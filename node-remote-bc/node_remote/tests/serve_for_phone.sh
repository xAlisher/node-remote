#!/usr/bin/env bash
# Bring up node_remote, publish the onion, pair a device, and print everything the
# phone needs. Leaves the daemon RUNNING so the phone can actually connect.
#
#   bash tests/serve_for_phone.sh [label]
#   …then: adb shell am start -n co.logos.noderemote/.MainActivity
#          paste the URI + token into the app.
#
# Ctrl-C (or `kill <pid>`) to tear it down — it prints the pid.
set -uo pipefail

MOD=node_remote
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/tests/lib.sh"
LOGOSCORE=$(find /nix/store -maxdepth 4 -name logoscore -path '*/bin/*' 2>/dev/null | head -1)
LABEL="${1:-pixel10}"

export LOGOSCORE_CONFIG_DIR="${NR_CFG:-$HOME/.cache/node_remote-serve/cfg}"
MDIR="${NR_MDIR:-$HOME/.cache/node_remote-serve/modules}"
mkdir -p "$LOGOSCORE_CONFIG_DIR" "$MDIR"
export NODE_REMOTE_TOKEN="${NODE_REMOTE_TOKEN:-tok-$(head -c12 /dev/urandom | od -An -tx1 | tr -d ' \n')}"

# Re-stage EVERY run and refuse to reuse a daemon that is already up. The module dir is
# persistent, so a rebuilt plugin was silently ignored and new methods came back
# METHOD_FAILED with no hint that the loaded .so was stale.
if "$LOGOSCORE" status >/dev/null 2>&1; then
  echo "stopping the daemon already running in this config dir (it would keep the old plugin)…"
  "$LOGOSCORE" call "$MOD" stopRemote >/dev/null 2>&1
  "$LOGOSCORE" stop >/dev/null 2>&1
  sleep 3
fi
rm -rf "$MDIR"; mkdir -p "$MDIR"
stage_modules "$MDIR" "$HERE/result/lib/${MOD}_plugin.so" >/dev/null
echo "staged plugin md5: $(md5sum "$MDIR/$MOD/${MOD}_plugin.so" | cut -c1-12)"

"$LOGOSCORE" -D -m "$MDIR" > /tmp/nr-serve.log 2>&1 &
for _ in $(seq 1 25); do "$LOGOSCORE" status >/dev/null 2>&1 && break; sleep 1; done
"$LOGOSCORE" load-module "$MOD" >/dev/null 2>&1
call() { "$LOGOSCORE" call "$MOD" "$@" 2>&1 | lc_unwrap; }

call startRemote >/dev/null
echo "publishing onion (this can take 30-90s)…"
for _ in $(seq 1 60); do
  I=$(call getRemoteInfo)
  [ "$(echo "$I" | grep -oE '"ready":(true|false)' | cut -d: -f2)" = "true" ] && break
  sleep 3
done
ONION=$(call getRemoteInfo | grep -oE '[a-z2-7]{56}\.onion')
[ -n "$ONION" ] || { echo "onion never published — see /tmp/nr-serve.log"; exit 1; }

PR=$(call beginPairing "$LABEL")
URI=$(echo "$PR" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("uri",""))')
SAS=$(echo "$PR" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("sas",""))')

# beginPairing restarts tor to apply the new authorization; wait for the re-publish or
# the phone will connect to a descriptor that is not up yet.
echo "re-publishing after pairing…"
for _ in $(seq 1 60); do
  I=$(call getRemoteInfo)
  [ "$(echo "$I" | grep -oE '"ready":(true|false)' | cut -d: -f2)" = "true" ] && break
  sleep 3
done

cat <<OUT

================ PASTE INTO THE PHONE ================
URI:
$URI

TOKEN:
$NODE_REMOTE_TOKEN

Confirm this SAS matches what the phone shows:  $SAS
onion: $ONION
======================================================
daemon log: /tmp/nr-serve.log
stop with:  $LOGOSCORE stop      (LOGOSCORE_CONFIG_DIR=$LOGOSCORE_CONFIG_DIR)
OUT
