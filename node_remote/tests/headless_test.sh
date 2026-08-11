#!/usr/bin/env bash
# node_remote — headless end-to-end test. No Basecamp, no display, no phone.
#
# Proves, in order:
#   T1  module loads under the logoscore daemon and every method is callable
#   T2  getNodeStatus() reports honestly when the node API is unreachable
#   T3  startRemote() binds a LOOPBACK-ONLY http port (never 0.0.0.0)
#   T4  the http surface fails CLOSED on a bad/absent token; /v1/ping needs none
#   T5  tor publishes a v3 onion descriptor
#   T6  the onion is reachable over Tor and returns the status payload
#   T7  CLIENT AUTH: an unauthorized client gets NO response at all
#
# T7 is the whole privacy claim of this module. A 401 would be a FAILURE there —
# the requirement is that an unpaired party cannot even confirm the service exists.
#
# NOTE: the logoscore CLI is now a daemon+client (subcommands: daemon/load-module/call/stop).
# The basecamp-skills recipe `logoscore-headless-testing` describes an older single-shot
# `-c ... --quit-on-finish` CLI that no longer exists. Daemon mode is what makes this test
# possible at all — the onion needs 60-120s to publish, far longer than one-shot allows.
set -uo pipefail

MOD=node_remote
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SO="$HERE/result/lib/${MOD}_plugin.so"
source "$(dirname "$0")/lib.sh"
LOGOSCORE=$(find /nix/store -maxdepth 4 -name logoscore -path '*/bin/*' 2>/dev/null | head -1)
TORSOCKS=$(command -v torsocks || true)

pass=0; fail=0; skip=0
ok()   { echo "  PASS  $*"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail+1)); }
skp()  { echo "  SKIP  $*"; skip=$((skip+1)); }

[ -f "$SO" ]        || { echo "no plugin at $SO — run: nix build .#packages.x86_64-linux.default"; exit 1; }
[ -n "$LOGOSCORE" ] || { echo "logoscore not found"; exit 1; }

export LOGOSCORE_CONFIG_DIR=$(mktemp -d)
MDIR=$(mktemp -d)
export NODE_REMOTE_TOKEN="tok-$(head -c12 /dev/urandom | od -An -tx1 | tr -d ' \n')"

cleanup() {
  "$LOGOSCORE" call "$MOD" stopRemote >/dev/null 2>&1
  "$LOGOSCORE" stop >/dev/null 2>&1
  sleep 1
  pkill -f "tor -f $TMPDIR_TOR" 2>/dev/null
  rm -rf "$MDIR" "$LOGOSCORE_CONFIG_DIR"
}
trap cleanup EXIT
TMPDIR_TOR="${TMPDIR:-/tmp}/node_remote/tor"

stage_modules "$MDIR" "$SO"

# Start a fresh, isolated daemon.
"$LOGOSCORE" -D -m "$MDIR" > /tmp/node_remote-daemon.log 2>&1 &
for _ in $(seq 1 20); do "$LOGOSCORE" status >/dev/null 2>&1 && break; sleep 1; done

# The daemon wraps every return in an envelope whose .result is the module's JSON
# as an ESCAPED STRING (the double-JSON wrapper — see skill logosresult-json-wrapper-ipc).
# Unwrap it here so every assertion below reads plain JSON.
call() {  # call <method> [args...]   — CLI is positional: call <module> <method> [args...]
  "$LOGOSCORE" call "$MOD" "$@" 2>&1 | python3 -c '
import sys,json
raw=sys.stdin.read().strip()
try:
    env=json.loads(raw)
except Exception:
    print(raw); sys.exit()
r=env.get("result", env)
if isinstance(r,str):
    try: r=json.loads(r)
    except Exception: pass
print(json.dumps(r,separators=(",",":")) if not isinstance(r,str) else r)
'
}

echo "== T1  module loads, methods callable"
LOADOUT=$("$LOGOSCORE" load-module "$MOD" 2>&1)
INFO=$(call getRemoteInfo)
if echo "$INFO" | grep -q '"running"'; then ok "loads + getRemoteInfo() → $INFO"
else bad "load/call failed: $LOADOUT / $INFO"; echo "--- daemon log ---"; tail -25 /tmp/node_remote-daemon.log; exit 1; fi

echo "== T2  honest status when the node API is unreachable"
S=$(call getNodeStatus)
echo "      $S"
if   echo "$S" | grep -q '"reachable":false'; then ok "reports reachable:false; no invented data"
elif echo "$S" | grep -q '"reachable":true'; then
  # A live node must yield a real height/slot — nulls here mean we read the wrong nesting.
  if echo "$S" | grep -qE '"height":[0-9]+' && echo "$S" | grep -qE '"slot":[0-9]+'; then
    ok "live node: $(echo "$S" | grep -oE '"height":[0-9]+|"state":"[A-Za-z]+"|"peers":[0-9]+' | tr '\n' ' ')"
  else bad "reachable but height/slot missing — nested-field bug: $S"; fi
else bad "unparseable: $S"; fi

echo "== T3  startRemote binds loopback only"
ST=$(call startRemote); echo "      $ST"
PORT=$(echo "$ST" | grep -oE '"port":[0-9]+' | head -1 | cut -d: -f2)
if [ -n "${PORT:-}" ] && [ "$PORT" -gt 0 ] 2>/dev/null; then
  if ss -ltnp 2>/dev/null | grep -q "127.0.0.1:$PORT"; then ok "bound 127.0.0.1:$PORT"
  else bad "port $PORT not bound to loopback"; ss -ltn | grep ":$PORT" || true; fi
  if ss -ltn 2>/dev/null | grep -qE "0\.0\.0\.0:$PORT|\[::\]:$PORT"; then
    bad "ALSO bound on a wildcard address — control surface exposed to the LAN"
  else ok "not bound on any wildcard address"; fi
else bad "startRemote did not return a port: $ST"; fi

echo "== T4  http surface auth"
if [ -n "${PORT:-}" ]; then
  P=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/v1/ping")
  [ "$P" = "200" ] && ok "/v1/ping 200 without a token" || bad "/v1/ping got $P"
  U=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/v1/status")
  [ "$U" = "401" ] && ok "/v1/status 401 with no token (fails closed)" || bad "/v1/status no-token got $U"
  W=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer wrong" "http://127.0.0.1:$PORT/v1/status")
  [ "$W" = "401" ] && ok "/v1/status 401 with a wrong token" || bad "/v1/status wrong-token got $W"
  G=$(curl -s -H "Authorization: Bearer $NODE_REMOTE_TOKEN" "http://127.0.0.1:$PORT/v1/status")
  echo "$G" | grep -q '"apiBase"' && ok "/v1/status 200 with the right token" || bad "authorized GET failed: $G"
fi

echo "== T5  onion descriptor publish (up to 150s)"
ONION=""; READY=""
for i in $(seq 1 50); do
  I=$(call getRemoteInfo)
  ONION=$(echo "$I" | grep -oE '"onion":"[a-z2-7]+\.onion"' | cut -d'"' -f4)
  READY=$(echo "$I" | grep -oE '"ready":(true|false)' | cut -d: -f2)
  [ "$READY" = "true" ] && break
  E=$(echo "$I" | grep -oE '"error":"[a-z_]+"' | cut -d'"' -f4)
  if [ -n "${E:-}" ]; then bad "onion failed: $E (see /tmp/node_remote/tor-fail.log)"; break; fi
  sleep 3
done
if [ "$READY" = "true" ] && [ -n "$ONION" ]; then ok "descriptor published: $ONION"
else bad "onion not ready after ~150s (onion='$ONION' ready='$READY')"; fi

echo "== T6  reachable over Tor"
# Use curl's own SOCKS5h rather than torsocks: our tor runs SocksPort 0 (it serves the
# hidden service only), so the CLIENT proxy is the system tor on 9050.
# Also: the descriptor is served a few seconds after upload is ACKed — probing instantly
# reads a healthy onion as dead.
if [ -n "$ONION" ] && ss -ltn 2>/dev/null | grep -q ":9050"; then
  sleep 12
  R=$(timeout 130 curl -s --socks5-hostname 127.0.0.1:9050 --max-time 120 \
        -H "Authorization: Bearer $NODE_REMOTE_TOKEN" "http://$ONION/v1/status" 2>/dev/null)
  if echo "$R" | grep -q '"apiBase"'; then
    ok "onion served live status over Tor: $(echo "$R" | grep -oE '"height":[0-9]+' | head -1)"
  else bad "no status over Tor (got: ${R:0:120})"; fi
else skp "T6 needs a tor SOCKS proxy on 9050 and a published onion"; fi

echo "== T7  client auth — unauthorized client gets NOTHING"
if [ -n "$ONION" ]; then
  A=$(call authorizeClient "testphone" "$(head -c32 /dev/urandom | base32 | tr -d '=' | head -c52)")
  echo "      authorizeClient → $A"
  if echo "$A" | grep -q '"ok":true'; then
    ok "authorized-client file written"
    echo "      NOTE: takes effect after a tor restart; T7's negative probe is asserted"
    echo "            by tests/client_auth_test.sh, which restarts tor and verifies that"
    echo "            an unauthorized fetch TIMES OUT rather than returning 401."
    skp "T7 negative probe (needs tor restart — see client_auth_test.sh)"
  else bad "authorizeClient failed: $A"; fi
fi

echo
echo "== summary: $pass passed, $fail failed, $skip skipped"
[ "$fail" -eq 0 ]
