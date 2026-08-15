#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/reconnect-engine-check"
rm -rf "$BUILD"
mkdir -p "$BUILD"
cat > "$BUILD/AndroidContext.kt" <<'KT'
package android.content
import android.hardware.input.InputManager
open class Context {
    open val applicationContext: Context get() = this
    open fun getSystemService(name: String): Any = InputManager()
    companion object { const val INPUT_SERVICE: String = "input" }
}
KT
cat > "$BUILD/AndroidOs.kt" <<'KT'
package android.os
class Looper { companion object { fun getMainLooper(): Looper = Looper() } }
class Handler(val looper: Looper)
KT
cat > "$BUILD/AndroidLog.kt" <<'KT'
package android.util
object Log { fun i(tag: String, msg: String): Int = 0 }
KT
cat > "$BUILD/AndroidInput.kt" <<'KT'
package android.hardware.input
import android.os.Handler
import android.view.InputDevice
class InputManager {
    interface InputDeviceListener {
        fun onInputDeviceAdded(deviceId: Int)
        fun onInputDeviceRemoved(deviceId: Int)
        fun onInputDeviceChanged(deviceId: Int)
    }
    val inputDeviceIds: IntArray get() = intArrayOf()
    fun getInputDevice(id: Int): InputDevice? = null
    fun registerInputDeviceListener(listener: InputDeviceListener, handler: Handler?) = Unit
    fun unregisterInputDeviceListener(listener: InputDeviceListener) = Unit
}
KT
cat > "$BUILD/AndroidView.kt" <<'KT'
package android.view
class InputDevice(
    val id: Int = 0,
    val name: String = "stub",
    val sources: Int = 0,
) {
    class MotionRange(val min: Float = -1f, val max: Float = 1f)
    fun getMotionRange(axis: Int): MotionRange? = null
    companion object {
        const val SOURCE_GAMEPAD = 0x00000401
        const val SOURCE_JOYSTICK = 0x01000010
    }
}
class KeyEvent(
    val deviceId: Int = 0,
    val device: InputDevice? = null,
    val source: Int = 0,
    val keyCode: Int = 0,
) {
    companion object {
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_BUTTON_A = 96
        const val KEYCODE_BUTTON_B = 97
        const val KEYCODE_BUTTON_X = 99
        const val KEYCODE_BUTTON_Y = 100
        const val KEYCODE_BUTTON_L1 = 102
        const val KEYCODE_BUTTON_R1 = 103
        const val KEYCODE_BUTTON_L2 = 104
        const val KEYCODE_BUTTON_R2 = 105
        const val KEYCODE_BUTTON_THUMBL = 106
        const val KEYCODE_BUTTON_THUMBR = 107
        const val KEYCODE_BUTTON_START = 108
        const val KEYCODE_BUTTON_SELECT = 109
        const val KEYCODE_BUTTON_MODE = 110
    }
}
class MotionEvent(
    val deviceId: Int = 0,
    val device: InputDevice? = null,
    val source: Int = 0,
    val actionMasked: Int = ACTION_MOVE,
) {
    fun getAxisValue(axis: Int): Float = 0f
    companion object {
        const val ACTION_MOVE = 2
        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_Z = 11
        const val AXIS_RX = 12
        const val AXIS_RY = 13
        const val AXIS_RZ = 14
        const val AXIS_HAT_X = 15
        const val AXIS_HAT_Y = 16
        const val AXIS_LTRIGGER = 17
        const val AXIS_RTRIGGER = 18
        const val AXIS_GAS = 22
        const val AXIS_BRAKE = 23
    }
}
KT
cat > "$BUILD/JsonStub.kt" <<'KT'
package org.json
class JSONObject(text: String) {
    fun has(name: String): Boolean = false
    fun optString(name: String): String = ""
}
KT
cat > "$BUILD/LocalStubs.kt" <<'KT'
package dev.gfn.webrtc

import android.content.Context
import dev.gfn.stream.InputDiagnostics
import java.nio.ByteBuffer
import org.webrtc.DataChannel
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities

data class GfnVideoCodecCapabilitySnapshot(
    val source: String,
    val index: Int,
    val preferredPayloadType: Int? = null,
    val name: String,
    val mimeType: String? = null,
    val clockRate: Int? = null,
    val parameters: Map<String, String> = emptyMap(),
) {
    val normalizedName: String get() = if (name.equals("HEVC", ignoreCase = true)) "H265" else name.uppercase()
}

enum class GfnHevcProfile(val sdpProfileId: String, val label: String) { Main("1","Main"), Main10("2","Main10") }
enum class GfnHevcTier(val sdpTierFlag: String) { Main("0"), High("1") }
enum class GfnHevcLevel(val label: String, val rank: Int, val sdpLevelId: String) {
    Level31("3.1",31,"93"), Level51("5.1",51,"153"), Level52("5.2",52,"156"), Level6("6",60,"180"), Level62("6.2",62,"186");
    companion object { fun fromSdpLevelId(v: String?): GfnHevcLevel? = entries.firstOrNull { it.sdpLevelId == v } }
}
data class GfnHevcDecoderCapability(
    val codecName: String,
    val profile: GfnHevcProfile,
    val tier: GfnHevcTier,
    val maxLevel: GfnHevcLevel,
    val hardwareAccelerated: Boolean,
    val supports1080p60: Boolean,
    val bitrateRangeKbps: IntRange?,
)
data class GfnHevcDecoderProbeResult(val candidates: List<GfnHevcDecoderCapability>, val selected: GfnHevcDecoderCapability?, val errors: List<String>, val selectedMain10: GfnHevcDecoderCapability? = null)
data class GfnHevcStreamSupport(val supported: Boolean, val sizeAndRateSupported: Boolean, val bitrateSupported: Boolean, val bitrateRangeKbps: IntRange?, val reason: String)

object GfnWebRtcRuntime {
    fun factory(context: Context): PeerConnectionFactory = PeerConnectionFactory()
    fun decoderCodecNames(context: Context): Set<String> = setOf("H264", "H265")
    private val hevcCap = GfnHevcDecoderCapability("c2.stub.hevc.decoder", GfnHevcProfile.Main, GfnHevcTier.High, GfnHevcLevel.Level51, true, true, 1..200000)
    private val hevcMain10Cap = GfnHevcDecoderCapability("c2.stub.hevc.decoder", GfnHevcProfile.Main10, GfnHevcTier.High, GfnHevcLevel.Level62, true, true, 1..200000)
    fun decoderCodecCapabilities(context: Context): List<GfnVideoCodecCapabilitySnapshot> = listOf(
        GfnVideoCodecCapabilitySnapshot("GfnHevcAwareVideoDecoderFactory", 0, name = "H264"),
        GfnVideoCodecCapabilitySnapshot("GfnHevcAwareVideoDecoderFactory", 1, name = "H265", parameters = mapOf("profile-id" to "1", "tier-flag" to "1", "level-id" to "153")),
        GfnVideoCodecCapabilitySnapshot("GfnHevcAwareVideoDecoderFactory", 2, name = "H265", parameters = mapOf("profile-id" to "2", "tier-flag" to "1", "level-id" to "186")),
    )
    fun receiverCodecCapabilities(context: Context): List<GfnVideoCodecCapabilitySnapshot> = listOf(
        GfnVideoCodecCapabilitySnapshot("PeerConnectionFactory.receiver", 0, 96, "H265", "video/H265", 90_000, mapOf("profile-id" to "1", "tier-flag" to "1", "level-id" to "153")),
        GfnVideoCodecCapabilitySnapshot("PeerConnectionFactory.receiver", 1, 98, "H265", "video/H265", 90_000, mapOf("profile-id" to "2", "tier-flag" to "1", "level-id" to "186")),
        GfnVideoCodecCapabilitySnapshot("PeerConnectionFactory.receiver", 2, 97, "H264", "video/H264", 90_000),
    )
    fun liveVideoReceiverCodecCapabilities(context: Context): List<RtpCapabilities.CodecCapability> = listOf(
        RtpCapabilities.CodecCapability(96, "H265", mapOf("profile-id" to "1", "tier-flag" to "1", "level-id" to "153"), "video/H265"),
        RtpCapabilities.CodecCapability(98, "H265", mapOf("profile-id" to "2", "tier-flag" to "1", "level-id" to "186"), "video/H265"),
        RtpCapabilities.CodecCapability(97, "H264", emptyMap(), "video/H264"),
    )
    fun hevcDecoderProbeResult(context: Context): GfnHevcDecoderProbeResult = GfnHevcDecoderProbeResult(listOf(hevcCap, hevcMain10Cap), hevcCap, emptyList(), hevcMain10Cap)
    fun hevcProductionCapability(context: Context): GfnHevcDecoderCapability = hevcCap
    fun hevcMain10ProductionCapability(context: Context): GfnHevcDecoderCapability = hevcMain10Cap
    fun hevcProductionCapability(context: Context, profile: GfnHevcProfile): GfnHevcDecoderCapability = if (profile == GfnHevcProfile.Main10) hevcMain10Cap else hevcCap
    fun hevcAdvertisementReason(context: Context): String = "stub Main capability"
    fun hevcMain10AdvertisementReason(context: Context): String = "stub Main10 capability"
    fun hevcAdvertisementReason(context: Context, profile: GfnHevcProfile): String = if (profile == GfnHevcProfile.Main10) "stub Main10 capability" else "stub Main capability"
    fun hevcStreamSupport(context: Context, profile: GfnHevcProfile = GfnHevcProfile.Main, width: Int, height: Int, fps: Int, maxBitrateKbps: Int): GfnHevcStreamSupport =
        GfnHevcStreamSupport(true, true, true, 1..200000, "stub ${profile.label} stream-safe")
}
data class GfnAudioRouteSnapshot(val likelyMaxChannels: Int? = 2, val summary: String = "stub")
object GfnAndroidAudioRouteProbe { fun detect(context: Context): GfnAudioRouteSnapshot = GfnAudioRouteSnapshot() }

sealed interface GfnSignalingEvent {
    data object Connected : GfnSignalingEvent
    data class Offer(val sdp: String) : GfnSignalingEvent
    data class RemoteIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : GfnSignalingEvent
    data class Trace(val direction: Direction, val type: String, val epochMillis: Long) : GfnSignalingEvent
    data class Closed(val code: Int, val reason: String) : GfnSignalingEvent
    data class Failure(val message: String) : GfnSignalingEvent
    enum class Direction { RX, TX }
}
class GfnSignalingClient(
    signalingUrl: String,
    sessionId: String,
    resolution: String,
    listener: (GfnSignalingEvent) -> Unit,
) {
    fun connect() = Unit
    fun disconnect() = Unit
    fun sendAnswer(sdp: String, nvstSdp: String) = Unit
    fun sendIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) = Unit
}

object GfnInputForensics {
    data class KeyTrace(val keyCode: Int = 0)
    data class KeyboardTx(val scanCode: Int = 0)
    fun logKeyboardTxBeforeSend(tx: KeyboardTx, buffer: ByteBuffer, channelState: String, bufferedAmount: Long, binary: Boolean): String = ""
    fun logKeyboardTxAfterSend(prefix: String, accepted: Boolean, bufferedAmount: Long) = Unit
    fun logDataChannel(connectionGeneration: Long, state: String, protocolReady: Boolean, note: String) = Unit
    fun logHandshake(connectionGeneration: Long, bytes: ByteArray, version: Int?) = Unit
}

class GfnKeyboardMouseInputController(
    connectionGeneration: Long,
    packetSink: PacketSink,
    onDiagnostics: (InputDiagnostics) -> Unit,
) {
    interface PacketSink {
        fun sendBinary(packet: ByteArray): Boolean
        fun sendKeyboard(tx: GfnInputForensics.KeyboardTx, packet: ByteArray): Boolean
        fun isOpen(): Boolean
        fun bufferedAmount(): Long
    }
    fun onActivityResumed() = Unit
    fun onActivityPaused() = Unit
    fun onActivityDestroy() = Unit
    fun onOverlayChanged(open: Boolean) = Unit
    fun releaseForFullscreenExit() = Unit
    fun onKey(down: Boolean, trace: GfnInputForensics.KeyTrace): Boolean = true
    fun onMouseMove(dx: Float, dy: Float) = Unit
    fun onMouseButton(down: Boolean, button: Int): Boolean = true
    fun onMouseWheel(verticalAxis: Float) = Unit
    fun onWindowFocusChanged(focused: Boolean) = Unit
    fun onPointerCaptureChanged(captured: Boolean) = Unit
    fun onStreamConnected(connected: Boolean) = Unit
    fun onDataChannelState(open: Boolean) = Unit
    fun onProtocolReady(version: Int) = Unit
    fun prepareForDisconnect(reason: dev.gfn.input.InputReleaseReason, callback: () -> Unit) = callback()
    fun shutdownWithoutTransport() = Unit
}

open class GfnVideoSurfaceView {
    interface InputListener {
        fun onKey(down: Boolean, trace: GfnInputForensics.KeyTrace): Boolean
        fun onMouseMove(dx: Float, dy: Float)
        fun onMouseButton(down: Boolean, button: Int): Boolean
        fun onMouseWheel(verticalAxis: Float)
        fun onGamepadKey(down: Boolean, event: android.view.KeyEvent): Boolean
        fun onGamepadMotion(event: android.view.MotionEvent): Boolean
        fun onWindowFocusChanged(focused: Boolean)
        fun onPointerCaptureChanged(captured: Boolean)
    }
    var onFirstFrame: (() -> Unit)? = null
    var onResolutionChanged: ((Int, Int) -> Unit)? = null
    var onFrameActivity: (() -> Unit)? = null
    var inputListener: InputListener? = null
    fun armRenderedFrameWitness(onRendered: () -> Unit) = Unit
}
KT
cat > "$BUILD/WebRtcStubs.kt" <<'KT'
package org.webrtc

open class MediaStreamTrack {
    enum class MediaType { MEDIA_TYPE_VIDEO, MEDIA_TYPE_AUDIO, MEDIA_TYPE_DATA }
}
open class VideoTrack : MediaStreamTrack() { fun addSink(sink: Any) = Unit; fun removeSink(sink: Any) = Unit }
open class AudioTrack : MediaStreamTrack() { fun setEnabled(enabled: Boolean): Boolean = true }
class MediaStream
class MediaConstraints {
    class KeyValuePair(val key: String, val value: String)
    val mandatory = mutableListOf<KeyValuePair>()
}
class IceCandidate(val sdpMid: String?, val sdpMLineIndex: Int, val sdp: String)

interface SdpObserver {
    fun onCreateSuccess(description: SessionDescription)
    fun onSetSuccess()
    fun onCreateFailure(error: String)
    fun onSetFailure(error: String)
}
class SessionDescription(val type: Type, val description: String) { enum class Type { OFFER, ANSWER } }

open class DataChannel {
    enum class State { CONNECTING, OPEN, CLOSING, CLOSED }
    class Init { var ordered: Boolean = false; var maxRetransmits: Int? = null; var maxRetransmitTimeMs: Int? = null }
    class Buffer(val data: java.nio.ByteBuffer, val binary: Boolean)
    interface Observer { fun onBufferedAmountChange(previousAmount: Long); fun onStateChange(); fun onMessage(buffer: Buffer) }
    fun state(): State = State.OPEN
    fun send(buffer: Buffer): Boolean = true
    fun bufferedAmount(): Long = 0L
    fun registerObserver(observer: Observer) = Unit
    fun close() = Unit
    fun dispose() = Unit
    fun label(): String = ""
}

open class RtpReceiver {
    interface Observer { fun onFirstPacketReceived(mediaType: MediaStreamTrack.MediaType) }
    fun id(): String = "id"
    fun SetObserver(observer: Observer) = Unit
    fun track(): MediaStreamTrack? = null
}
class RtcException(message: String) : RuntimeException(message)
class RtcError(private val value: RtcException? = null) {
    val isError: Boolean get() = value != null
    val isSuccess: Boolean get() = value == null
    fun error(): RtcException? = value
}
class RtpCapabilities(val codecs: List<CodecCapability> = emptyList()) {
    class CodecCapability(
        var preferredPayloadType: Int = 0,
        var name: String = "",
        var parameters: Map<String, String>? = emptyMap(),
        var mimeType: String? = null,
        var clockRate: Int? = 90_000,
    )
}
class RtpTransceiver {
    val receiver: RtpReceiver = RtpReceiver()
    val mediaType: MediaStreamTrack.MediaType = MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
    val mid: String? = "video"
    fun setCodecPreferences(codecs: List<RtpCapabilities.CodecCapability>): RtcError = RtcError()
}

open class PeerConnection {
    enum class SdpSemantics { UNIFIED_PLAN }
    enum class ContinualGatheringPolicy { GATHER_CONTINUALLY }
    enum class BundlePolicy { MAXBUNDLE }
    enum class RtcpMuxPolicy { REQUIRE }
    enum class TcpCandidatePolicy { DISABLED }
    enum class SignalingState { STABLE }
    enum class IceConnectionState { NEW, CHECKING, CONNECTED, COMPLETED, FAILED, DISCONNECTED, CLOSED }
    enum class IceGatheringState { NEW, GATHERING, COMPLETE }
    enum class PeerConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }
    class IceServer { companion object { fun builder(urls: List<String>) = Builder() }; class Builder { fun setUsername(v: String)=this; fun setPassword(v: String)=this; fun createIceServer()=IceServer() } }
    class RTCConfiguration(val iceServers: List<IceServer>) {
        var sdpSemantics: SdpSemantics = SdpSemantics.UNIFIED_PLAN
        var continualGatheringPolicy: ContinualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY
        var bundlePolicy: BundlePolicy = BundlePolicy.MAXBUNDLE
        var rtcpMuxPolicy: RtcpMuxPolicy = RtcpMuxPolicy.REQUIRE
        var tcpCandidatePolicy: TcpCandidatePolicy = TcpCandidatePolicy.DISABLED
        var audioJitterBufferFastAccelerate: Boolean = false
        var audioJitterBufferMaxPackets: Int = 0
    }
    interface Observer {
        fun onSignalingChange(newState: SignalingState)
        fun onIceConnectionChange(newState: IceConnectionState)
        fun onConnectionChange(newState: PeerConnectionState)
        fun onIceConnectionReceivingChange(receiving: Boolean)
        fun onIceGatheringChange(newState: IceGatheringState)
        fun onIceCandidate(candidate: IceCandidate)
        fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>)
        fun onAddStream(stream: MediaStream)
        fun onRemoveStream(stream: MediaStream)
        fun onDataChannel(dataChannel: DataChannel)
        fun onRenegotiationNeeded()
        fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>)
        fun onTrack(transceiver: RtpTransceiver)
    }
    val transceivers: List<RtpTransceiver> = listOf(RtpTransceiver())
    fun createDataChannel(label: String, init: DataChannel.Init): DataChannel? = DataChannel()
    fun setRemoteDescription(observer: SdpObserver, description: SessionDescription) = observer.onSetSuccess()
    fun createAnswer(observer: SdpObserver, constraints: MediaConstraints) = Unit
    fun setLocalDescription(observer: SdpObserver, description: SessionDescription) = observer.onSetSuccess()
    fun addIceCandidate(candidate: IceCandidate): Boolean = true
    fun close() = Unit
}

open class PeerConnectionFactory {
    fun createPeerConnection(config: PeerConnection.RTCConfiguration, observer: PeerConnection.Observer): PeerConnection? = PeerConnection()
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$BUILD/AndroidContext.kt" "$BUILD/AndroidOs.kt" "$BUILD/AndroidLog.kt" "$BUILD/AndroidInput.kt" "$BUILD/AndroidView.kt" \
  "$BUILD/JsonStub.kt" "$BUILD/LocalStubs.kt" "$BUILD/WebRtcStubs.kt" \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/HttpTransport.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$ROOT/stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt" \
  "$ROOT/stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt" \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnGamepadInputController.kt" \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoCodecNegotiationPolicy.kt" \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevcNegotiationCompat.kt" \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoFrameLiveness.kt" \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt" \
  -d "$BUILD/check.jar" > "$BUILD/compile.log" 2>&1

test -s "$BUILD/check.jar"
echo 'V521_WEBRTC_ENGINE_API_SHAPED_COMPILE=PASS'
echo 'V530_WEBRTC_ENGINE_GAMEPAD_API_SHAPED_COMPILE=PASS'
echo 'V604_WEBRTC_ENGINE_HEVC_PRODUCTION_API_SHAPED_COMPILE=PASS'
echo 'V610_WEBRTC_ENGINE_MAIN10_API_SHAPED_COMPILE=PASS'
