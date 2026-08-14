#!/usr/bin/env sh
set -eu


# v5.0.2 Android/WebRTC static ABI + manifest guards.
grep -Fq 'api("io.github.webrtc-sdk:android:144.7559.09")' stream-webrtc/build.gradle.kts || {
  echo 'ERROR: stream-webrtc 必须用 api 暴露 WebRTC，因为 GfnVideoSurfaceView 公共继承 SurfaceViewRenderer' >&2
  exit 1
}
grep -Fq 'android.permission.ACCESS_NETWORK_STATE' app/src/main/AndroidManifest.xml || {
  echo 'ERROR: 缺少 ACCESS_NETWORK_STATE，WebRTC NetworkMonitor 可能 JNI SIGABRT' >&2
  exit 1
}
grep -Fq 'android.permission.CHANGE_NETWORK_STATE' app/src/main/AndroidManifest.xml || {
  echo 'ERROR: 缺少 CHANGE_NETWORK_STATE' >&2
  exit 1
}
echo 'V5_WEBRTC_PUBLIC_ABI=PASS'
grep -Fq 'android.permission.ACCESS_NETWORK_STATE' stream-webrtc/src/main/AndroidManifest.xml || {
  echo 'ERROR: stream-webrtc library manifest 缺少 ACCESS_NETWORK_STATE' >&2
  exit 1
}
grep -Fq 'android.permission.CHANGE_NETWORK_STATE' stream-webrtc/src/main/AndroidManifest.xml || {
  echo 'ERROR: stream-webrtc library manifest 缺少 CHANGE_NETWORK_STATE' >&2
  exit 1
}
echo 'V5_WEBRTC_NETWORK_PERMISSIONS=PASS'
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
