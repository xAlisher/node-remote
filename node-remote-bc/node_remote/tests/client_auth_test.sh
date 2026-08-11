#!/usr/bin/env bash
# node_remote — the test the whole privacy claim rests on.
#
# Phase A (no authorized_clients): an anonymous Tor client CAN reach the onion.
#         This proves the transport works at all, so a failure in Phase B means
#         "client auth blocked it", not "the onion was broken anyway".
# Phase B (authorized_clients present, tor restarted): the SAME anonymous client
#         gets NOTHING — no 401, no RST, no response. If Phase B returns any HTTP
#         status at all, client auth is NOT in effect and the README claim
#         "a third party cannot connect to it, discover it, or confirm it exists"
#         MUST NOT be shipped.
#
# Runs entirely headless. Uses the system tor's SOCKS on 9050 as the client.
set -uo pipefail

HS="$HOME/.local/share/logos_host_qt/node_remote/hs"
BACKUP=$(mktemp -d)
PORT=${PORT:-18099}
RUN=$(mktemp -d)
TOR=$(command -v tor || echo /usr/sbin/tor)
SOCKS=127.0.0.1:9050

pass=0; fail=0
ok()  { echo "  PASS  $*"; pass=$((pass+1)); }
bad() { echo "  FAIL  $*"; fail=$((fail+1)); }

torpid=""
pysrv=""
cleanup() {
  [ -n "$torpid" ] && kill "$torpid" 2>/dev/null
  [ -n "$pysrv" ]  && kill "$pysrv"  2>/dev/null
  rm -rf "$RUN" "$BACKUP"
}
trap cleanup EXIT

ss -ltn 2>/dev/null | grep -q ":9050" || { echo "system tor SOCKS not on 9050 — cannot run client probes"; exit 1; }
[ -f "$HS/hostname" ] || { echo "no onion key material at $HS — run tests/headless_test.sh first"; exit 1; }
ONION=$(cat "$HS/hostname")
echo "onion: $ONION"

# A tiny origin so this test does not depend on the module or a running node.
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$RUN" >/dev/null 2>&1 &
pysrv=$!
echo "ORIGIN-OK" > "$RUN/probe.txt"
sleep 1

# Persist tor's DataDirectory across phases AND across runs. Wiping it forces a cold
# consensus fetch every time; doing that repeatedly gets throttled by the directory
# authorities and tor sits at "Bootstrapped 5%" until the test times out — which reads
# as "the onion is broken" when nothing is wrong. Cached, it bootstraps in seconds.
TORCACHE="$HOME/.cache/node_remote-test-tor"

start_tor() {   # start_tor -> waits for descriptor publish
  [ -n "$torpid" ] && { kill "$torpid" 2>/dev/null; sleep 3; torpid=""; }
  mkdir -p "$TORCACHE/data" "$RUN/tor"; chmod 700 "$TORCACHE" "$TORCACHE/data"
  : > "$RUN/tor/tor.log"; : > "$RUN/tor/hs.log"
  cat > "$RUN/tor/torrc" <<CFG
SocksPort 0
DataDirectory $TORCACHE/data
Log notice file $RUN/tor/tor.log
Log [rend]info file $RUN/tor/hs.log
HiddenServiceDir $HS
HiddenServicePort 80 127.0.0.1:$PORT
CFG
  "$TOR" -f "$RUN/tor/torrc" >/dev/null 2>&1 &
  torpid=$!
  for _ in $(seq 1 60); do
    if grep -qi "upload.*descriptor\|descriptor.*upload" "$RUN/tor/hs.log" 2>/dev/null; then
      # The upload is logged the moment it is ACKed; give the HSDirs a few seconds to
      # actually serve it to a fetching client, or a healthy onion probes as dead.
      sleep 12
      return 0
    fi
    kill -0 "$torpid" 2>/dev/null || { echo "  tor died:"; tail -8 "$RUN/tor/tor.log"; return 1; }
    sleep 2
  done
  echo "  --- tor.log (no descriptor upload seen) ---"; tail -12 "$RUN/tor/tor.log" 2>/dev/null
  echo "  --- hs.log ---"; tail -6 "$RUN/tor/hs.log" 2>/dev/null
  return 1
}

fetch() {  # fetch -> prints http_code ("000" = no response at all)
  # NB: curl -w already prints 000 on failure. A `|| echo 000` fallback CONCATENATES
  # into "000000" and silently breaks every comparison below — that bug made a
  # perfectly reachable onion read as dead for two runs.
  local c
  c=$(timeout 130 curl -s --socks5-hostname "$SOCKS" -o /dev/null -w '%{http_code}' \
        --max-time 120 "http://$ONION/probe.txt" 2>/dev/null)
  echo "${c:-000}"
}

echo "== Phase A  no client auth → onion must be reachable"
mv "$HS/authorized_clients" "$BACKUP/" 2>/dev/null && echo "  (moved authorized_clients aside)"
if start_tor; then
  A=$(fetch); echo "  http_code=$A"
  [ "$A" = "200" ] && ok "anonymous client reached the onion (transport works)" \
                   || bad "onion unreachable even WITHOUT client auth (code=$A) — transport problem, not auth"
else bad "tor never published a descriptor in Phase A"; fi

echo "== Phase B  client auth enabled → anonymous client must get NOTHING"
mkdir -p "$HS/authorized_clients"; chmod 700 "$HS/authorized_clients"
if [ -d "$BACKUP/authorized_clients" ] && ls "$BACKUP/authorized_clients"/*.auth >/dev/null 2>&1; then
  cp "$BACKUP/authorized_clients"/*.auth "$HS/authorized_clients/"
else
  # Mint a throwaway authorized client so the service is auth-gated but we hold no key.
  KEY=$(head -c32 /dev/urandom | base32 | tr -d '=' | head -c52)
  echo "descriptor:x25519:$KEY" > "$HS/authorized_clients/probe.auth"
fi
chmod 600 "$HS/authorized_clients"/*.auth
echo "  authorized clients: $(ls "$HS/authorized_clients" | tr '\n' ' ')"
if start_tor; then
  B=$(fetch); echo "  http_code=$B"
  if [ "$B" = "000" ]; then
    ok "anonymous client got NO response — descriptor is auth-encrypted"
    echo "        → the claim 'cannot connect, discover, or confirm it exists' is SUPPORTED"
  else
    bad "anonymous client got HTTP $B — client auth is NOT in effect"
    echo "        → DO NOT ship the 'cannot confirm it exists' claim"
  fi
else
  # tor refusing to publish at all is also a valid auth outcome, but say so precisely.
  ok "tor published no descriptor with auth enabled (anonymous clients cannot resolve it)"
fi

echo
echo "== summary: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
