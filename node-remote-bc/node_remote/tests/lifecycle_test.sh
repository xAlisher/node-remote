#!/usr/bin/env bash
# node_remote — lifecycle honesty test. No Basecamp, no display, no phone, no real node.
#
# Proves the "honest states" contract (issues node-remote#1, #2, logos-blockchain-ui#40):
#   L1  intent=stopped + a stale FATAL log line  → status "Stopped", NO error
#       (the "deploy config on stop" bug: a clean stop must never inherit a stale error)
#   L2  intent=started + the SAME fatal log line  → status "Error" + the error text
#       (the error table is consulted ONLY when the node was meant to be up)
#   L3  a "found N stored blocks to replay" log    → status "Recovering", recovering.blocks=N
#       (block-count parity with 1-click getRecoveryStatus)
#   L4  intent=started, quiet log, node down       → status "Starting" (coming up, no error)
#
# The node API is forced UNREACHABLE via NODE_REMOTE_API_BASE=http://127.0.0.1:1 so every
# case exercises the unreachable branch of NodeProbe::statusJson(). Intent + config path are
# injected through the SAME QSettings the module reads (org "Logos", app "BlockchainUI"),
# isolated under a temp XDG_CONFIG_HOME so the real desktop config is never touched.
set -uo pipefail

MOD=node_remote
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SO="$HERE/result/lib/${MOD}_plugin.so"
source "$(dirname "$0")/lib.sh"
LOGOSCORE=$(find /nix/store -maxdepth 4 -name logoscore -path '*/bin/*' 2>/dev/null | head -1)

pass=0; fail=0
ok()  { echo "  PASS  $*"; pass=$((pass+1)); }
bad() { echo "  FAIL  $*"; fail=$((fail+1)); }

[ -f "$SO" ]        || { echo "no plugin at $SO — run: nix build .#packages.x86_64-linux.default"; exit 1; }
[ -n "$LOGOSCORE" ] || { echo "logoscore not found"; exit 1; }

# Isolated everything.
ROOT=$(mktemp -d)
export XDG_CONFIG_HOME="$ROOT/config"
export LOGOSCORE_CONFIG_DIR="$ROOT/lc"
export NODE_REMOTE_API_BASE="http://127.0.0.1:1"   # nothing listens → unreachable, instantly
mkdir -p "$XDG_CONFIG_HOME/Logos" "$ROOT/node/logs" "$LOGOSCORE_CONFIG_DIR"

CFG="$ROOT/node/user_config.yaml"
LOG="$ROOT/node/logs/node.log"
printf 'api:\n  backend:\n    listen_address: 127.0.0.1:1\n' > "$CFG"

MDIR=$(mktemp -d)
stage_modules "$MDIR" "$SO" || { echo "SKIP: blockchain_module not installed — cannot load node_remote"; exit 0; }

DAEMON_PID=""
start_daemon() {
  "$LOGOSCORE" -D -m "$MDIR" > "$ROOT/daemon.log" 2>&1 &
  DAEMON_PID=$!
  for _ in $(seq 1 20); do "$LOGOSCORE" status >/dev/null 2>&1 && break; sleep 1; done
  "$LOGOSCORE" load-module "$MOD" >/dev/null 2>&1
}
stop_daemon() { "$LOGOSCORE" stop >/dev/null 2>&1; sleep 1; }
cleanup() { stop_daemon; rm -rf "$ROOT" "$MDIR"; }
trap cleanup EXIT

# Write the shared QSettings conf (Qt INI: top-level keys live under [General]).
write_settings() {  # write_settings <intent>
  cat > "$XDG_CONFIG_HOME/Logos/BlockchainUI.conf" <<INI
[General]
userConfigPath=$CFG
nodeIntent=$1
INI
}

status() { "$LOGOSCORE" call "$MOD" getNodeStatus 2>&1 | lc_unwrap; }

# ── L1  intent=stopped + stale fatal log → Stopped, no error ────────────────────────────
printf 'ERROR node: missing field `funding_pk`, failed to parse config\n' > "$LOG"
write_settings stopped
start_daemon
S=$(status); echo "      L1: $S"
if echo "$S" | grep -q '"status":"Stopped"' && ! echo "$S" | grep -q '"error":'; then
  ok "intent=stopped + stale fatal log → Stopped, no error (the deploy-config-on-stop bug)"
else
  bad "expected status:Stopped and no error — got: $S"
fi

# ── L2  intent=started + same fatal log → Error ─────────────────────────────────────────
write_settings started
S=$(status); echo "      L2: $S"
if echo "$S" | grep -q '"status":"Error"' && echo "$S" | grep -q '"error":"'; then
  ok "intent=started + fatal log → Error + actionable text"
else
  bad "expected status:Error with error text — got: $S"
fi

# ── L3  recovery block count parity ─────────────────────────────────────────────────────
printf 'INFO node: found 4242 stored blocks to replay during chain recovery\n' > "$LOG"
S=$(status); echo "      L3: $S"
if echo "$S" | grep -q '"status":"Recovering"' && echo "$S" | grep -q '"blocks":4242'; then
  ok "replay log → Recovering with block count 4242 (parity with 1-click)"
else
  bad "expected status:Recovering with blocks:4242 — got: $S"
fi

# ── L4  intent=started, quiet log, down → Starting ──────────────────────────────────────
printf 'INFO node: starting up, opening listeners\n' > "$LOG"
S=$(status); echo "      L4: $S"
if echo "$S" | grep -q '"status":"Starting"' && ! echo "$S" | grep -q '"error":'; then
  ok "intent=started + quiet log → Starting, no error"
else
  bad "expected status:Starting and no error — got: $S"
fi

echo
echo "== summary: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
