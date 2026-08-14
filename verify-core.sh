#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build"
MODULES="$BUILD/module-check"
OUT="$BUILD/core-smoke.jar"
mkdir -p "$BUILD"
rm -rf "$MODULES"
mkdir -p "$MODULES"

# 先按真实 Gradle 模块边界编译关键协议层。
# 这一步专门抓跨模块 public property smart-cast、API 可见性和 classpath 依赖错误。
kotlinc $(find "$ROOT/core-model/src/main/kotlin" -name '*.kt' -print) \
  -d "$MODULES/core-model.jar"
kotlinc $(find "$ROOT/core-network/src/main/kotlin" -name '*.kt' -print) \
  -d "$MODULES/core-network.jar"
kotlinc $(find "$ROOT/gfn-identity/src/main/kotlin" -name '*.kt' -print) \
  -d "$MODULES/gfn-identity.jar"
kotlinc -classpath "$MODULES/core-model.jar" \
  $(find "$ROOT/gfn-session/src/main/kotlin" -name '*.kt' -print) \
  -d "$MODULES/gfn-session.jar"
kotlinc -classpath "$MODULES/core-model.jar:$MODULES/core-network.jar:$MODULES/gfn-identity.jar:$MODULES/gfn-session.jar" \
  $(find "$ROOT/gfn-cloudmatch/src/main/kotlin" -name '*.kt' -print) \
  -d "$MODULES/gfn-cloudmatch.jar"
echo "MODULE_BOUNDARY_COMPILE=PASS"

SOURCES=$(find \
  "$ROOT/core-model/src/main/kotlin" \
  "$ROOT/core-network/src/main/kotlin" \
  "$ROOT/gfn-auth/src/main/kotlin" \
  "$ROOT/gfn-account/src/main/kotlin" \
  "$ROOT/gfn-games/src/main/kotlin" \
  "$ROOT/gfn-identity/src/main/kotlin" \
  "$ROOT/gfn-session/src/main/kotlin" \
  "$ROOT/gfn-cloudmatch/src/main/kotlin" \
  "$ROOT/diagnostics/src/main/kotlin" \
  "$ROOT/stream-core/src/main/kotlin" \
  "$ROOT/protocol-cli/src/main/kotlin" \
  -name '*.kt' -print)

kotlinc -J-Dfile.encoding=UTF-8 $SOURCES -include-runtime -d "$OUT"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "$OUT"
