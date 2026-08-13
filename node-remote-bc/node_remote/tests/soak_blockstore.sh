#!/usr/bin/env bash
# node_remote — SOAK the cross-thread race behind #3, headlessly.
#
# #3 is a SIGSEGV seen once with the node in initial block download and a phone polling.
# Two containers were found unguarded and fixed (the balance strings, then BlockStore), but
# "no crash since" is not evidence — a previous mitigation in this same code was called a fix
# and crashed again 77s later.
#
# The defect does not need a phone or Tor. It needs the HTTP handler thread reading a
# container while the module's timers and the onNewBlock callback write it. Driving the
# LOCAL surface exercises exactly that, far harder than a phone's 15s poll ever would.
#
# Passes only if the module process survives the whole run with no signal death.
set -uo pipefail

MOD=node_remote
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/tests/lib.sh"
LOGOSCORE=$(find /nix/store -maxdepth 4 -name logoscore -path '*/bin/*' 2>/dev/null | head -1)

SECS="${SOAK_SECS:-360}"          # 5+ min: every prior crash landed at 77-90s
CONC="${SOAK_CONC:-6}"            # concurrent readers

# Isolate from the machine's real state — see the note in pairing_stability.sh.
export XDG_DATA_HOME="${NR_TEST_HOME:-$HOME/.cache/node_remote-test-home}"
mkdir -p "$XDG_DATA_HOME"
export LOGOSCORE_CONFIG_DIR=$(mktemp -d)
MDIR=$(mktemp -d)
export NODE_REMOTE_TOKEN="tok-$(head -c12 /dev/urandom | od -An -tx1 | tr -d ' \n')"

HAMMER_PIDS=""
cleanup() {
  for p in $HAMMER_PIDS; do kill "$p" 2>/dev/null; done
  "$LOGOSCORE" call "$MOD" stopRemote >/dev/null 2>&1
  "$LOGOSCORE" stop >/dev/null 2>&1
  rm -rf "$MDIR" "$LOGOSCORE_CONFIG_DIR"
}
trap cleanup EXIT

SO="${SO:?set SO to the built node_remote_plugin.so}"
[ -f "$SO" ] || { echo "no plugin at $SO"; exit 2; }
stage_modules "$MDIR" "$SO"

"$LOGOSCORE" -D -m "$MDIR" > /tmp/node_remote-soak-daemon.log 2>&1 &
for _ in $(seq 1 20); do "$LOGOSCORE" status >/dev/null 2>&1 && break; sleep 1; done
"$LOGOSCORE" load-module "$MOD" >/dev/null 2>&1

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

echo "== node_remote BlockStore soak =="

# The module process — the thing that must still be alive at the end. Found via the daemon's
# child rather than a pattern match: `pgrep -f` on a path also matches this script's own
# command line and would report (or kill) the wrong thing.
sleep 2
MODPID=""
for p in $(pgrep -x .logos_host.elf 2>/dev/null; pgrep -x logos_host 2>/dev/null); do
  grep -qa "$MOD" "/proc/$p/cmdline" 2>/dev/null && MODPID=$p && break
done
echo "  module pid: ${MODPID:-not found (will fall back to method calls)}"

# Bring the HTTP surface up and learn its port.
call startRemote >/dev/null 2>&1
sleep 2
PORT=$(call getRemoteInfo | sed -n 's/.*"port":\([0-9]*\).*/\1/p')
echo "  http surface: 127.0.0.1:${PORT:-?}"
[ -n "$PORT" ] || { echo "  FAIL  no port — cannot soak"; exit 1; }

NODE_UP=$(call getNodeStatus | grep -c '"reachable":true' || true)
echo "  node reachable (blocks will stream): $([ "$NODE_UP" = "1" ] && echo yes || echo NO — weaker test)"

# Hammer the routes that READ the shared containers while the module's timers and the
# onNewBlock callback WRITE them. /v1/blocks is the suspected reader behind #3.
for i in $(seq 1 "$CONC"); do
  ( while :; do
      curl -s -o /dev/null -m 5 -H "Authorization: Bearer $NODE_REMOTE_TOKEN" \
           "http://127.0.0.1:$PORT/v1/blocks"
      curl -s -o /dev/null -m 5 -H "Authorization: Bearer $NODE_REMOTE_TOKEN" \
           "http://127.0.0.1:$PORT/v1/status"
    done ) &
  HAMMER_PIDS="$HAMMER_PIDS $!"
done
echo "  $CONC concurrent readers started; soaking ${SECS}s"

REQ0=0
t0=$SECONDS
while [ $((SECONDS - t0)) -lt "$SECS" ]; do
  sleep 30
  el=$((SECONDS - t0))
  if [ -n "$MODPID" ] && ! kill -0 "$MODPID" 2>/dev/null; then
    echo "  FAIL  module process DIED at +${el}s — the race is not fixed"
    tail -20 /tmp/node_remote-soak-daemon.log
    exit 1
  fi
  alive=$(call getRemoteInfo | grep -c '"running"' || true)
  echo "    +${el}s  process alive, module answering=$alive"
  [ "$alive" = "1" ] || { echo "  FAIL  module stopped answering at +${el}s"; exit 1; }
done

echo
if [ -n "$MODPID" ] && kill -0 "$MODPID" 2>/dev/null; then
  echo "  PASS  survived ${SECS}s under $CONC concurrent readers (crashes landed at 77-90s)"
  exit 0
fi
echo "  PASS  module still answering after ${SECS}s"
