#!/usr/bin/env bash
# node_remote — the REAL closed loop, run on a remote host (khidr).
#
# Everything before this proved the control PATH. This proves the control EFFECT:
# a POST over HTTP actually stops a running blockchain node, and starts it again.
#
# It needs the node to be owned by OUR logoscore instance, because blockchain_module
# can only stop a node its own instance started. So it closes Basecamp first and
# restores it at the end.
#
#   L0  baseline: Basecamp is running a node on :8080
#   L1  close Basecamp   → :8080 goes down
#   L2  node_remote.startNode() → :8080 comes back, state Online
#   L3  POST /v1/stop    → :8080 goes DOWN   ← the actual E2 claim
#   L4  startNode()      → :8080 comes back up again
#   L5  restore: Basecamp relaunched
#
# Run from the dev box:  ssh khidr bash -s < tests/lifecycle_khidr.sh
set -uo pipefail

pass=0; fail=0
ok()  { echo "  PASS  $*"; pass=$((pass+1)); }
bad() { echo "  FAIL  $*"; fail=$((fail+1)); }
node_up() { curl -s -o /dev/null -w '%{http_code}' --max-time 4 http://127.0.0.1:8080/cryptarchia/info; }
wait_for() { # wait_for <up|down> <secs>
  local want="$1" n="$2" i
  for ((i=0;i<n;i++)); do
    local c; c=$(node_up)
    [ "$want" = up ]   && [ "$c" = "200" ] && return 0
    [ "$want" = down ] && [ "$c" != "200" ] && return 0
    sleep 2
  done
  return 1
}

MDIR=/tmp/nrtest2
LOGOSCORE=/tmp/logoscore
export LOGOSCORE_CONFIG_DIR=$(mktemp -d)
export NODE_REMOTE_TOKEN="tok-$(head -c12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
BC_APPIMAGE="$HOME/logos-basecamp-current.AppImage"

restore() {
  echo "== L5  restore Basecamp"
  "$LOGOSCORE" call node_remote stopRemote >/dev/null 2>&1
  "$LOGOSCORE" stop >/dev/null 2>&1
  sleep 2
  if ! pgrep -f "logos-basecamp-current.AppImage" >/dev/null 2>&1; then
    # Basecamp is a GUI app and an ssh session has no display; khidr is Wayland.
    XDG_RUNTIME_DIR=/run/user/1000 WAYLAND_DISPLAY=wayland-0 \
      setsid nohup "$BC_APPIMAGE" > /tmp/bc-relaunch.log 2>&1 < /dev/null &
    sleep 25
  fi
  if pgrep -f "logos-basecamp-current.AppImage" >/dev/null 2>&1; then
    echo "  PASS  Basecamp relaunched (pid $(pgrep -f 'logos-basecamp-current.AppImage' | head -1))"
    echo "  NOTE  the NODE is not running: it was owned by this test's logoscore daemon and"
    echo "        died with it. Basecamp does not auto-start user-installed core modules, so"
    echo "        it needs one Start click in the Blockchain tab to return to its prior state."
  else
    echo "  FAIL  BASECAMP NOT RESTORED — relaunch by hand: $BC_APPIMAGE"
  fi
  rm -rf "$LOGOSCORE_CONFIG_DIR"
}
trap restore EXIT

echo "== L0  baseline"
B=$(node_up); echo "  :8080 → $B"
[ "$B" = "200" ] && ok "a node is running under Basecamp" || { echo "  no node running; aborting"; exit 1; }
CFG=$(ls -t ~/.local/share/Logos/LogosBasecamp/module_data/blockchain_module/*/user_config.yaml 2>/dev/null | head -1)
echo "  config: $CFG"

echo "== L1  close Basecamp"
for p in $(pgrep -f "logos-basecamp-current.AppImage"); do kill "$p" 2>/dev/null; done
sleep 3
for p in $(pgrep -f "LogosBasecamp.elf"); do kill "$p" 2>/dev/null; done
for p in $(pgrep -f "logos_host.elf"); do kill "$p" 2>/dev/null; done
if wait_for down 25; then ok ":8080 is down after closing Basecamp"
else bad ":8080 still answering — Basecamp did not release the node"; fi

echo "== L2  node_remote starts the node"
"$LOGOSCORE" -D -m "$MDIR" > /tmp/nr-life.log 2>&1 &
for i in $(seq 1 25); do "$LOGOSCORE" status >/dev/null 2>&1 && break; sleep 1; done
"$LOGOSCORE" load-module node_remote >/dev/null 2>&1
R=$("$LOGOSCORE" call node_remote startNode "$CFG" "" 2>&1 | head -c 300); echo "  startNode → $R"
if wait_for up 90; then ok "node started by node_remote is serving :8080"
else bad "node did not come up within 180s"; fi

echo "== L3  POST /v1/stop actually stops it"
# GUARD: "down" is only meaningful if the node is UP right now. Without this the test
# passes vacuously whenever L2 failed — which it did, and it reported a green PASS for
# stopping a node that had never started.
if [ "$(node_up)" != "200" ]; then
  bad "SKIPPED-AS-FAIL: node is not up, so 'it went down' would prove nothing"
else
ST=$("$LOGOSCORE" call node_remote startRemote 2>&1)
PORT=$(echo "$ST" | grep -oE '\\"port\\":[0-9]+|"port":[0-9]+' | grep -oE '[0-9]+' | head -1)
echo "  http surface port: ${PORT:-none}"
if [ -n "${PORT:-}" ]; then
  H=$(curl -s --max-time 60 -X POST -H "Authorization: Bearer $NODE_REMOTE_TOKEN" \
        "http://127.0.0.1:$PORT/v1/stop"); echo "  POST /v1/stop → $H"
  if wait_for down 40; then ok ":8080 is DOWN — an HTTP POST stopped a live node"
  else bad "node still serving after POST /v1/stop"; fi
else bad "no http port; cannot test the HTTP path"; fi
fi

echo "== L4  start it again"
R2=$("$LOGOSCORE" call node_remote startNode "$CFG" "" 2>&1 | head -c 200); echo "  startNode → $R2"
if wait_for up 90; then ok "node is back up"
else bad "node did not restart"; fi

echo
echo "== summary: $pass passed, $fail failed"
