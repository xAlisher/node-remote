#!/usr/bin/env bash
# Shared staging for node_remote's headless tests.
#
# stage_modules <module-dir> <plugin.so>
#
# Builds an isolated -m directory containing node_remote plus every module it depends
# on. Two things bite here and both are silent:
#
#  1. The dependency list is COMPILED INTO THE PLUGIN, not read from manifest.json.
#     Writing "dependencies":[] in a hand-made manifest does NOT make the daemon skip
#     them — it still refuses to load with "Cannot resolve dependencies for: node_remote".
#     So every dep must actually be present in the -m dir.
#
#  2. logoscore only resolves the `-dev` platform variants. The blockchain_module that
#     Basecamp installs carries `linux-amd64`, so copying it verbatim yields
#     "Module not found in known modules: blockchain_module", which then surfaces as an
#     unrelated-looking dependency error against node_remote.

stage_modules() {
  local MDIR="$1" SO="$2" MOD=node_remote

  mkdir -p "$MDIR/$MOD"
  cp "$SO" "$MDIR/$MOD/"
  cat > "$MDIR/$MOD/manifest.json" <<JSON
{"name":"$MOD","version":"0.1.0","type":"core","manifestVersion":"0.2.0",
 "main":{"linux-amd64":"${MOD}_plugin.so","linux-amd64-dev":"${MOD}_plugin.so","linux-x86_64-dev":"${MOD}_plugin.so"},
 "dependencies":["blockchain_module"]}
JSON
  echo linux-amd64-dev > "$MDIR/$MOD/variant"

  local BC="$HOME/.local/share/Logos/LogosBasecamp/modules/blockchain_module"
  if [ -d "$BC" ]; then
    cp -r "$BC" "$MDIR/"
    python3 - "$MDIR/blockchain_module/manifest.json" <<'PY'
import json,sys
p=sys.argv[1]; m=json.load(open(p))
main=m.get("main") or {}
so=main.get("linux-amd64") or next(iter(main.values()), None)
if so:
    main["linux-amd64-dev"]=so; main["linux-x86_64-dev"]=so
    m["main"]=main; json.dump(m,open(p,"w"),indent=2)
PY
    echo linux-amd64-dev > "$MDIR/blockchain_module/variant"
    return 0
  fi
  echo "  WARN: blockchain_module not installed — node_remote will fail to load" >&2
  return 1
}

# Unwrap the daemon's envelope: .result carries the module's JSON as an ESCAPED STRING
# (the double-JSON wrapper). Emitted compact so assertions can match "key":value.
lc_unwrap() {
  python3 -c '
import sys,json
raw=sys.stdin.read().strip()
try: env=json.loads(raw)
except Exception: print(raw); sys.exit()
r=env.get("result",env)
if isinstance(r,str):
    try: r=json.loads(r)
    except Exception: pass
print(json.dumps(r,separators=(",",":")) if not isinstance(r,str) else r)
'
}
