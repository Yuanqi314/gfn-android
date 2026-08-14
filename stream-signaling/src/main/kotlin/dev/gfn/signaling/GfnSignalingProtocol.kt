package dev.gfn.signaling

import dev.gfn.network.Json
import dev.gfn.network.Json.int
import dev.gfn.network.Json.obj
import dev.gfn.network.Json.string
import java.net.URI
import java.time.Instant

/**
 * GFN /nvst WebSocket 的协议模型。
 *
 * 这里只描述 JSON envelope，不包含具体 WebSocket 或 WebRTC 实现，因此可以在纯 JVM fixture 中验证。
 */

object GfnSignalingEndpoint {
    fun sessionSubprotocol(sessionId: String): String {
        require(sessionId.isNotBlank()) { "Session ID 不能为空" }
        return "x-nv-sessionid.$sessionId"
    }

    fun signInUrl(signalingUrl: String, sessionId: String, peerName: String): String {
        val base = URI(signalingUrl)
        require(base.scheme == "wss" || base.scheme == "ws") { "Signaling URL 必须是 ws/wss" }
        require(!base.host.isNullOrBlank()) { "Signaling URL 缺少 host" }
        val originalPath = base.path?.takeIf { it.isNotBlank() } ?: "/"
        val normalized = if (originalPath.endsWith('/')) originalPath else "$originalPath/"
        val path = "${normalized}sign_in"
        val query = "peer_id=$peerName&version=2&peer_role=1&pairing_id=$sessionId"
        return URI(base.scheme, null, base.host, base.port, path, query, null).toASCIIString()
    }
}

data class SignalingPeerInfo(
    val id: Int,
    val name: String?,
)

sealed interface SignalingPeerPayload {
    data class Offer(val sdp: String) : SignalingPeerPayload
    data class Ice(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?,
    ) : SignalingPeerPayload
    data class Unknown(val type: String?, val keys: List<String>) : SignalingPeerPayload
}

data class DecodedSignalingMessage(
    val peerInfo: SignalingPeerInfo?,
    val acknowledgementId: Int?,
    val acknowledgement: Int?,
    val heartbeat: Boolean,
    val peerFrom: Int?,
    val payload: SignalingPeerPayload?,
)

sealed class SignalingMessageException(message: String) : Exception(message) {
    class MalformedJson(message: String) : SignalingMessageException(message)
    class MissingField(field: String) : SignalingMessageException("Signaling 缺少字段：$field")
    class InvalidField(field: String) : SignalingMessageException("Signaling 字段类型错误：$field")
}

object GfnSignalingMessageCodec {
    fun decode(text: String): DecodedSignalingMessage {
        val root = try {
            Json.parseObject(text)
        } catch (error: Exception) {
            throw SignalingMessageException.MalformedJson(error.message ?: "无法解析 JSON")
        }

        val peerInfo = root.obj("peer_info")?.let { raw ->
            val id = raw.int("id") ?: throw SignalingMessageException.MissingField("peer_info.id")
            SignalingPeerInfo(id = id, name = raw.string("name"))
        }

        var peerFrom: Int? = null
        val payload = root.obj("peer_msg")?.let { peerMessage ->
            peerFrom = peerMessage.int("from")
            val messageText = peerMessage.string("msg")
                ?: throw SignalingMessageException.MissingField("peer_msg.msg")
            val payloadObject = try {
                Json.parseObject(messageText)
            } catch (error: Exception) {
                throw SignalingMessageException.InvalidField("peer_msg.msg")
            }

            when {
                payloadObject.string("type") == "offer" -> {
                    val sdp = payloadObject.string("sdp")
                        ?: throw SignalingMessageException.MissingField("peer_msg.msg.sdp")
                    SignalingPeerPayload.Offer(sdp)
                }
                payloadObject.string("candidate") != null -> SignalingPeerPayload.Ice(
                    candidate = payloadObject.string("candidate")!!,
                    sdpMid = payloadObject.string("sdpMid"),
                    sdpMLineIndex = payloadObject.int("sdpMLineIndex"),
                )
                else -> SignalingPeerPayload.Unknown(
                    type = payloadObject.string("type"),
                    keys = payloadObject.keys.sorted(),
                )
            }
        }

        return DecodedSignalingMessage(
            peerInfo = peerInfo,
            acknowledgementId = root.int("ackid"),
            acknowledgement = root.int("ack"),
            heartbeat = root.containsKey("hb"),
            peerFrom = peerFrom,
            payload = payload,
        )
    }

    fun encodePeerInfo(
        acknowledgementId: Int,
        localPeerId: Int,
        peerName: String,
        resolution: String,
    ): String = Json.stringify(
        linkedMapOf(
            "ackid" to acknowledgementId,
            "peer_info" to linkedMapOf(
                "browser" to "Chrome",
                "browserVersion" to "131",
                "connected" to true,
                "id" to localPeerId,
                "name" to peerName,
                "peerRole" to 1,
                "resolution" to resolution,
                "version" to 2,
            ),
        ),
    )

    fun encodeAck(acknowledgementId: Int): String =
        Json.stringify(mapOf("ack" to acknowledgementId))

    fun encodeHeartbeat(): String = Json.stringify(mapOf("hb" to 1))

    fun encodeAnswer(
        sdp: String,
        nvstSdp: String?,
        from: Int,
        to: Int,
        acknowledgementId: Int,
    ): String {
        val payload = linkedMapOf<String, Any?>(
            "type" to "answer",
            "sdp" to sdp,
        )
        if (nvstSdp != null) payload["nvstSdp"] = nvstSdp
        return encodePeerPayload(payload, from, to, acknowledgementId)
    }

    fun encodeIceCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?,
        from: Int,
        to: Int,
        acknowledgementId: Int,
    ): String {
        val payload = linkedMapOf<String, Any?>("candidate" to candidate)
        if (sdpMid != null) payload["sdpMid"] = sdpMid
        if (sdpMLineIndex != null) payload["sdpMLineIndex"] = sdpMLineIndex
        return encodePeerPayload(payload, from, to, acknowledgementId)
    }

    fun isTcpIceCandidate(candidate: String): Boolean =
        candidate.lowercase().split(Regex("\\s+")).any { it == "tcp" }

    private fun encodePeerPayload(
        payload: Map<String, Any?>,
        from: Int,
        to: Int,
        acknowledgementId: Int,
    ): String = Json.stringify(
        linkedMapOf(
            "peer_msg" to linkedMapOf(
                "from" to from,
                "to" to to,
                "msg" to Json.stringify(payload),
            ),
            "ackid" to acknowledgementId,
        ),
    )
}

enum class SignalingTraceDirection { RX, TX }

data class SignalingTraceEntry(
    val direction: SignalingTraceDirection,
    val type: String,
    val timestamp: Instant,
)

data class SdpSummary(
    val offerPresent: Boolean = false,
    val answerPresent: Boolean = false,
    val videoCodecs: List<String> = emptyList(),
    val h264PayloadTypes: List<Int> = emptyList(),
    val iceUfragPresent: Boolean = false,
    val icePasswordPresent: Boolean = false,
    val dtlsFingerprintPresent: Boolean = false,
    val firstVideoMid: String? = null,
    val firstVideoPort: Int? = null,
)

data class VideoMediaTarget(
    val mid: String?,
    val mLineIndex: Int,
    val port: Int?,
)

data class IceCredentialsPresence(
    val ufrag: String,
    val password: String,
    val fingerprintSha256: String,
)

data class NvstSdpConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val maxBitrateKbps: Int,
    val bitDepth: Int = 8,
    val partialReliableThresholdMs: Int = 300,
)

/** 仅处理 v5.0 H.264/SDR8 所需的最小 SDP 变换。 */
object GfnSdpTools {
    fun summarize(sdp: String, isOffer: Boolean): SdpSummary {
        val lines = lines(sdp)
        val videoRtpMaps = buildList {
            var inFirstVideo = false
            var videoSeen = false
            for (line in lines) {
                if (line.startsWith("m=video") && !videoSeen) {
                    inFirstVideo = true
                    videoSeen = true
                    continue
                }
                if (line.startsWith("m=")) {
                    if (inFirstVideo) break
                    inFirstVideo = false
                }
                if (inFirstVideo && line.startsWith("a=rtpmap:")) add(line)
            }
        }
        val codecs = videoRtpMaps.mapNotNull { line ->
            line.substringAfter(' ', "").substringBefore('/').takeIf { it.isNotBlank() }
        }.distinct()
        val h264Pts = videoRtpMaps.mapNotNull { line ->
            val rest = line.removePrefix("a=rtpmap:")
            val pt = rest.substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
            val codec = rest.substringAfter(' ', "").substringBefore('/').uppercase()
            pt.takeIf { codec == "H264" }
        }
        val target = firstVideoTarget(sdp)
        val videoMid = target?.mid
        val videoPort = target?.port
        return SdpSummary(
            offerPresent = isOffer,
            answerPresent = !isOffer,
            videoCodecs = codecs,
            h264PayloadTypes = h264Pts,
            iceUfragPresent = lines.any { it.startsWith("a=ice-ufrag:") },
            icePasswordPresent = lines.any { it.startsWith("a=ice-pwd:") },
            dtlsFingerprintPresent = lines.any { it.startsWith("a=fingerprint:sha-256 ") },
            firstVideoMid = videoMid,
            firstVideoPort = videoPort,
        )
    }

    /**
     * 只在 Answer 上做 codec 收敛：保留 H.264、其 RTX apt，以及 RED/ULPFEC/FLEXFEC。
     * 不修改服务器 Offer，避免破坏 FEC/SSRC 关系。
     */
    fun preferH264InAnswer(sdp: String): String {
        val separator = separator(sdp)
        val input = lines(sdp)
        val h264Pts = linkedSetOf<String>()
        val repairPts = linkedSetOf<String>()

        input.forEach { line ->
            if (!line.startsWith("a=rtpmap:")) return@forEach
            val rest = line.removePrefix("a=rtpmap:")
            val pt = rest.substringBefore(' ')
            val codec = rest.substringAfter(' ', "").substringBefore('/').lowercase()
            if (codec == "h264") h264Pts += pt
            if (codec == "red" || codec == "ulpfec" || codec == "flexfec-03") repairPts += pt
        }
        if (h264Pts.isEmpty()) return sdp

        val allowed = linkedSetOf<String>().apply {
            addAll(h264Pts)
            addAll(repairPts)
        }
        input.forEach { line ->
            if (!line.startsWith("a=fmtp:")) return@forEach
            val rest = line.removePrefix("a=fmtp:")
            val pt = rest.substringBefore(' ')
            val params = rest.substringAfter(' ', "")
            val apt = Regex("(?:^|[;\\s])apt=(\\d+)").find(params)?.groupValues?.getOrNull(1)
            if (apt != null && h264Pts.contains(apt)) allowed += pt
        }

        val output = mutableListOf<String>()
        var inVideo = false
        input.forEach { line ->
            if (line.startsWith("m=video")) {
                inVideo = true
                val parts = line.split(' ')
                if (parts.size > 3) {
                    val payloads = parts.drop(3).filter { allowed.contains(it) }
                    output += (parts.take(3) + payloads).joinToString(" ")
                } else {
                    output += line
                }
                return@forEach
            }
            if (line.startsWith("m=")) inVideo = false
            if (inVideo) {
                val pt = attributePayloadType(line)
                if (pt != null && !allowed.contains(pt)) return@forEach
            }
            output += line
        }
        return output.joinToString(separator)
    }

    fun injectBandwidth(sdp: String, videoKbps: Int, audioKbps: Int = 128): String {
        val separator = separator(sdp)
        val input = lines(sdp)
        val output = mutableListOf<String>()
        input.forEachIndexed { index, line ->
            output += line
            val next = input.getOrNull(index + 1).orEmpty()
            if (line.startsWith("m=video") && !next.startsWith("b=")) {
                output += "b=AS:${videoKbps.coerceAtLeast(1)}"
            } else if (line.startsWith("m=audio") && !next.startsWith("b=")) {
                output += "b=AS:${audioKbps.coerceAtLeast(1)}"
            }
        }
        return output.joinToString(separator)
    }

    fun rewriteOfferConnectionAddresses(sdp: String, serverIp: String): String {
        if (serverIp.isBlank()) return sdp
        return sdp
            .replace("c=IN IP4 0.0.0.0", "c=IN IP4 $serverIp")
            .replace("c=IN IP4 127.0.0.1", "c=IN IP4 $serverIp")
            .replace(" 0.0.0.0 ", " $serverIp ")
            .replace(" 127.0.0.1 ", " $serverIp ")
    }

    fun extractIceCredentials(sdp: String): IceCredentialsPresence {
        val lines = lines(sdp)
        return IceCredentialsPresence(
            ufrag = lines.firstOrNull { it.startsWith("a=ice-ufrag:") }
                ?.substringAfter("a=ice-ufrag:")?.trim().orEmpty(),
            password = lines.firstOrNull { it.startsWith("a=ice-pwd:") }
                ?.substringAfter("a=ice-pwd:")?.trim().orEmpty(),
            fingerprintSha256 = lines.firstOrNull { it.startsWith("a=fingerprint:sha-256 ") }
                ?.substringAfter("a=fingerprint:sha-256 ")?.trim().orEmpty(),
        )
    }

    fun buildNvstSdp(credentials: IceCredentialsPresence, config: NvstSdpConfig): String {
        val minBitrate = maxOf(5_000, config.maxBitrateKbps * 35 / 100)
        val initialBitrate = maxOf(minBitrate, config.maxBitrateKbps * 70 / 100)
        return listOf(
            "v=0",
            "o=SdpTest test_id_13 14 IN IPv4 127.0.0.1",
            "s=-",
            "t=0 0",
            "a=general.icePassword:${credentials.password}",
            "a=general.iceUserNameFragment:${credentials.ufrag}",
            "a=general.dtlsFingerprint:${credentials.fingerprintSha256}",
            "m=video 0 RTP/AVP",
            "a=msid:fbc-video-0",
            "a=vqos.fec.rateDropWindow:10",
            "a=vqos.fec.repairPercent:5",
            "a=vqos.drc.enable:0",
            "a=vqos.dfc.enable:0",
            "a=video.enableRtpNack:1",
            "a=video.packetSize:1140",
            "a=bwe.useOwdCongestionControl:1",
            "a=vqos.resControl.cpmRtc.enable:0",
            "a=vqos.resControl.cpmRtc.minResolutionPercent:100",
            "a=video.clientViewportWd:${config.width}",
            "a=video.clientViewportHt:${config.height}",
            "a=video.maxFPS:${config.fps}",
            "a=video.initialBitrateKbps:$initialBitrate",
            "a=video.initialPeakBitrateKbps:${config.maxBitrateKbps}",
            "a=vqos.bw.maximumBitrateKbps:${config.maxBitrateKbps}",
            "a=vqos.bw.minimumBitrateKbps:$minBitrate",
            "a=vqos.bw.peakBitrateKbps:${config.maxBitrateKbps}",
            "a=video.bitDepth:${config.bitDepth}",
            "m=audio 0 RTP/AVP",
            "a=msid:audio",
            "m=application 0 RTP/AVP",
            "a=msid:input_1",
            "a=ri.partialReliableThresholdMs:${config.partialReliableThresholdMs}",
            "a=ri.hidDeviceMask:0",
            "a=ri.enablePartiallyReliableTransferGamepad:0",
            "a=ri.enablePartiallyReliableTransferHid:0",
            "",
        ).joinToString("\r\n")
    }

    fun firstVideoTarget(sdp: String): VideoMediaTarget? {
        val input = lines(sdp)
        var videoIndex = -1
        var currentMediaIndex = -1
        var port: Int? = null
        var mid: String? = null
        var inVideo = false
        for (line in input) {
            if (line.startsWith("m=")) {
                currentMediaIndex += 1
                inVideo = line.startsWith("m=video") && videoIndex < 0
                if (inVideo) {
                    videoIndex = currentMediaIndex
                    port = line.split(' ').getOrNull(1)?.toIntOrNull()?.takeIf { it > 9 }
                } else if (videoIndex >= 0) {
                    break
                }
            } else if (inVideo && line.startsWith("a=mid:")) {
                mid = line.substringAfter("a=mid:").trim().takeIf { it.isNotEmpty() }
            }
        }
        if (videoIndex < 0) return null
        return VideoMediaTarget(mid = mid ?: videoIndex.toString(), mLineIndex = videoIndex, port = port)
    }

    fun partialReliableThresholdMs(sdp: String, defaultValue: Int = 300): Int {
        val value = Regex("ri\\.partialReliableThresholdMs[: ]+(\\d+)")
            .find(sdp)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return (value ?: defaultValue).coerceIn(1, 65_535)
    }

    fun firstVideoMidAndPort(sdp: String): Pair<String?, Int?> =
        firstVideoTarget(sdp)?.let { it.mid to it.port } ?: (null to null)

    private fun attributePayloadType(line: String): String? {
        val prefixes = listOf("a=rtpmap:", "a=fmtp:", "a=rtcp-fb:")
        val prefix = prefixes.firstOrNull(line::startsWith) ?: return null
        return line.removePrefix(prefix).substringBefore(' ').takeIf { it != "*" }
    }

    private fun separator(sdp: String): String = if (sdp.contains("\r\n")) "\r\n" else "\n"

    private fun lines(sdp: String): List<String> =
        sdp.split(if (sdp.contains("\r\n")) "\r\n" else "\n")
}
