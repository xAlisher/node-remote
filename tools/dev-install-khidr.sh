#!/usr/bin/env bash
# Replace node_remote + node_remote_ui on khidr, restarting Basecamp cleanly.
#
# ORDER MATTERS, and getting it wrong has now cost a running node twice:
#
#   1. STOP THE NODE.      SIGTERM to Basecamp is NOT a node shutdown — it tears the module
#                          host out from under a running node, which never gets asked to
#                          stop. A small WAL afterwards proves RocksDB flushed; it does not
#                          prove the node stopped properly, and it does not bring it back.
#   2. Stop Basecamp.      SIGTERM only, then WAIT for every host to exit on its own.
#   3. Install, relaunch.
#
# If the node is running and we CANNOT stop it, this script REFUSES rather than killing it.
# Pass FORCE=1 to override, deliberately.
#
# Kills by PID, never by -f pattern: a pattern matching "logos_host" also matches this
# script's own ssh command line.
set -u
B="$HOME/.local/share/Logos/LogosBasecamp"
TOKEN_FILE="$HOME/.local/share/.logos_host.elf/node_remote/device_token"
GRACE=90
FORCE="${FORCE:-0}"

node_up() { curl -s -m 3 http://127.0.0.1:8080/cryptarchia/info >/dev/null 2>&1; }

logos_pids() {
  pgrep -u "$USER" -f 'LogosBasecamp\.elf|logos_host\.elf|ui-host\.elf|LogosBasecamp-Desktop' 2>/dev/null
}

echo "== 1. node state =="
if ! node_up; then
  echo "  node is not running — nothing to stop"
else
  echo "  node IS running; asking node_remote to stop it before touching Basecamp"
  stopped=0
  if [ -r "$TOKEN_FILE" ]; then
    T=$(cat "$TOKEN_FILE")
    # node_remote's own control route. Only reachable while its HTTP surface is up, which
    # requires the pane to have started the remote at least once this session.
    for p in $(ss -ltn 2>/dev/null | grep -oE '127\.0\.0\.1:[0-9]+' | cut -d: -f2); do
      code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST \
                  -H "Authorization: Bearer $T" "http://127.0.0.1:$p/v1/stop" 2>/dev/null)
      if [ "$code" = "200" ]; then echo "  stop accepted on port $p"; stopped=1; break; fi
    done
  else
    echo "  no device_token — cannot reach node_remote's control route"
  fi

  if [ "$stopped" = "1" ]; then
    echo "  waiting for the node's API to go quiet (up to 60s)"
    for i in $(seq 1 60); do
      node_up || { echo "  node stopped after ${i}s"; break; }
      sleep 1
    done
  fi

  if node_up; then
    if [ "$FORCE" != "1" ]; then
      echo
      echo "REFUSING: the node is still running and I could not stop it cleanly."
      echo "Stop it from the 1-click Blockchain Node UI, then re-run this."
      echo "(FORCE=1 overrides — it WILL kill the running node.)"
      exit 2
    fi
    echo "  FORCE=1 — proceeding with the node still up (it will be killed)"
  fi
fi

echo "== 2. stopping Basecamp (SIGTERM) =="
for p in $(logos_pids); do kill -TERM "$p" 2>/dev/null; done
for i in $(seq 1 $GRACE); do
  [ "$(logos_pids | wc -l)" -eq 0 ] && { echo "  all exited cleanly after ${i}s"; break; }
  sleep 1
done
if [ "$(logos_pids | wc -l)" -ne 0 ]; then
  echo "  WARNING: still running after ${GRACE}s — escalating to SIGKILL"
  for p in $(logos_pids); do kill -9 "$p" 2>/dev/null; done
  sleep 2
fi
pgrep -u "$USER" -x tor >/dev/null && { pkill -u "$USER" -x tor; sleep 1; }

echo "== 3. installing =="
chmod -R u+w "$B/modules/node_remote" "$B/plugins/node_remote_ui" 2>/dev/null
rm -rf "$B/modules/node_remote" "$B/plugins/node_remote_ui"
mkdir -p "$B/modules/node_remote" "$B/plugins/node_remote_ui"
cp -a /tmp/nr-stage/node_remote/.    "$B/modules/node_remote/"
cp -a /tmp/nr-stage/node_remote_ui/. "$B/plugins/node_remote_ui/"
md5sum "$B/modules/node_remote/node_remote_plugin.so"

echo "== 4. relaunching =="
cd "$HOME"
XDG_RUNTIME_DIR=/run/user/1000 WAYLAND_DISPLAY=wayland-0 \
  nohup "$HOME/logos-basecamp-current.AppImage" > /tmp/bc-nr.log 2>&1 &
for i in $(seq 1 20); do
  grep -q "Logos Core started successfully" /tmp/bc-nr.log 2>/dev/null && { echo "  Basecamp up after ${i}s"; break; }
  sleep 1
done
echo
echo "NOTE: the node does NOT restart itself. Start it from the 1-click UI."
