package dev.gfn.webrtc

import dev.gfn.signaling.GfnSignalingEndpoint
import dev.gfn.signaling.GfnSignalingMessageCodec
import dev.gfn.signaling.SignalingPeerPayload
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface GfnSignalingEvent {
    data object Connected : GfnSignalingEvent
    data class Offer(val sdp: String) : GfnSignalingEvent
    data class RemoteIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : GfnSignalingEvent
    data class Trace(val direction: Direction, val type: String, val epochMillis: Long) : GfnSignalingEvent
    data class Closed(val code: Int, val reason: String) : GfnSignalingEvent
    data class Failure(val message: String) : GfnSignalingEvent

    enum class Direction { RX, TX }
}

/**
 * GFN /nvst WebSocket transport。
 *
 * Android 先使用标准 TLS 校验，并强制 HTTP/1.1 WebSocket upgrade；不会复制 Apple 端的证书绕过。
 */
class GfnSignalingClient(
    private val signalingUrl: String,
    private val sessionId: String,
    private val resolution: String,
    private val listener: (GfnSignalingEvent) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build(),
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "gfn-signaling-heartbeat").apply { isDaemon = true }
    }
    private val lock = Any()
    private val peerName = "peer-${ThreadLocalRandom.current().nextLong(10_000_000_000L)}"

    private var socket: WebSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var ackCounter = 0
    private var localPeerId = 2
    private var remotePeerId = 1
    private var closed = false

    fun connect() {
        val url = GfnSignalingEndpoint.signInUrl(signalingUrl, sessionId, peerName)
        synchronized(lock) {
            if (socket != null) return
            closed = false
        }
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, SocketListener())
        synchronized(lock) { socket = ws }
    }

    fun sendAnswer(sdp: String, nvstSdp: String) {
        val ids = synchronized(lock) { Triple(localPeerId, remotePeerId, nextAckIdLocked()) }
        send(
            GfnSignalingMessageCodec.encodeAnswer(
                sdp = sdp,
                nvstSdp = nvstSdp,
                from = ids.first,
                to = ids.second,
                acknowledgementId = ids.third,
            ),
            "answer",
        )
    }

    fun sendIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        if (GfnSignalingMessageCodec.isTcpIceCandidate(candidate)) {
            trace(GfnSignalingEvent.Direction.TX, "ice/tcp-dropped")
            return
        }
        val ids = synchronized(lock) { Triple(localPeerId, remotePeerId, nextAckIdLocked()) }
        send(
            GfnSignalingMessageCodec.encodeIceCandidate(
                candidate = candidate,
                sdpMid = sdpMid,
                sdpMLineIndex = sdpMLineIndex,
                from = ids.first,
                to = ids.second,
                acknowledgementId = ids.third,
            ),
            "ice",
        )
    }

    fun disconnect() {
        val ws = synchronized(lock) {
            if (closed) return
            closed = true
            heartbeat?.cancel(true)
            heartbeat = null
            socket.also { socket = null }
        }
        ws?.close(1000, "client disconnect")
        scheduler.shutdownNow()
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (closed) {
                    webSocket.cancel()
                    return
                }
                socket = webSocket
            }
            val peerInfo = synchronized(lock) {
                GfnSignalingMessageCodec.encodePeerInfo(
                    acknowledgementId = nextAckIdLocked(),
                    localPeerId = localPeerId,
                    peerName = peerName,
                    resolution = resolution,
                )
            }
            send(peerInfo, "peer_info")
            synchronized(lock) {
                heartbeat?.cancel(true)
                heartbeat = scheduler.scheduleAtFixedRate(
                    { send(GfnSignalingMessageCodec.encodeHeartbeat(), "heartbeat") },
                    5,
                    5,
                    TimeUnit.SECONDS,
                )
            }
            listener(GfnSignalingEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val decoded = try {
                GfnSignalingMessageCodec.decode(text)
            } catch (error: Exception) {
                trace(GfnSignalingEvent.Direction.RX, "malformed")
                listener(GfnSignalingEvent.Failure("GFN signaling JSON 解析失败：${error.message}"))
                return
            }

            val type = when (decoded.payload) {
                is SignalingPeerPayload.Offer -> "offer"
                is SignalingPeerPayload.Ice -> "ice"
                is SignalingPeerPayload.Unknown -> "unknown"
                null -> when {
                    decoded.peerInfo != null -> "peer_info"
                    decoded.heartbeat -> "heartbeat"
                    decoded.acknowledgement != null -> "ack"
                    else -> "envelope"
                }
            }
            trace(GfnSignalingEvent.Direction.RX, type)

            decoded.peerInfo?.let { peer ->
                synchronized(lock) {
                    if (peer.name == peerName) localPeerId = peer.id
                    else if (peer.id != localPeerId) remotePeerId = peer.id
                }
            }
            decoded.peerFrom?.let { from ->
                synchronized(lock) {
                    if (from != localPeerId) remotePeerId = from
                }
            }

            decoded.acknowledgementId?.let { ackId ->
                val shouldAck = synchronized(lock) { decoded.peerInfo?.id != localPeerId }
                if (shouldAck) send(GfnSignalingMessageCodec.encodeAck(ackId), "ack")
            }
            if (decoded.heartbeat) {
                send(GfnSignalingMessageCodec.encodeHeartbeat(), "heartbeat")
            }

            when (val payload = decoded.payload) {
                is SignalingPeerPayload.Offer -> listener(GfnSignalingEvent.Offer(payload.sdp))
                is SignalingPeerPayload.Ice -> listener(
                    GfnSignalingEvent.RemoteIce(
                        payload.candidate,
                        payload.sdpMid,
                        payload.sdpMLineIndex,
                    ),
                )
                is SignalingPeerPayload.Unknown,
                null -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                heartbeat?.cancel(true)
                heartbeat = null
                socket = null
                closed = true
            }
            listener(GfnSignalingEvent.Closed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                heartbeat?.cancel(true)
                heartbeat = null
                socket = null
                closed = true
            }
            listener(GfnSignalingEvent.Failure("WebSocket 失败：${t.message ?: t::class.java.simpleName}"))
        }
    }

    private fun send(text: String, type: String) {
        val ws = synchronized(lock) { socket }
        if (ws?.send(text) == true) trace(GfnSignalingEvent.Direction.TX, type)
    }

    private fun trace(direction: GfnSignalingEvent.Direction, type: String) {
        listener(GfnSignalingEvent.Trace(direction, type, clockMillis()))
    }

    private fun nextAckIdLocked(): Int {
        ackCounter += 1
        return ackCounter
    }

}
