#!/usr/bin/env bash
# node_remote — E2: the control path (phone → HTTP → module → blockchain_module).
#
# SAFETY: this runs an ISOLATED logoscore daemon with its own blockchain_module
# instance. That instance has never started a node, so stop() cannot affect the
# node Basecamp is running on :8080. The test asserts that fact before and after.
#
#   C1  POST /v1/stop with a valid token completes a real IPC round-trip to
#       blockchain_module and returns a well-formed result.
#       ok:false with a real error IS A PASS — it proves the call reached the
#       module. What would be a FAIL is a transport error, a hang, or malformed JSON.
#   C2  POST /v1/stop with no token          → 401
#   C3  POST /v1/stop with a wrong token     → 401
#   C4  GET  /v1/stop                        → 405 (control routes are POST-only, so a
#                                              prefetch or a stray link cannot stop a node)
#   C5  the user's node on :8080 is STILL UP afterwards
#
# NOT proven here: a real stop→start cycle of a live node. That needs an isolated
# node config on non-default ports; see tests/README.
set -uo pipefail

MOD=node_remote
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SO="$HERE/result/lib/${MOD}_plugin.so"
LOGOSCORE=$(find /nix/store -maxdepth 4 -name logoscore -path '*/bin/*' 2>/dev/null | head -1)

pass=0; fail=0
ok()  { echo "  PASS  $*"; pass=$((pass+1)); }
bad() { echo "  FAIL  $*"; fail=$((fail+1)); }

[ -f "$SO" ] || { echo "no plugin — nix build first"; exit 1; }

# ISOLATE THE MODULE'S DATA DIR. The module resolves state through
# QStandardPaths::AppDataLocation, which honours XDG_DATA_HOME. Without this every suite
# here runs against the MACHINE'S OWN node_remote state -- it mints pairings, revokes
# clients and persists bearer tokens in the same directory a real paired phone depends on.
# Two concrete consequences already observed: a leftover "pixel10" client from an earlier
# run, and a persisted device_token that silently overrode NODE_REMOTE_TOKEN and turned
# every authorized request in this file into a 401.
#
# Stable path, not mktemp: tor's DataDirectory hangs off this, and a fresh one per run
# forces a cold consensus fetch that looks exactly like an onion that never publishes.
export XDG_DATA_HOME="${NR_TEST_HOME:-$HOME/.cache/node_remote-test-home}"
mkdir -p "$XDG_DATA_HOME"
# Service state is per-run; only tor's cache is shared.
rm -rf "$XDG_DATA_HOME"/*/node_remote/hs \
       "$XDG_DATA_HOME"/*/node_remote/last_seen \
       "$XDG_DATA_HOME"/*/node_remote/device_token 2>/dev/null

export LOGOSCORE_CONFIG_DIR=$(mktemp -d)
MDIR=$(mktemp -d)
export NODE_REMOTE_TOKEN="tok-$(head -c12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
DAEMON_PID=""
cleanup() {
  "$LOGOSCORE" call "$MOD" stopRemote >/dev/null 2>&1
  "$LOGOSCORE" stop >/dev/null 2>&1
  sleep 1
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null
  rm -rf "$MDIR" "$LOGOSCORE_CONFIG_DIR"
}
trap cleanup EXIT

# Baseline: the user's node must be up BEFORE we start, or C5 proves nothing.
NODE_BEFORE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 4 http://127.0.0.1:8080/cryptarchia/info)
echo "user's node on :8080 before → $NODE_BEFORE"

mkdir -p "$MDIR/$MOD"; cp "$SO" "$MDIR/$MOD/"
cat > "$MDIR/$MOD/manifest.json" <<JSON
{"name":"$MOD","version":"0.1.0","type":"core","manifestVersion":"0.2.0",
 "main":{"linux-amd64":"${MOD}_plugin.so","linux-amd64-dev":"${MOD}_plugin.so","linux-x86_64-dev":"${MOD}_plugin.so"},
 "dependencies":["blockchain_module"]}
JSON
echo linux-amd64-dev > "$MDIR/$MOD/variant"
# blockchain_module must be resolvable by the daemon for the typed call to dispatch.
BC=~/.local/share/Logos/LogosBasecamp/modules/blockchain_module
if [ -d "$BC" ]; then
  cp -r "$BC" "$MDIR/"
  # The installed module carries variant `linux-amd64` (what Basecamp wants), but
  # logoscore only resolves `-dev` variants and otherwise reports the module as
  # "not found in known modules" — which surfaces as an unrelated
  # "Cannot resolve dependencies for: node_remote". Add the -dev keys to the COPY.
  python3 - "$MDIR/blockchain_module/manifest.json" <<'PY'
import json,sys
p=sys.argv[1]; m=json.load(open(p))
main=m.get("main") or {}
so=main.get("linux-amd64") or next(iter(main.values()), None)
if so:
    main["linux-amd64-dev"]=so; main["linux-x86_64-dev"]=so
    m["main"]=main; json.dump(m,open(p,"w"),indent=2)
    print("  patched blockchain_module manifest with -dev variants ->", so)
PY
  echo linux-amd64-dev > "$MDIR/blockchain_module/variant"
  echo "staged blockchain_module for the isolated daemon"
fi

"$LOGOSCORE" -D -m "$MDIR" > /tmp/node_remote-control.log 2>&1 &
DAEMON_PID=$!
for _ in $(seq 1 20); do "$LOGOSCORE" status >/dev/null 2>&1 && break; sleep 1; done
"$LOGOSCORE" load-module "$MOD" >/dev/null 2>&1

call() { "$LOGOSCORE" call "$MOD" "$@" 2>&1 | python3 -c '
import sys,json
raw=sys.stdin.read().strip()
try: env=json.loads(raw)
except Exception: print(raw); sys.exit()
r=env.get("result",env)
if isinstance(r,str):
    try: r=json.loads(r)
    except Exception: pass
print(json.dumps(r,separators=(",",":")) if not isinstance(r,str) else r)
'; }

ST=$(call startRemote)
PORT=$(echo "$ST" | grep -oE '"port":[0-9]+' | head -1 | cut -d: -f2)
[ -n "${PORT:-}" ] || { echo "startRemote failed: $ST"; exit 1; }
echo "http surface on 127.0.0.1:$PORT"

echo "== C1  POST /v1/stop reaches blockchain_module"
R=$(curl -s --max-time 60 -X POST -H "Authorization: Bearer $NODE_REMOTE_TOKEN" \
      "http://127.0.0.1:$PORT/v1/stop")
echo "      $R"
if echo "$R" | python3 -c 'import sys,json; d=json.load(sys.stdin); sys.exit(0 if "ok" in d else 1)' 2>/dev/null; then
  ok "IPC round-trip completed, well-formed result"
else
  bad "no well-formed result — the call did not reach blockchain_module: $R"
fi

echo "== C2/C3  auth on control routes"
N=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X POST "http://127.0.0.1:$PORT/v1/stop")
[ "$N" = "401" ] && ok "no token → 401" || bad "no token → $N"
W=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X POST -H "Authorization: Bearer wrong" "http://127.0.0.1:$PORT/v1/stop")
[ "$W" = "401" ] && ok "wrong token → 401" || bad "wrong token → $W"

echo "== C4  control routes are POST-only"
G=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -H "Authorization: Bearer $NODE_REMOTE_TOKEN" "http://127.0.0.1:$PORT/v1/stop")
if [ "$G" = "405" ] || [ "$G" = "404" ]; then ok "GET /v1/stop → $G (not executed)"
else bad "GET /v1/stop → $G — a GET must never stop a node"; fi

echo "== C5  the user's node is untouched"
NODE_AFTER=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8080/cryptarchia/info)
if [ "$NODE_BEFORE" = "200" ] && [ "$NODE_AFTER" = "200" ]; then ok "node still serving on :8080"
elif [ "$NODE_BEFORE" != "200" ]; then echo "  SKIP  no node was running before the test (baseline $NODE_BEFORE)"
else bad "node was up before ($NODE_BEFORE) and is now $NODE_AFTER — THE TEST STOPPED THE USER'S NODE"; fi

echo
echo "== summary: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
