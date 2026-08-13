#!/usr/bin/env bash
# node_remote — pairing STABILITY, headless. No phone.
#
# pairing_e2e.sh proves a pairing can be established and revoked. This proves the harder
# thing: that an established pairing SURVIVES. Every case here is a way the desktop can
# invalidate a working phone without either end noticing, because an un-authorized tor
# client is indistinguishable from no client at all — no error, no log line, nothing but
# requests that time out forever.
#
#   S1  beginPairing REFUSES once a device has authenticated, and does NOT rotate the key
#   S2  the paired client still reaches the onion after that refusal          (200)
#   S3  a NEVER-USED key is replaceable — an expired code must be re-mintable
#   S4  revoke -> pair rotates for real: old key loses access, new key works
#   S5  authorizing a client clears the _sealed deny-all sentinel
#
# S1 is the regression this file exists for. The field failure was a rotation at 17:58:53
# that left authorized_clients holding one public key while the phone held the private
# half of another; the pane had called beginPairing() from a POLL TIMER, so no human
# action corresponded to it and nothing in either UI changed.
set -uo pipefail

MOD=node_remote
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/tests/lib.sh"
LOGOSCORE=$(find /nix/store -maxdepth 4 -name logoscore -path '*/bin/*' 2>/dev/null | head -1)
TOR=$(command -v tor || echo /usr/sbin/tor)

pass=0; fail=0
ok()  { echo "  PASS  $*"; pass=$((pass+1)); }
bad() { echo "  FAIL  $*"; fail=$((fail+1)); }

export LOGOSCORE_CONFIG_DIR=$(mktemp -d)
MDIR=$(mktemp -d); RUN=$(mktemp -d)
export NODE_REMOTE_TOKEN="tok-$(head -c12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
CLIENT_TOR_PID=""
cleanup() {
  [ -n "$CLIENT_TOR_PID" ] && kill "$CLIENT_TOR_PID" 2>/dev/null
  [ -n "${SVC_TOR_PID:-}" ] && kill "$SVC_TOR_PID" 2>/dev/null
  "$LOGOSCORE" call "$MOD" stopRemote >/dev/null 2>&1
  "$LOGOSCORE" stop >/dev/null 2>&1
  rm -rf "$MDIR" "$RUN" "$LOGOSCORE_CONFIG_DIR"
}
trap cleanup EXIT

# ISOLATE THE MODULE'S DATA DIR. The module resolves state through
# QStandardPaths::AppDataLocation, which honours XDG_DATA_HOME -- without this the test
# reads and WRITES the machine's real pairing. Not hypothetical: an early run reported
# clients:["pixel10"], a leftover pairing belonging to the host, and the same run on a
# machine with a live phone would have revoked it. A test for "do not destroy the user's
# pairing" must not destroy the user's pairing.
# STABLE, not mktemp: tor's DataDirectory is derived from this, and a fresh one every run
# means a COLD consensus fetch every run — two-plus minutes before the onion can publish,
# which is what turned a working test into "onion never published". pairing_e2e.sh makes
# the same point about its client tor. A fixed path under the cache dir keeps the
# consensus warm while still being nowhere near the user's real pairing.
export XDG_DATA_HOME="${NR_TEST_HOME:-$HOME/.cache/node_remote-test-home}"
mkdir -p "$XDG_DATA_HOME"

# Each run still starts from a clean SERVICE state — only tor's cache is shared.
rm -rf "$XDG_DATA_HOME"/*/node_remote/hs "$XDG_DATA_HOME"/*/node_remote/last_seen 2>/dev/null

# Globbed, not hardcoded: under logoscore the app dir is `logos_host_qt`, under Basecamp
# it is `.logos_host.elf`. Hardcoding the Basecamp one pointed every assertion at a
# directory the module never touched, so they all "failed" while reading nothing.
authdir() { echo "$XDG_DATA_HOME"/*/node_remote/hs/authorized_clients; }

pubkey_of() { sed -n 's/^descriptor:x25519://p' "$(authdir)/$1.auth" 2>/dev/null; }

SO="${SO:-/tmp/lc32x/variants/linux-amd64/node_remote_plugin.so}"
[ -f "$SO" ] || { echo "no plugin at $SO — build first"; exit 2; }
[ -n "$LOGOSCORE" ] || { echo "logoscore not found"; exit 2; }
stage_modules "$MDIR" "$SO"

# Daemon bootstrap per skill logoscore-headless-testing: `-D` + `load-module`. There is no
# `logoscore start` subcommand — using one produced a run where every assertion "failed"
# with an empty string, including two that PASSED for the wrong reason (no key file means
# no sentinel, and no service means no access).
"$LOGOSCORE" -D -m "$MDIR" > /tmp/node_remote-pairing-daemon.log 2>&1 &
for _ in $(seq 1 20); do "$LOGOSCORE" status >/dev/null 2>&1 && break; sleep 1; done
"$LOGOSCORE" load-module "$MOD" >/dev/null 2>&1

# beginPairing() restarts tor (reload() re-reads authorized_clients), so readiness is lost
# after EVERY pairing and the descriptor must republish before anything can connect.
wait_ready() {
  local t0=$SECONDS
  for _ in $(seq 1 ${1:-60}); do
    if call getRemoteInfo | grep -q '"ready":true'; then
      REPUBLISH_SECS=$((SECONDS - t0))
      return 0
    fi
    sleep 2
  done
  REPUBLISH_SECS=$((SECONDS - t0))
  return 1
}

# The daemon wraps returns in an envelope whose .result is the module's JSON as an ESCAPED
# STRING — see skill logosresult-json-wrapper-ipc.
call() {
  "$LOGOSCORE" call "$MOD" "$@" 2>&1 | python3 -c '
import sys,json
raw=sys.stdin.read().strip()
try: env=json.loads(raw)
except Exception: print(raw); sys.exit()
r=env.get("result", env)
if isinstance(r,str):
    try: r=json.loads(r)
    except Exception: pass
print(json.dumps(r,separators=(",",":")) if not isinstance(r,str) else r)
'
}

# The module spawns its own tor; note its pid so cleanup can end it without a pattern
# kill. Captured after startRemote below.
SVC_TOR_PID=""

if ! call getRemoteInfo | grep -q '"running"'; then
  echo "  FAIL  module did not load — aborting rather than reporting phantom results"
  tail -25 /tmp/node_remote-pairing-daemon.log
  exit 1
fi

echo "== node_remote pairing stability =="

call startRemote >/dev/null 2>&1
sleep 3
SVC_TOR_PID=$(pgrep -f "tor -f ${TMPDIR:-/tmp}/node_remote/tor/torrc" | head -1)
# The onion must publish before beginPairing will issue anything.
wait_ready 120 || { echo "  FAIL  onion never published — nothing below can be meaningful"; exit 1; }

# ── Establish a pairing and record the key that backs it. ────────────────────────
P1=$(call beginPairing phone)
KEY1=$(pubkey_of phone)
wait_ready || echo "  WARN  onion did not republish after the first pairing"
URI1=$(echo "$P1" | sed -n 's/.*"uri":"\([^"]*\)".*/\1/p')
TOK1=$(echo "$URI1" | sed -n 's/.*[?&]t=\([^&"]*\).*/\1/p')
if [ -n "$KEY1" ]; then ok "pairing established, key on disk"; else bad "no key written"; fi

# ── S3 FIRST: while nothing has authenticated, a re-mint must be ALLOWED. ────────
# Ordering matters — once S1 makes a device "seen" this case is no longer reachable.
P2=$(call beginPairing phone)
KEY2=$(pubkey_of phone)
wait_ready || echo "  WARN  onion did not republish after the re-mint"
if echo "$P2" | grep -q '"ok":true' && [ "$KEY2" != "$KEY1" ]; then
  ok "S3  never-used key is replaceable (expired code can be re-minted)"
else
  bad "S3  re-mint refused for an unused key — an expired code would strand the user"
fi

# ── Bring a client up holding KEY2's private half, and make it authenticate. ─────
ONION=$(call getRemoteInfo \
        | sed -n 's/.*"onion":"\([^"]*\)".*/\1/p')
CA2=$(echo "$P2" | sed -n 's/.*[?&]ca=\([^&"]*\).*/\1/p')
TOK2=$(echo "$P2" | sed -n 's/.*[?&]t=\([^&"]*\).*/\1/p')

CDATA=$(mktemp -d); SOCKS=19951
cat > "$CDATA/torrc" <<EOF
SocksPort $SOCKS
DataDirectory $CDATA/d
ClientOnionAuthDir $CDATA/auth
EOF
mkdir -p "$CDATA/auth" "$CDATA/d"; chmod 700 "$CDATA/auth" "$CDATA/d"
echo "${ONION%.onion}:descriptor:x25519:$CA2" > "$CDATA/auth/phone.auth_private"
"$TOR" -f "$CDATA/torrc" >"$CDATA/tor.log" 2>&1 &
CLIENT_TOR_PID=$!

fetch() { curl -s -o /dev/null -w '%{http_code}' --max-time 60 \
            --socks5-hostname "127.0.0.1:$SOCKS" \
            -H "Authorization: Bearer $2" "http://$ONION/v1/status" 2>/dev/null; }

CODE=000
for _ in $(seq 1 20); do
  CODE=$(fetch "$ONION" "$TOK2"); [ "$CODE" = "200" ] && break; sleep 10
done
if [ "$CODE" = "200" ]; then ok "client reached the onion (control)"; else bad "control failed: $CODE"; fi

# ── S1: NOW a device has authenticated. Rotation must be refused. ────────────────
KEY_BEFORE=$(pubkey_of phone)
R=$(call beginPairing phone)
KEY_AFTER=$(pubkey_of phone)
if echo "$R" | grep -q 'already_paired'; then
  ok "S1a beginPairing refuses once a device has authenticated"
else
  bad "S1a beginPairing did NOT refuse: $R"
fi
if [ "$KEY_BEFORE" = "$KEY_AFTER" ] && [ -n "$KEY_AFTER" ]; then
  ok "S1b the on-disk key was NOT rotated"
else
  bad "S1b KEY ROTATED under a live pairing: $KEY_BEFORE -> $KEY_AFTER"
fi

# ── S2: and the paired client still works. ───────────────────────────────────────
CODE=$(fetch "$ONION" "$TOK2")
if [ "$CODE" = "200" ]; then ok "S2  paired client still reaches the onion"
else bad "S2  paired client lost access after a refused rotation: $CODE"; fi

# ── S5: the deny-all sentinel must not outlive a real client. ────────────────────
if [ ! -f "$(authdir)/_sealed.auth" ]; then
  ok "S5  _sealed sentinel cleared while a real client is authorized"
else
  bad "S5  _sealed.auth still present alongside a real client"
fi

# ── S6: republish after pairing is fast, i.e. reload() did not restart tor. ──────
if [ "${REPUBLISH_SECS:-999}" -le 45 ]; then
  ok "S6  onion republished ${REPUBLISH_SECS}s after pairing (SIGHUP, not a restart)"
else
  bad "S6  republish took ${REPUBLISH_SECS}s — reload() looks like a full tor restart"
fi

# ── S4: explicit revoke -> pair really does rotate. ──────────────────────────────
call revokeClient phone >/dev/null 2>&1
# Revocation restarts tor by design, so the onion must republish before a new code can be
# issued. The pane models this by setting busy and letting its poll loop mint the code once
# ready returns — an immediate revoke-then-pair fails with "onion not ready".
wait_ready 120 || echo "  WARN  onion did not republish after revocation"
P3=$(call beginPairing phone)
KEY3=$(pubkey_of phone)
wait_ready || echo "  WARN  onion did not republish after the rotation"
if echo "$P3" | grep -q '"ok":true' && [ "$KEY3" != "$KEY_AFTER" ]; then
  ok "S4a revoke then pair rotates the key"
else
  bad "S4a rotation after revoke did not happen: $P3"
fi
CODE=$(fetch "$ONION" "$TOK2")
# 000, not merely "not 200". A 401 means the revoked client still REACHED the service and
# was turned away by the token — which confirms the onion exists and is up, the very thing
# client auth hides. This assertion caught exactly that when reload() began HUPing.
if [ "$CODE" = "000" ]; then ok "S4b revoked key cannot reach the onion at all (000)"
elif [ "$CODE" = "200" ]; then bad "S4b REVOKED key still has full access"
else bad "S4b revoked key still reached the service (got $CODE, want 000) — revocation is only token-deep"; fi

rm -rf "$CDATA"
echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
