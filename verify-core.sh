#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT="$ROOT/build/core-smoke.jar"
mkdir -p "$ROOT/build"
SOURCES=$(find \
  "$ROOT/core-model/src/main/kotlin" \
  "$ROOT/core-network/src/main/kotlin" \
  "$ROOT/gfn-auth/src/main/kotlin" \
  "$ROOT/gfn-identity/src/main/kotlin" \
  "$ROOT/gfn-session/src/main/kotlin" \
  "$ROOT/gfn-cloudmatch/src/main/kotlin" \
  "$ROOT/diagnostics/src/main/kotlin" \
  "$ROOT/stream-core/src/main/kotlin" \
  "$ROOT/protocol-cli/src/main/kotlin" \
  -name '*.kt' -print)
kotlinc -J-Dfile.encoding=UTF-8 $SOURCES -include-runtime -d "$OUT"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "$OUT"
