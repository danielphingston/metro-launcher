#!/usr/bin/env bash
# Renders every Compose @Preview of the app (app/src/debug/.../previews) with Google's Android CLI,
# without a device, then checks the results with tools/preview-check.py: every preview must render,
# and text that runs past the screen's edge is reported. Images and layout trees go to <out dir>.
#
# Usage: tools/preview-check.sh [out dir] [-- extra Gradle arguments]
#   Locally: tools/preview-check.sh build/previews -- --init-script tools/google-mirror.init.gradle
# Needs the Android CLI (`android`, or set ANDROID_CLI) and ANDROID_HOME. Install it with
#   curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
set -euo pipefail
OUT="${1:-build/previews}"
shift || true
[ "${1:-}" = "--" ] && shift
CLI="${ANDROID_CLI:-android}"
# --no-metrics: no usage data is sent to Google from here or from CI.
run() { "$CLI" --no-metrics "$@"; }

rm -rf "$OUT"
mkdir -p "$OUT"
run preview list app --format=json -- "$@" > "$OUT/previews.json"
FQNS=$(python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
items = next(v for v in d.values() if isinstance(v, list)) if isinstance(d, dict) else d
print(" ".join(sorted({"--fqn=" + i["methodFqn"] for i in items})))
' "$OUT/previews.json")
if [ -z "$FQNS" ]; then
  echo "::error::The Android CLI found no previews."
  exit 1
fi
STATUS=0
# shellcheck disable=SC2086 # one --fqn per preview function
run preview render app --component-tree -o "$OUT/render" $FQNS -- "$@" > "$OUT/render.log" 2>&1 || STATUS=$?
python3 tools/preview-check.py "$OUT/previews.json" "$OUT/render" "$OUT/render.log" "$STATUS"
