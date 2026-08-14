#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build"
MODULES="$BUILD/module-check"
rm -rf "$MODULES"
mkdir -p "$MODULES"

compile_module() {
  name=$1
  echo "COMPILE_START=$name"
  cp=$2
  src=$3
  if [ -n "$cp" ]; then
    kotlinc -J-Dfile.encoding=UTF-8 -classpath "$cp" $(find "$src" -name '*.kt' -print) -d "$MODULES/$name.jar"
  else
    kotlinc -J-Dfile.encoding=UTF-8 $(find "$src" -name '*.kt' -print) -d "$MODULES/$name.jar"
  fi
  echo "COMPILE_PASS=$name"
}

compile_module core-model "" "$ROOT/core-model/src/main/kotlin"
compile_module core-network "" "$ROOT/core-network/src/main/kotlin"
compile_module gfn-identity "" "$ROOT/gfn-identity/src/main/kotlin"
compile_module gfn-auth "$MODULES/core-network.jar" "$ROOT/gfn-auth/src/main/kotlin"
compile_module gfn-account "$MODULES/core-model.jar:$MODULES/core-network.jar:$MODULES/gfn-identity.jar" "$ROOT/gfn-account/src/main/kotlin"
compile_module gfn-games "$MODULES/core-model.jar:$MODULES/core-network.jar:$MODULES/gfn-identity.jar" "$ROOT/gfn-games/src/main/kotlin"
compile_module gfn-session "$MODULES/core-model.jar" "$ROOT/gfn-session/src/main/kotlin"
compile_module gfn-cloudmatch "$MODULES/core-model.jar:$MODULES/core-network.jar:$MODULES/gfn-identity.jar:$MODULES/gfn-session.jar" "$ROOT/gfn-cloudmatch/src/main/kotlin"
compile_module diagnostics "$MODULES/core-model.jar:$MODULES/gfn-identity.jar" "$ROOT/diagnostics/src/main/kotlin"
compile_module stream-core "$MODULES/core-model.jar" "$ROOT/stream-core/src/main/kotlin"
compile_module stream-signaling "$MODULES/core-network.jar" "$ROOT/stream-signaling/src/main/kotlin"

CP="$MODULES/core-model.jar:$MODULES/core-network.jar:$MODULES/gfn-auth.jar:$MODULES/gfn-account.jar:$MODULES/gfn-games.jar:$MODULES/gfn-identity.jar:$MODULES/gfn-session.jar:$MODULES/gfn-cloudmatch.jar:$MODULES/diagnostics.jar:$MODULES/stream-core.jar:$MODULES/stream-signaling.jar"
kotlinc -J-Dfile.encoding=UTF-8 -classpath "$CP" $(find "$ROOT/protocol-cli/src/main/kotlin" -name '*.kt' -print) -d "$MODULES/protocol-cli.jar"
echo "MODULE_BOUNDARY_COMPILE=PASS"
kotlin -J-Dfile.encoding=UTF-8 -classpath "$CP:$MODULES/protocol-cli.jar" dev.gfn.protocol.MainKt
