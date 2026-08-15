#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"
export TERM="${TERM:-xterm}"


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

# v5.1.1 true-device fixes and lifecycle/session-end guards.
grep -Fq 'pendingWheel += verticalAxis * 3.0' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: Android true-device wheel sign fix missing' >&2; exit 1; }
if grep -Fq 'pendingWheel += -verticalAxis' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt; then echo 'ERROR: wheel sign regressed to pre-v5.1.1 inversion' >&2; exit 1; fi
grep -Fq 'track.setEnabled(true)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: remote AudioTrack still disabled' >&2; exit 1; }
if grep -Fq 'is AudioTrack -> track.setEnabled(false)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt; then echo 'ERROR: remote AudioTrack regressed to video-only mute' >&2; exit 1; fi
grep -Fq 'label != "control_channel"' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: control_channel registration missing' >&2; exit 1; }
grep -Fq 'json.has("exitMessage")' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: exitMessage parser missing' >&2; exit 1; }
grep -Fq 'controlDataChannel !== channel || serverEnded' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: control_channel identity/idempotence guard missing' >&2; exit 1; }
grep -Fq 'orchestrator.detachOwnedSession()' app/src/main/java/dev/gfn/android/session/GfnSessionController.kt || { echo 'ERROR: server-end must detach owned Session' >&2; exit 1; }
grep -Fq 'error.code == 404 || error.code == 410' app/src/main/java/dev/gfn/android/session/GfnSessionController.kt || { echo 'ERROR: conservative terminal reconcile guard missing' >&2; exit 1; }
grep -Fq 'androidx.lifecycle:lifecycle-viewmodel:2.11.0' app/build.gradle.kts || { echo 'ERROR: explicit lifecycle-viewmodel dependency missing' >&2; exit 1; }
grep -Fq 'class GfnAppRuntimeViewModel' app/src/main/java/dev/gfn/android/GfnAppRuntimeViewModel.kt || { echo 'ERROR: retained runtime ViewModel missing' >&2; exit 1; }
grep -Fq 'ViewModelProvider(this)' app/src/main/java/dev/gfn/android/MainActivity.kt || { echo 'ERROR: stream/session runtime is not retained across Activity recreation' >&2; exit 1; }
grep -Fq 'GfnAndroidApp(runtime)' app/src/main/java/dev/gfn/android/MainActivity.kt || { echo 'ERROR: retained runtime not passed into Compose root' >&2; exit 1; }
grep -Fq 'rememberSaveable { mutableStateOf(false) }' app/src/main/java/dev/gfn/android/ui/GfnAndroidApp.kt || { echo 'ERROR: fullscreen route not saveable across recreation' >&2; exit 1; }
grep -Fq 'SCREEN_ORIENTATION_SENSOR_LANDSCAPE' app/src/main/java/dev/gfn/android/ui/FullscreenStreamScreen.kt || { echo 'ERROR: fullscreen landscape policy missing' >&2; exit 1; }
grep -Fq '.aspectRatio(videoAspectRatio)' app/src/main/java/dev/gfn/android/ui/FullscreenStreamScreen.kt || { echo 'ERROR: fullscreen aspect-fit bounds policy missing' >&2; exit 1; }
grep -Fq 'if (videoOutput !== output) return' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: fullscreen surface switch lacks identity-safe unbind' >&2; exit 1; }
grep -Fq 'CredentialRestore:FAILED' app/src/main/java/dev/gfn/android/auth/AndroidKeystoreTokenStore.kt || { echo 'ERROR: auth restore reason diagnostics missing' >&2; exit 1; }
grep -Fq 'CredentialCleanup:reason=RESTORE_FAILED' app/src/main/java/dev/gfn/android/auth/AndroidKeystoreTokenStore.kt || { echo 'ERROR: auth cleanup reason diagnostics missing' >&2; exit 1; }
echo 'V511_TRUE_DEVICE_FIX_GUARDS=PASS'

# v5.1.2 audio routing + keyboard modifier truth guards.
grep -Fq 'JavaAudioDeviceModule.builder' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt || { echo 'ERROR: custom game/media AudioDeviceModule missing' >&2; exit 1; }
grep -Fq '.setUsage(AudioAttributes.USAGE_GAME)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt || { echo 'ERROR: WebRTC playout must use USAGE_GAME' >&2; exit 1; }
grep -Fq '.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt || { echo 'ERROR: WebRTC playout content type must be MUSIC' >&2; exit 1; }
grep -Fq '.setAudioDeviceModule(adm)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt || { echo 'ERROR: custom AudioDeviceModule not attached to PeerConnectionFactory' >&2; exit 1; }
grep -Fq 'adm.release()' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt || { echo 'ERROR: caller-owned AudioDeviceModule native ref is not released after PeerConnectionFactory creation' >&2; exit 1; }
grep -Fq 'volumeControlStream = AudioManager.STREAM_MUSIC' app/src/main/java/dev/gfn/android/MainActivity.kt || { echo 'ERROR: Activity volume buttons not bound to media stream' >&2; exit 1; }
grep -Fq 'currentPhysicalModifierMask()' stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt || { echo 'ERROR: tracked modifier truth missing' >&2; exit 1; }
grep -Fq 'val androidReportedModifiers = AndroidKeyboardMapper.modifiers(trace.metaState)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: raw Android modifier diagnostics missing' >&2; exit 1; }
grep -Fq 'handleKeyDown(trace, eventEpoch, key, trackedModifiersForEvent)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: keyboard DOWN still trusts Android metaState instead of tracked modifier state' >&2; exit 1; }
grep -Fq 'modifierMismatchCount' stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt || { echo 'ERROR: modifier mismatch diagnostics missing' >&2; exit 1; }
echo 'V512_AUDIO_KEYBOARD_GUARDS=PASS'

# v5.1.3+ generic Input Forensics guards. Diagnostics must observe, never transform keyboard semantics.
grep -Fq 'buildConfigField("boolean", "INPUT_FORENSICS_ENABLED", "true")' app/build.gradle.kts || { echo 'ERROR: debug Input Forensics switch missing' >&2; exit 1; }
grep -Fq 'BuildConfig.DEBUG && BuildConfig.INPUT_FORENSICS_ENABLED' app/src/main/java/dev/gfn/android/MainActivity.kt || { echo 'ERROR: Input Forensics must be debug-gated' >&2; exit 1; }
grep -Fq 'override fun dispatchKeyEvent(event: KeyEvent)' app/src/main/java/dev/gfn/android/MainActivity.kt || { echo 'ERROR: Activity dispatch PRE/POST instrumentation missing' >&2; exit 1; }
grep -Fq 'GfnKeyDispatch' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnInputForensics.kt || { echo 'ERROR: GfnKeyDispatch log missing' >&2; exit 1; }
grep -Fq 'GfnInputKey' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnInputForensics.kt || { echo 'ERROR: GfnInputKey log missing' >&2; exit 1; }
grep -Fq 'GfnInputHandshake' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnInputForensics.kt || { echo 'ERROR: GfnInputHandshake log missing' >&2; exit 1; }
grep -Fq 'GfnInputTx' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnInputForensics.kt || { echo 'ERROR: GfnInputTx log missing' >&2; exit 1; }
grep -Fq 'asReadOnlyBuffer()' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnInputForensics.kt || { echo 'ERROR: final ByteBuffer dump must use read-only duplicate/view' >&2; exit 1; }
grep -Fq 'val finalBuffer = ByteBuffer.wrap(packet)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: Tx diagnostics are not attached to final DataChannel ByteBuffer' >&2; exit 1; }
grep -Fq 'DataChannel.Buffer(finalBuffer, true)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: actual final ByteBuffer is not sent as binary DataChannel payload' >&2; exit 1; }
grep -Fq 'GfnInputForensics.logHandshake(eventGeneration, bytes, version)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: raw input handshake diagnostics missing' >&2; exit 1; }
grep -Fq 'DataChannel.Init().apply { ordered = true }' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: input_channel_v1 DataChannel configuration changed' >&2; exit 1; }
grep -Fq 'fun releaseAll(reason: InputReleaseReason)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: releaseAll architecture changed' >&2; exit 1; }
grep -Fq 'pendingWheel += verticalAxis * 3.0' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: mouse/wheel logic changed' >&2; exit 1; }
echo 'V513_INPUT_FORENSICS_GUARDS=PASS'

# v5.2 Session snapshot foundation: persistent settings resolve once, then CREATE/CLAIM/WebRTC share one profile.
grep -Fq 'const val DEFAULT = "en-US"' app/src/main/java/dev/gfn/android/settings/GfnKeyboardLayoutCatalog.kt || { echo 'ERROR: keyboard layout default must be en-US' >&2; exit 1; }
grep -Fq 'const val KEY_KEYBOARD_LAYOUT = "keyboardLayoutSelection"' app/src/main/java/dev/gfn/android/settings/AndroidStreamSettingsStore.kt || { echo 'ERROR: v5.1.8 keyboard preference migration key changed' >&2; exit 1; }
grep -Fq 'streamSettingsController.resolveForNewSession' app/src/main/java/dev/gfn/android/session/GfnSessionController.kt || { echo 'ERROR: Session create does not resolve persistent settings first' >&2; exit 1; }
grep -Fq 'val launchProfile: ResolvedLaunchProfile? = null' app/src/main/java/dev/gfn/android/session/AndroidSessionPersistence.kt || { echo 'ERROR: persisted Session does not carry ResolvedLaunchProfile' >&2; exit 1; }
grep -Fq 'launchProfile = active.profile' app/src/main/java/dev/gfn/android/session/GfnSessionController.kt || { echo 'ERROR: resolved profile not persisted with Session' >&2; exit 1; }
grep -Fq 'val launchProfile = record.launchProfile' app/src/main/java/dev/gfn/android/session/GfnSessionController.kt || { echo 'ERROR: Claim does not restore frozen launch profile' >&2; exit 1; }
grep -Fq 'engine.connect(session, profile.streamConfig)' app/src/main/java/dev/gfn/android/stream/GfnStreamingController.kt || { echo 'ERROR: WebRTC does not consume frozen StreamConfig snapshot' >&2; exit 1; }
grep -Fq 'val owned = orchestrator.currentOwnedSession()' app/src/main/java/dev/gfn/android/session/GfnSessionController.kt || { echo 'ERROR: persisted legacy cleanup does not distinguish owned Session from restored record' >&2; exit 1; }
grep -Fq 'val hasOwnedSession = orchestrator.currentOwnedSession() != null' app/src/main/java/dev/gfn/android/session/GfnSessionController.kt || { echo 'ERROR: local resume-record cleanup can clear an active launch profile' >&2; exit 1; }
grep -Fq 'StreamCapabilityProfiles.V60_ANDROID_WEBRTC.rejectionReason(config)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: WebRTC engine capability validation drifted from resolver' >&2; exit 1; }
CLOUDMATCH_LAYOUT_QUERIES=$(grep -Fc 'keyboardLayout=${enc(request.keyboardLayout)}' gfn-cloudmatch/src/main/kotlin/dev/gfn/cloudmatch/CloudMatchProtocol.kt)
[ "$CLOUDMATCH_LAYOUT_QUERIES" -ge 2 ] || { echo 'ERROR: create/claim CloudMatch keyboardLayout queries are not both present' >&2; exit 1; }
echo 'V520_STREAM_SETTINGS_SNAPSHOT_GUARDS=PASS'
./verify-stream-settings.sh

# v6.0 HEVC Main / SDR8 regression plus v6.0.2 tier-only A/B negotiation compatibility.
./verify-hevc.sh
./verify-hevc-compat.sh

# v5.4 audio: native stereo output plus explicit experimental multiopus/downmix probe.
./verify-audio.sh

# v5.2.1 same-session reconnect: reclaim existing Session, rebuild transport, keep frozen profile.
grep -Fq 'fun recoverForStreamReconnect(' app/src/main/java/dev/gfn/android/session/GfnSessionController.kt || { echo 'ERROR: same-session reconnect session handler missing' >&2; exit 1; }
grep -Fq 'orchestrator.claimSession(request, sessionAttempt)' app/src/main/java/dev/gfn/android/session/GfnSessionController.kt || { echo 'ERROR: reconnect does not use same-session claim' >&2; exit 1; }
grep -Fq 'fun prepareForReconnect(onDrained: () -> Unit)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: reconnect transport drain missing' >&2; exit 1; }
grep -Fq 'onTransportNeedsReconnect(sessionId, source, immediate)' app/src/main/java/dev/gfn/android/stream/GfnStreamingController.kt || { echo 'ERROR: reconnect trigger is not wired' >&2; exit 1; }
grep -Fq 'transportRecoverySink = sessionController::recoverForStreamReconnect' app/src/main/java/dev/gfn/android/GfnAppRuntimeViewModel.kt || { echo 'ERROR: session reconnect sink not wired into stream controller' >&2; exit 1; }
grep -Fq 'videoOutput?.let(::installVideoOutputCallbacksLocked)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: reconnect does not restore Surface input listener' >&2; exit 1; }
echo 'V521_RECONNECT_GUARDS=PASS'
./verify-reconnect.sh

# v5.3 single-controller gamepad: independent type-12 controller on the reliable input channel.
grep -Fq 'const val GAMEPAD: Int = 12' stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt || { echo 'ERROR: v5.3 type-12 gamepad encoder missing' >&2; exit 1; }
grep -Fq 'class GfnGamepadInputController' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnGamepadInputController.kt || { echo 'ERROR: v5.3 gamepad controller missing' >&2; exit 1; }
grep -Fq 'onGamepadMotion' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoSurfaceView.kt || { echo 'ERROR: Android gamepad motion route missing' >&2; exit 1; }
grep -Fq 'gamepad?.onProtocolReady(version)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt || { echo 'ERROR: gamepad protocol handshake route missing' >&2; exit 1; }
grep -Fq 'a=ri.enablePartiallyReliableTransferGamepad:0' stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt || { echo 'ERROR: v5.3 reliable-only gamepad assumption changed' >&2; exit 1; }
echo 'V530_GAMEPAD_GUARDS=PASS'
./verify-gamepad.sh

# v5.1.9 stable keyboard baseline: Set-1 is the sole production path; all C2/C3 probes are removed.
test ! -e stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardWireMode.kt || { echo 'ERROR: experiment-only GfnKeyboardWireMode.kt still exists' >&2; exit 1; }
if grep -RqsE 'LOCK_KEYS_SYNC|lockKeysSync|GfnKeyboardWireMode|GfnKeyboardWirePolicy|setKeyboardWireMode|GfnCapsCompat|GfnLockState|C2_ISO|C3_OPENNOW' stream-input/src/main stream-webrtc/src/main app/src/main; then
  echo 'ERROR: C2/C3 keyboard experiment residue remains in production source' >&2
  exit 1
fi
grep -Fq 'KeyEvent.KEYCODE_CAPS_LOCK to k(0x14, 0x3A)' stream-webrtc/src/main/java/dev/gfn/webrtc/AndroidKeyboardMapper.kt || { echo 'ERROR: stable CapsLock VK/Set-1 mapping missing' >&2; exit 1; }
grep -Fq 'val packet = encoder.keyboard(true, key, modifiers)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: stable key-down encoder path missing' >&2; exit 1; }
grep -Fq 'val packet = encoder.keyboard(false, key, encodedModifiers)' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt || { echo 'ERROR: stable key-up encoder path missing' >&2; exit 1; }
grep -Fq 'scan=${hex16(tx.scanCode)}' stream-webrtc/src/main/java/dev/gfn/webrtc/GfnInputForensics.kt || { echo 'ERROR: generic final scan diagnostics missing' >&2; exit 1; }
if grep -Fq 'Keyboard Wire:' app/src/main/java/dev/gfn/android/ui/FullscreenStreamScreen.kt; then echo 'ERROR: Wire A/B experiment UI remains' >&2; exit 1; fi
echo 'V519_KEYBOARD_STABLE_GUARDS=PASS'
./verify-keyboard-stable.sh

BUILD="$ROOT/build"
MODULES="$BUILD/module-check"
rm -rf "$MODULES"
mkdir -p "$MODULES"

compile_module() {
  module_name=$1
  classpath_value=$2
  source_dir=$3
  output_jar="$MODULES/$module_name.jar"
  echo "COMPILE_START=$module_name"
  if [ -n "$classpath_value" ]; then
    kotlinc -J-Dfile.encoding=UTF-8 -classpath "$classpath_value" $(find "$source_dir" -name '*.kt' -print) -d "$output_jar"
  else
    kotlinc -J-Dfile.encoding=UTF-8 $(find "$source_dir" -name '*.kt' -print) -d "$output_jar"
  fi
  if [ ! -s "$output_jar" ]; then
    echo "ERROR: compiler returned without output jar: $module_name" >&2
    exit 1
  fi
  echo "COMPILE_PASS=$module_name"
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
