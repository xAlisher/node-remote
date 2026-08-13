#!/usr/bin/env bash
# node_remote — pairing end to end, headless. No phone.
#
# This is the test that ties the whole privacy design together. It plays the phone's
# part by hand: take the client-auth key out of the QR URI, feed it to a CLIENT tor
# exactly as kmp-tor's ONION_CLIENT_AUTH_ADD would, and try to reach the service.
#
#   P1  beginPairing returns a well-formed lgnode:// URI, a 6-digit SAS, an expiry,
#       and writes an authorized_clients entry
#   P2  a client holding the QR's key REACHES the onion            (200)
#   P3  a client WITHOUT the key gets NOTHING                      (000, not 401)
#   P4  after revoke, the formerly-paired key ALSO gets nothing    (000)
#
# P3 is the privacy claim. P4 is the revocation claim. P2 is the control that stops
# either of them passing just because the onion happens to be broken.
set -uo pipefail

MOD=node_remote
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/tests/lib.sh"
LOGOSCORE=$(find /nix/store -maxdepth 4 -name logoscore -path '*/bin/*' 2>/dev/null | head -1)
TOR=$(command -v tor || echo /usr/sbin/tor)

pass=0; fail=0
ok()  { echo "  PASS  $*"; pass=$((pass+1)); }
bad() { echo "  FAIL  $*"; fail=$((fail+1)); }

# ISOLATE THE MODULE'S DATA DIR. This suite calls beginPairing and revokeClient for real;
# without XDG_DATA_HOME it does so against the MACHINE'S OWN pairing, so running it on a
# host with a paired phone silently revokes that phone. A leftover "pixel10" client from an
# earlier run of this very file is what made the point.
#
# Stable, not mktemp: tor's DataDirectory hangs off this, and a fresh one each run forces a
# cold consensus fetch that presents as an onion that never publishes.
export XDG_DATA_HOME="${NR_TEST_HOME:-$HOME/.cache/node_remote-test-home}"
mkdir -p "$XDG_DATA_HOME"
rm -rf "$XDG_DATA_HOME"/*/node_remote/hs "$XDG_DATA_HOME"/*/node_remote/last_seen 2>/dev/null

export LOGOSCORE_CONFIG_DIR=$(mktemp -d)
MDIR=$(mktemp -d); RUN=$(mktemp -d)
export NODE_REMOTE_TOKEN="tok-$(head -c12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
CLIENT_TOR_PID=""
cleanup() {
  [ -n "$CLIENT_TOR_PID" ] && kill "$CLIENT_TOR_PID" 2>/dev/null
  "$LOGOSCORE" call "$MOD" stopRemote >/dev/null 2>&1
  "$LOGOSCORE" stop >/dev/null 2>&1
  rm -rf "$MDIR" "$RUN" "$LOGOSCORE_CONFIG_DIR"
}
trap cleanup EXIT

# Persist the client tor's DataDirectory across runs: a cold consensus fetch every time
# gets throttled and tor sits at "Bootstrapped 5%" until the test times out.
CTOR_CACHE="$HOME/.cache/node_remote-clienttor"
SOCKS=19055

start_client_tor() {   # start_client_tor [auth_priv_base32]
  [ -n "$CLIENT_TOR_PID" ] && { kill "$CLIENT_TOR_PID" 2>/dev/null; sleep 3; CLIENT_TOR_PID=""; }
  rm -rf "$RUN/cauth"; mkdir -p "$RUN/cauth" "$CTOR_CACHE/data"
  chmod 700 "$RUN/cauth" "$CTOR_CACHE" "$CTOR_CACHE/data"
  if [ -n "${1:-}" ]; then
    # This is exactly what kmp-tor's ONION_CLIENT_AUTH_ADD writes:
    #   <onion-without-suffix>:descriptor:x25519:<base32 private key>
    echo "${ONION%.onion}:descriptor:x25519:$1" > "$RUN/cauth/nr.auth_private"
    chmod 600 "$RUN/cauth/nr.auth_private"
  fi
  cat > "$RUN/ctorrc" <<CFG
SocksPort $SOCKS
DataDirectory $CTOR_CACHE/data
ClientOnionAuthDir $RUN/cauth
Log notice file $RUN/ctor.log
CFG
  "$TOR" -f "$RUN/ctorrc" >/dev/null 2>&1 &
  CLIENT_TOR_PID=$!
  for _ in $(seq 1 45); do
    grep -q "Bootstrapped 100%" "$RUN/ctor.log" 2>/dev/null && { sleep 3; return 0; }
    kill -0 "$CLIENT_TOR_PID" 2>/dev/null || return 1
    sleep 2
  done
  return 1
}

probe() {  # -> http code ("000" = no response at all)
  local c
  c=$(timeout 140 curl -s --socks5-hostname "127.0.0.1:$SOCKS" -o /dev/null \
        -w '%{http_code}' --max-time 130 "http://$ONION/v1/ping" 2>/dev/null)
  echo "${c:-000}"
}

# Fresh key material so a previous run's authorized_clients cannot skew the result.
rm -rf "$HOME/.local/share/logos_host_qt/node_remote/hs"

stage_modules "$MDIR" "$HERE/result/lib/${MOD}_plugin.so" >/dev/null
"$LOGOSCORE" -D -m "$MDIR" > /tmp/nr-pair.log 2>&1 &
for _ in $(seq 1 20); do "$LOGOSCORE" status >/dev/null 2>&1 && break; sleep 1; done
"$LOGOSCORE" load-module "$MOD" >/dev/null 2>&1

call() { "$LOGOSCORE" call "$MOD" "$@" 2>&1 | lc_unwrap; }

call startRemote >/dev/null
echo "waiting for the onion to publish…"
ONION=""
for _ in $(seq 1 60); do
  I=$(call getRemoteInfo)
  [ "$(echo "$I" | grep -oE '"ready":(true|false)' | cut -d: -f2)" = "true" ] && {
    ONION=$(echo "$I" | grep -oE '[a-z2-7]{56}\.onion'); break; }
  sleep 3
done
[ -n "$ONION" ] || { echo "onion never published; aborting"; exit 1; }
echo "onion: $ONION"

echo "== P1  beginPairing"
PR=$(call beginPairing "pixel10"); echo "      ${PR:0:150}…"
URI=$(echo "$PR" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("uri",""))')
SAS=$(echo "$PR" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("sas",""))')
CA=$(echo "$URI" | sed -n 's/.*[?&]ca=\([a-z2-7]*\).*/\1/p')
URI_ONION=$(echo "$URI" | sed -n 's/.*[?&]onion=\([a-z2-7]*\.onion\).*/\1/p')
echo "      SAS=$SAS  ca=${CA:0:12}…(${#CA} chars)"
[ "${#SAS}" = 6 ] && [ "${#CA}" = 52 ] && [[ "$URI" == lgnode://pair* ]] \
  && ok "URI + 6-digit SAS + 52-char client-auth key" \
  || bad "malformed pairing payload"
# The URI must carry the onion. It came back EMPTY once, because beginPairing reloads
# tor (to apply the new auth) and the reload cleared the cached address.
[ "$URI_ONION" = "$ONION" ] && ok "URI carries the onion address" \
  || bad "URI onion is '$URI_ONION', expected '$ONION'"

# beginPairing RESTARTS tor so the new authorized_clients entry takes effect; wait for
# the descriptor to republish before probing, or a working service reads as dead.
echo "      waiting for re-publish after pairing reload…"
for _ in $(seq 1 60); do
  I=$(call getRemoteInfo)
  [ "$(echo "$I" | grep -oE '"ready":(true|false)' | cut -d: -f2)" = "true" ] && break
  sleep 3
done

echo "== P2  a client WITH the key reaches the onion"
if start_client_tor "$CA"; then
  A=$(probe); echo "      http_code=$A"
  [ "$A" = "200" ] && ok "paired client reached /v1/ping" || bad "paired client got $A"
else bad "client tor failed to bootstrap"; fi

echo "== P3  a client WITHOUT the key gets nothing"
if start_client_tor ""; then
  B=$(probe); echo "      http_code=$B"
  if [ "$B" = "000" ]; then ok "unpaired client got NO response"
  else bad "unpaired client got HTTP $B — client auth is not in effect"; fi
else bad "client tor failed to bootstrap"; fi

echo "== P4  after revoke, the paired key stops working"
call revokeClient "pixel10" >/dev/null
call stopRemote >/dev/null; sleep 2; call startRemote >/dev/null
for _ in $(seq 1 60); do
  I=$(call getRemoteInfo)
  [ "$(echo "$I" | grep -oE '"ready":(true|false)' | cut -d: -f2)" = "true" ] && break
  sleep 3
done
if start_client_tor "$CA"; then
  C=$(probe); echo "      http_code=$C"
  if [ "$C" = "000" ]; then ok "revoked key gets NO response"
  else bad "revoked key still got HTTP $C — revocation is not effective"; fi
else bad "client tor failed to bootstrap"; fi

echo
echo "== summary: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
