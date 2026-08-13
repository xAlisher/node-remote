#!/usr/bin/env bash
# Deploy node_remote + node_remote_ui + logos_node_1click, stopping the node cleanly first.
# Extends tools/dev-install-khidr.sh with the 1-click plugin, which is hot-swappable on this
# rig (it lives in plugins/, not inside the AppImage).
set -u
B="$HOME/.local/share/Logos/LogosBasecamp"
TOKEN_FILE="$HOME/.local/share/.logos_host.elf/node_remote/device_token"
GRACE=90
FORCE="${FORCE:-0}"

node_up() { curl -s -m 3 http://127.0.0.1:8080/cryptarchia/info >/dev/null 2>&1; }
logos_pids() { pgrep -u "$USER" -f 'LogosBasecamp\.elf|logos_host\.elf|ui-host\.elf|LogosBasecamp-Desktop' 2>/dev/null; }

echo "== 1. node =="
if ! node_up; then echo "  not running"; else
  echo "  RUNNING — asking node_remote to stop it"
  stopped=0
  if [ -r "$TOKEN_FILE" ]; then
    T=$(cat "$TOKEN_FILE")
    for p in $(ss -ltn 2>/dev/null | grep -oE '127\.0\.0\.1:[0-9]+' | cut -d: -f2); do
      # Read the BODY, not just the HTTP code. /v1/stop answers 200 with {"ok":false} when
      # it cannot stop the node — e.g. code "not_owned", a node started from the desktop UI,
      # which blockchain_module will only stop from the client that started it. Trusting the
      # 200 alone made this script wait 60s for a stop that was never attempted.
      body=$(curl -s -m 8 -X POST -H "Authorization: Bearer $T" "http://127.0.0.1:$p/v1/stop" 2>/dev/null)
      case "$body" in
        *'"ok":true'*) echo "  stop accepted on $p: $body"; stopped=1; break ;;
        *'"ok":false'*) echo "  stop REFUSED by the module: $body"; break ;;
      esac
    done
  fi
  [ "$stopped" = "1" ] && for i in $(seq 1 60); do node_up || { echo "  stopped after ${i}s"; break; }; sleep 1; done
  if node_up; then
    [ "$FORCE" != "1" ] && { echo; echo "REFUSING: node still running. Stop it in the 1-click UI."; exit 2; }
    echo "  FORCE=1 — proceeding over a running node"
  fi
fi

echo "== 2. stopping Basecamp (SIGTERM) =="
for p in $(logos_pids); do kill -TERM "$p" 2>/dev/null; done
for i in $(seq 1 $GRACE); do
  [ "$(logos_pids | wc -l)" -eq 0 ] && { echo "  clean after ${i}s"; break; }; sleep 1
done
if [ "$(logos_pids | wc -l)" -ne 0 ]; then
  echo "  WARNING: SIGKILL after ${GRACE}s"; for p in $(logos_pids); do kill -9 "$p" 2>/dev/null; done; sleep 2
fi
pgrep -u "$USER" -x tor >/dev/null && { pkill -u "$USER" -x tor; sleep 1; }

echo "== 3. installing =="
install_one() {  # $1 = staged name, $2 = destination dir
  # BACK UP FIRST. This function rm -rf'd a working plugin with no copy kept — if the new
  # build had been bad (wrong SDK/ABI is easy to produce from a compat branch) the only
  # working one would have been gone, with Basecamp unable to start and nothing to restore.
  if [ -d "$2" ]; then
    rm -rf "$2.bak"; cp -a "$2" "$2.bak"
    echo "  backup: $2.bak"
  fi
  chmod -R u+w "$2" 2>/dev/null
  rm -rf "$2"; mkdir -p "$2"
  cp -a "/tmp/nr-stage/$1/." "$2/"
  chmod -R u+w "$2"
}
install_one node_remote      "$B/modules/node_remote"
install_one node_remote_ui   "$B/plugins/node_remote_ui"
install_one logos_node_1click "$B/plugins/logos_node_1click"
md5sum "$B/modules/node_remote/node_remote_plugin.so" | cut -c1-12
md5sum "$B/plugins/logos_node_1click/logos_node_1click_plugin.so" | cut -c1-12
echo -n "  1click carries nodeIntent: "
strings -el "$B/plugins/logos_node_1click/logos_node_1click_plugin.so" | grep -c nodeIntent

echo "== 4. relaunching =="
cd "$HOME"
XDG_RUNTIME_DIR=/run/user/1000 WAYLAND_DISPLAY=wayland-0 \
  nohup "$HOME/logos-basecamp-current.AppImage" > /tmp/bc-nr.log 2>&1 &
for i in $(seq 1 20); do
  grep -q "Logos Core started successfully" /tmp/bc-nr.log 2>/dev/null && { echo "  up after ${i}s"; break; }; sleep 1
done
echo
echo "NOTE: the node does NOT restart itself. Start it from the 1-click UI."
