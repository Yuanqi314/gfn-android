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

# v5.1 input architecture guards.
grep -Fq 'include(' settings.gradle.kts
grep -Fq '":stream-input"' settings.gradle.kts || { echo 'ERROR: 缺少 stream-input 模块' >&2; exit 1; }
grep -Fq 'api("io.github.webrtc-sdk:android:144.7559.09")' stream-webrtc/build.gradle.kts || { echo 'ERROR: WebRTC public ABI 必须继续使用 api(...)' >&2; exit 1; }
grep -Fq 'InputEpochGate' stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt || { echo 'ERROR: v5.1 缺少 input epoch gate' >&2; exit 1; }
grep -Fq 'activeModifierMask' stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt || { echo 'ERROR: releaseAll 普通键 UP 必须保留当前 modifier mask' >&2; exit 1; }
grep -Fq 'InputReleaseReason' stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt || { echo 'ERROR: v5.1 缺少 releaseAll reason 模型' >&2; exit 1; }
echo 'V51_INPUT_ARCHITECTURE=PASS'
# v5.1 safety/state-machine guards.
grep -Fq 'fun releaseAll(reason: InputReleaseReason)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: 缺少统一 releaseAll(reason) 入口' >&2; exit 1; }
grep -Fq 'DISCONNECT_DRAIN_TIMEOUT_MILLIS' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: 缺少主动断开 queue drain' >&2; exit 1; }
grep -Fq 'neutralizeUncertainRemoteStateBeforeReady' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: protocolReady 前必须 neutralize uncertain remote state' >&2; exit 1; }
grep -Fq 'registerInputDataChannel' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: input_channel_v1 未接 server handshake' >&2; exit 1; }
grep -Fq 'GfnInputHandshake.parseProtocolVersion' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: input protocol handshake parser 未接入' >&2; exit 1; }
if grep -Fq '!buffer.binary' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt; then echo 'ERROR: server handshake 不应被 binary flag 硬过滤' >&2; exit 1; fi
grep -Fq 'getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoSurfaceView.kt || { echo 'ERROR: 相对鼠标必须消费 batched historical samples' >&2; exit 1; }
grep -Fq 'FullscreenStreamScreen' app/src/main/java/dev/gfn/android/ui/GfnAndroidApp.kt || { echo 'ERROR: v5.1 缺少全屏串流页面' >&2; exit 1; }
echo 'V51_RELEASE_ALL_GUARDS=PASS'
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
# 先编译 v5/v5.1 变更链，让即使旧 gfn-cloudmatch 在受限容器中耗时，输入模块边界也能先得到证据。
compile_module diagnostics "$MODULES/core-model.jar:$MODULES/gfn-identity.jar" "$ROOT/diagnostics/src/main/kotlin"
compile_module stream-core "$MODULES/core-model.jar" "$ROOT/stream-core/src/main/kotlin"
compile_module stream-input "" "$ROOT/stream-input/src/main/kotlin"
compile_module stream-signaling "$MODULES/core-network.jar" "$ROOT/stream-signaling/src/main/kotlin"
compile_module gfn-cloudmatch "$MODULES/core-model.jar:$MODULES/core-network.jar:$MODULES/gfn-identity.jar:$MODULES/gfn-session.jar" "$ROOT/gfn-cloudmatch/src/main/kotlin"

CP="$MODULES/core-model.jar:$MODULES/core-network.jar:$MODULES/gfn-auth.jar:$MODULES/gfn-account.jar:$MODULES/gfn-games.jar:$MODULES/gfn-identity.jar:$MODULES/gfn-session.jar:$MODULES/gfn-cloudmatch.jar:$MODULES/diagnostics.jar:$MODULES/stream-core.jar:$MODULES/stream-input.jar:$MODULES/stream-signaling.jar"
kotlinc -J-Dfile.encoding=UTF-8 -classpath "$CP" $(find "$ROOT/protocol-cli/src/main/kotlin" -name '*.kt' -print) -d "$MODULES/protocol-cli.jar"
echo "MODULE_BOUNDARY_COMPILE=PASS"
kotlin -J-Dfile.encoding=UTF-8 -classpath "$CP:$MODULES/protocol-cli.jar" dev.gfn.protocol.MainKt
