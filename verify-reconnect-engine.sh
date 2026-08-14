#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/reconnect-engine-check"
rm -rf "$BUILD"
mkdir -p "$BUILD"
cat > "$BUILD/AndroidContext.kt" <<'KT'
package android.content
open class Context
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

object GfnWebRtcRuntime { fun factory(context: Context): PeerConnectionFactory = PeerConnectionFactory() }

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
        fun onWindowFocusChanged(focused: Boolean)
        fun onPointerCaptureChanged(captured: Boolean)
    }
    var onFirstFrame: (() -> Unit)? = null
    var onResolutionChanged: ((Int, Int) -> Unit)? = null
    var inputListener: InputListener? = null
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
class RtpTransceiver { val receiver: RtpReceiver = RtpReceiver() }

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
  "$BUILD/AndroidContext.kt" "$BUILD/JsonStub.kt" "$BUILD/LocalStubs.kt" "$BUILD/WebRtcStubs.kt" \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/HttpTransport.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$ROOT/stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt" \
  "$ROOT/stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt" \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt" \
  -d "$BUILD/check.jar" > "$BUILD/compile.log" 2>&1

test -s "$BUILD/check.jar"
echo 'V521_WEBRTC_ENGINE_API_SHAPED_COMPILE=PASS'
