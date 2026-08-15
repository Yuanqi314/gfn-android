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
    val hevcPayloadTypes: List<Int> = emptyList(),
    val hevcMainPayloadTypes: List<Int> = emptyList(),
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

data class VideoCodecDescription(
    val payloadType: Int,
    val name: String,
    val clockRate: Int?,
    val fmtp: String?,
    val parameters: Map<String, String>,
    val rtxPayloadTypes: List<Int> = emptyList(),
) {
    val normalizedName: String
        get() = when (name.trim().uppercase()) {
            "HEVC" -> "H265"
            else -> name.trim().uppercase()
        }

    val profileId: String? get() = parameters["profile-id"]
    val tierFlag: String? get() = parameters["tier-flag"]
    val levelId: String? get() = parameters["level-id"]
    val txMode: String? get() = parameters["tx-mode"]
}

data class VideoRtxAssociation(
    val payloadType: Int,
    val apt: Int,
)

data class AudioCodecDescription(
    val payloadType: Int,
    val name: String,
    val clockRate: Int?,
    val channels: Int?,
    val fmtp: String?,
)

data class AudioAnswerMungeResult(
    val sdp: String,
    val mode: String,
    val selectedCodec: AudioCodecDescription?,
    val opusStereoEnabled: Boolean,
    val surroundAccepted: Boolean,
    val limitation: String? = null,
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

/** v6.0: H.264/HEVC Main SDR8 视频选择 + Stereo/multiopus 音频 Answer 变换。 */
object GfnSdpTools {
    fun summarize(sdp: String, isOffer: Boolean): SdpSummary {
        val allLines = lines(sdp)
        val details = firstVideoCodecDetails(sdp)
        val codecs = details.map { it.name }.distinct()
        val h264Pts = details.filter { it.normalizedName == "H264" }.map { it.payloadType }
        val hevcPts = details.filter { it.normalizedName == "H265" }.map { it.payloadType }
        val hevcMainPts = details.filter {
            it.normalizedName == "H265" && it.profileId == "1"
        }.map { it.payloadType }
        val target = firstVideoTarget(sdp)
        return SdpSummary(
            offerPresent = isOffer,
            answerPresent = !isOffer,
            videoCodecs = codecs,
            h264PayloadTypes = h264Pts,
            hevcPayloadTypes = hevcPts,
            hevcMainPayloadTypes = hevcMainPts,
            iceUfragPresent = allLines.any { it.startsWith("a=ice-ufrag:") },
            icePasswordPresent = allLines.any { it.startsWith("a=ice-pwd:") },
            dtlsFingerprintPresent = allLines.any { it.startsWith("a=fingerprint:sha-256 ") },
            firstVideoMid = target?.mid,
            firstVideoPort = target?.port,
        )
    }

    /**
     * Parse the first video media section without interpreting payload numbers as stable codec
     * identities. Dynamic PTs remain session-local; compatibility decisions must use codec/fmtp.
     */
    fun firstVideoCodecDetails(sdp: String): List<VideoCodecDescription> {
        val rtp = linkedMapOf<Int, Pair<String, Int?>>()
        val fmtp = linkedMapOf<Int, String>()
        firstVideoAttributeLines(sdp, "a=rtpmap:").forEach { line ->
            val rest = line.removePrefix("a=rtpmap:")
            val pt = rest.substringBefore(' ').toIntOrNull() ?: return@forEach
            val encoding = rest.substringAfter(' ', "")
            val parts = encoding.split('/')
            val name = parts.getOrNull(0)?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
            rtp[pt] = name to parts.getOrNull(1)?.toIntOrNull()
        }
        firstVideoAttributeLines(sdp, "a=fmtp:").forEach { line ->
            val rest = line.removePrefix("a=fmtp:")
            val pt = rest.substringBefore(' ').toIntOrNull() ?: return@forEach
            fmtp[pt] = rest.substringAfter(' ', "").trim()
        }
        val rtxByPrimary = linkedMapOf<Int, MutableList<Int>>()
        rtp.forEach { (pt, value) ->
            if (normalizeVideoCodecName(value.first) != "RTX") return@forEach
            val apt = parseFmtpParameters(fmtp[pt]).get("apt")?.toIntOrNull() ?: return@forEach
            rtxByPrimary.getOrPut(apt) { mutableListOf() }.add(pt)
        }
        return rtp.map { (pt, value) ->
            val fmtpText = fmtp[pt]
            VideoCodecDescription(
                payloadType = pt,
                name = value.first,
                clockRate = value.second,
                fmtp = fmtpText,
                parameters = parseFmtpParameters(fmtpText),
                rtxPayloadTypes = rtxByPrimary[pt].orEmpty().sorted(),
            )
        }
    }

    fun firstVideoPayloadOrder(sdp: String): List<Int> =
        firstMediaSection(sdp, "video").firstOrNull()
            ?.split(Regex("\\s+"))
            ?.drop(3)
            ?.mapNotNull(String::toIntOrNull)
            .orEmpty()

    fun firstVideoRtxAssociations(sdp: String): List<VideoRtxAssociation> =
        firstVideoCodecDetails(sdp).filter { it.normalizedName == "RTX" }.mapNotNull { codec ->
            codec.parameters["apt"]?.toIntOrNull()?.let { apt ->
                VideoRtxAssociation(payloadType = codec.payloadType, apt = apt)
            }
        }

    /**
     * Correlate an H265 payload in an Answer with an H265 Main payload from the same Offer/Answer
     * exchange. Dynamic payload numbers are not treated as globally stable codec identities; they
     * are used only as session-local lineage after both SDP sections have independently identified
     * the payload as H265. This lets libwebrtc omit profile-id/tier-flag in its Answer without
     * accidentally accepting the Offer's Main10 payload.
     */
    fun matchingAnswerHevcMainPayloadTypes(offer: String, answer: String): List<Int> {
        val offerOrder = firstVideoPayloadOrder(offer).toSet()
        val answerOrder = firstVideoPayloadOrder(answer)
        val offeredMain = firstVideoCodecDetails(offer)
            .asSequence()
            .filter { it.payloadType in offerOrder }
            .filter { it.normalizedName == "H265" && it.profileId == "1" }
            .map { it.payloadType }
            .toSet()
        if (offeredMain.isEmpty()) return emptyList()

        val answeredH265 = firstVideoCodecDetails(answer)
            .asSequence()
            .filter { it.payloadType in answerOrder }
            .filter { it.normalizedName == "H265" }
            .map { it.payloadType }
            .toSet()
        return answerOrder.filter { it in offeredMain && it in answeredH265 }.distinct()
    }

    /**
     * Converge the first video Answer section to one receive codec while retaining its RTX and
     * repair payloads. The server Offer remains untouched. HEVC callers may constrain either an
     * explicit profile-id or a session-local set of primary payloads previously matched to the
     * Offer, so Main10 cannot be pulled into the SDR8 experiment.
     */
    fun preferVideoCodecInAnswer(
        sdp: String,
        codec: String,
        preferredHevcProfileId: Int? = null,
        allowedPrimaryPayloadTypes: Set<Int>? = null,
    ): String {
        val separator = separator(sdp)
        val input = lines(sdp)
        val targetCodec = normalizeVideoCodecName(codec)
        val rtpCodecByPt = linkedMapOf<String, String>()
        val fmtpByPt = linkedMapOf<String, String>()
        val repairPts = linkedSetOf<String>()

        firstVideoAttributeLines(sdp, "a=rtpmap:").forEach { line ->
            val rest = line.removePrefix("a=rtpmap:")
            val pt = rest.substringBefore(' ')
            val name = normalizeVideoCodecName(rest.substringAfter(' ', "").substringBefore('/'))
            rtpCodecByPt[pt] = name
            if (name == "RED" || name == "ULPFEC" || name == "FLEXFEC-03") repairPts += pt
        }
        firstVideoAttributeLines(sdp, "a=fmtp:").forEach { line ->
            val rest = line.removePrefix("a=fmtp:")
            fmtpByPt[rest.substringBefore(' ')] = rest.substringAfter(' ', "")
        }

        var primaryPts = rtpCodecByPt.filterValues { it == targetCodec }.keys.toCollection(linkedSetOf())
        if (targetCodec == "H265" && preferredHevcProfileId != null) {
            primaryPts = primaryPts.filterTo(linkedSetOf()) { pt ->
                fmtpByPt[pt]?.containsParameter("profile-id", preferredHevcProfileId.toString()) == true
            }
        }
        if (allowedPrimaryPayloadTypes != null) {
            primaryPts = primaryPts.filterTo(linkedSetOf()) { pt ->
                pt.toIntOrNull()?.let(allowedPrimaryPayloadTypes::contains) == true
            }
        }
        if (primaryPts.isEmpty()) return sdp

        val allowed = linkedSetOf<String>().apply {
            addAll(primaryPts)
            addAll(repairPts)
        }
        fmtpByPt.forEach { (pt, params) ->
            val apt = Regex("(?:^|[;\\s])apt=(\\d+)").find(params)?.groupValues?.getOrNull(1)
            if (apt != null && primaryPts.contains(apt)) allowed += pt
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

    fun preferH264InAnswer(sdp: String): String = preferVideoCodecInAnswer(sdp, "H264")

    /** Return codecs declared by the first game-audio m-line only. */
    fun firstAudioCodecs(sdp: String): List<AudioCodecDescription> {
        val input = lines(sdp)
        val rtp = linkedMapOf<Int, Triple<String, Int?, Int?>>()
        val fmtp = linkedMapOf<Int, String>()
        var inFirstAudio = false
        var audioSeen = false
        for (line in input) {
            if (line.startsWith("m=audio") && !audioSeen) {
                inFirstAudio = true
                audioSeen = true
                continue
            }
            if (line.startsWith("m=")) {
                if (inFirstAudio) break
                inFirstAudio = false
            }
            if (!inFirstAudio) continue
            if (line.startsWith("a=rtpmap:")) {
                val rest = line.removePrefix("a=rtpmap:")
                val pt = rest.substringBefore(' ').toIntOrNull() ?: continue
                val encoding = rest.substringAfter(' ', "")
                val parts = encoding.split('/')
                val name = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: continue
                val clock = parts.getOrNull(1)?.toIntOrNull()
                val channels = parts.getOrNull(2)?.toIntOrNull()
                rtp[pt] = Triple(name, clock, channels)
            } else if (line.startsWith("a=fmtp:")) {
                val rest = line.removePrefix("a=fmtp:")
                val pt = rest.substringBefore(' ').toIntOrNull() ?: continue
                fmtp[pt] = rest.substringAfter(' ', "").trim()
            }
        }
        return rtp.map { (pt, value) ->
            AudioCodecDescription(
                payloadType = pt,
                name = value.first,
                clockRate = value.second,
                channels = value.third,
                fmtp = fmtp[pt],
            )
        }
    }

    fun firstAudioCodec(sdp: String, preferredName: String? = null): AudioCodecDescription? {
        val codecs = firstAudioCodecs(sdp)
        return preferredName?.let { wanted ->
            codecs.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        } ?: codecs.firstOrNull()
    }

    /**
     * v5.4 audio answer policy.
     *
     * 2ch: retain libwebrtc's answer and add `stereo=1` to the first Opus fmtp when present.
     * 6ch: CloudNow-derived experimental path. libwebrtc ships a multiopus decoder but does not
     * advertise it in the builtin decoder factory, so createAnswer can reject the game-audio m-line.
     * Rebuild only that first audio section using the offer's exact multiopus rtpmap/fmtp and the
     * answer bundle transport. Android Java ADM still exposes 1/2-channel playout, so this is a
     * negotiation/2ch-ADM probe, not a claim of native 5.1 output.
     */
    fun mungeAudioAnswer(answer: String, offer: String, requestedChannels: Int): AudioAnswerMungeResult {
        if (requestedChannels < 6) {
            val stereo = ensureFirstAudioOpusStereo(answer)
            val selected = firstAudioCodec(stereo, "opus") ?: firstAudioCodec(stereo)
            return AudioAnswerMungeResult(
                sdp = stereo,
                mode = "STEREO_NATIVE",
                selectedCodec = selected,
                opusStereoEnabled = selected?.fmtp?.containsParameter("stereo", "1") == true,
                surroundAccepted = false,
            )
        }

        val multiopus = firstAudioCodec(offer, "multiopus")
            ?: return AudioAnswerMungeResult(
                sdp = answer,
                mode = "SURROUND_UNAVAILABLE",
                selectedCodec = firstAudioCodec(answer),
                opusStereoEnabled = false,
                surroundAccepted = false,
                limitation = "GFN Offer 未包含 multiopus；不能验证 6ch 音频。",
            )
        if (multiopus.channels != 6) {
            return AudioAnswerMungeResult(
                sdp = answer,
                mode = "SURROUND_UNAVAILABLE",
                selectedCodec = firstAudioCodec(answer),
                opusStereoEnabled = false,
                surroundAccepted = false,
                limitation = "GFN multiopus Offer 声道数=${multiopus.channels ?: -1}，v5.4 仅验证 6ch。",
            )
        }

        val existing = firstAudioCodec(answer, "multiopus")
        val acceptedAlready = existing?.payloadType == multiopus.payloadType && firstAudioPort(answer)?.let { it > 0 } == true
        val munged = if (acceptedAlready) answer else rebuildFirstAudioForMultiopus(answer, offer, multiopus)
        val selected = firstAudioCodec(munged, "multiopus")
        val accepted = selected?.payloadType == multiopus.payloadType && firstAudioPort(munged)?.let { it > 0 } == true
        return AudioAnswerMungeResult(
            sdp = munged,
            mode = if (accepted) "SURROUND_MULTI_OPUS_ADM_2CH_PROBE" else "SURROUND_UNAVAILABLE",
            selectedCodec = selected ?: firstAudioCodec(munged),
            opusStereoEnabled = false,
            surroundAccepted = accepted,
            limitation = if (accepted) {
                "multiopus 6ch 已协商；Android upstream JavaAudioDeviceModule 仍只配置 2ch playout，6ch→2ch 的实际行为（下混或失败）必须由真机裁决。"
            } else {
                "无法在 Answer 中接受 GFN multiopus 6ch。"
            },
        )
    }

    private fun ensureFirstAudioOpusStereo(sdp: String): String {
        val separator = separator(sdp)
        val input = lines(sdp)
        val opusPts = firstAudioCodecs(sdp)
            .filter { it.name.equals("opus", ignoreCase = true) && (it.channels == null || it.channels == 2) }
            .map { it.payloadType.toString() }
            .toSet()
        if (opusPts.isEmpty()) return sdp

        var inFirstAudio = false
        var audioSeen = false
        var changed = false
        val output = input.map { line ->
            if (line.startsWith("m=audio") && !audioSeen) {
                audioSeen = true
                inFirstAudio = true
                return@map line
            }
            if (line.startsWith("m=")) inFirstAudio = false
            if (!inFirstAudio || !line.startsWith("a=fmtp:")) return@map line
            val rest = line.removePrefix("a=fmtp:")
            val pt = rest.substringBefore(' ')
            if (pt !in opusPts || line.containsParameter("stereo", "1")) return@map line
            changed = true
            val stereoParameter = Regex("(?:^|[;\\s])stereo=[^;\\s]+", RegexOption.IGNORE_CASE)
            if (stereoParameter.containsMatchIn(line)) {
                line.replace(stereoParameter) { match ->
                    val prefix = match.value.takeWhile { it == ';' || it.isWhitespace() }
                    "${prefix}stereo=1"
                }
            } else {
                "$line;stereo=1"
            }
        }
        return if (changed) output.joinToString(separator) else sdp
    }

    private fun rebuildFirstAudioForMultiopus(
        answer: String,
        offer: String,
        multiopus: AudioCodecDescription,
    ): String {
        val separator = separator(answer)
        val answerLines = lines(answer)
        val transport = mutableListOf<String>()
        var inVideo = false
        for (line in answerLines) {
            if (line.startsWith("m=video")) {
                inVideo = true
                continue
            }
            if (line.startsWith("m=")) inVideo = false
            if (!inVideo) continue
            if (
                line.startsWith("a=ice-ufrag:") ||
                line.startsWith("a=ice-pwd:") ||
                line.startsWith("a=ice-options:") ||
                line.startsWith("a=fingerprint:") ||
                line.startsWith("a=setup:")
            ) {
                transport += line
            }
        }
        if (transport.isEmpty()) return answer

        val offerAudio = firstMediaSection(offer, "audio")
        val audioMid = offerAudio.firstOrNull { it.startsWith("a=mid:") }
            ?.substringAfter("a=mid:")?.trim()?.takeIf(String::isNotBlank) ?: "0"
        val extmaps = offerAudio.filter { it.startsWith("a=extmap:") }
        val fmtp = multiopus.fmtp.orEmpty()
        val section = buildList {
            add("m=audio 9 UDP/TLS/RTP/SAVPF ${multiopus.payloadType}")
            add("c=IN IP4 0.0.0.0")
            add("a=rtcp:9 IN IP4 0.0.0.0")
            addAll(transport)
            add("a=mid:$audioMid")
            addAll(extmaps)
            add("a=recvonly")
            add("a=rtcp-mux")
            add("a=rtcp-rsize")
            add("a=rtpmap:${multiopus.payloadType} ${multiopus.name}/${multiopus.clockRate ?: 48_000}/${multiopus.channels ?: 6}")
            add("a=rtcp-fb:${multiopus.payloadType} transport-cc")
            if (fmtp.isNotBlank()) add("a=fmtp:${multiopus.payloadType} $fmtp")
        }

        val output = mutableListOf<String>()
        var skippingFirstAudio = false
        var replaced = false
        for (line in answerLines) {
            if (line.startsWith("a=group:BUNDLE")) {
                val mids = line.removePrefix("a=group:BUNDLE")
                    .trim().split(Regex("\\s+")).filter(String::isNotBlank).toMutableList()
                if (audioMid !in mids) mids.add(0, audioMid)
                output += "a=group:BUNDLE ${mids.joinToString(" ")}"
                continue
            }
            if (line.startsWith("m=audio") && !replaced) {
                replaced = true
                skippingFirstAudio = true
                output += section
                continue
            }
            if (skippingFirstAudio) {
                if (!line.startsWith("m=")) continue
                skippingFirstAudio = false
            }
            output += line
        }
        return if (replaced) output.joinToString(separator) else answer
    }

    private fun firstMediaSection(sdp: String, media: String): List<String> {
        val result = mutableListOf<String>()
        var inTarget = false
        var seen = false
        for (line in lines(sdp)) {
            if (line.startsWith("m=$media") && !seen) {
                inTarget = true
                seen = true
                result += line
                continue
            }
            if (line.startsWith("m=")) {
                if (inTarget) break
                inTarget = false
            }
            if (inTarget) result += line
        }
        return result
    }

    private fun firstAudioPort(sdp: String): Int? =
        firstMediaSection(sdp, "audio").firstOrNull()
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()

    private fun String.containsParameter(name: String, value: String): Boolean =
        Regex("(?:^|[;\\s])${Regex.escape(name)}=${Regex.escape(value)}(?:$|[;\\s])", RegexOption.IGNORE_CASE)
            .containsMatchIn(this)

    private fun parseFmtpParameters(fmtp: String?): Map<String, String> {
        if (fmtp.isNullOrBlank()) return emptyMap()
        return buildMap {
            fmtp.split(';').forEach { token ->
                val part = token.trim()
                if (part.isEmpty()) return@forEach
                val separator = part.indexOf('=')
                if (separator < 0) {
                    put(part.lowercase(), "")
                } else {
                    val key = part.substring(0, separator).trim().lowercase()
                    val value = part.substring(separator + 1).trim()
                    if (key.isNotEmpty()) put(key, value)
                }
            }
        }
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

    private fun firstVideoAttributeLines(sdp: String, prefix: String): List<String> {
        val result = mutableListOf<String>()
        var inFirstVideo = false
        var seenVideo = false
        for (line in lines(sdp)) {
            if (line.startsWith("m=video") && !seenVideo) {
                seenVideo = true
                inFirstVideo = true
                continue
            }
            if (line.startsWith("m=")) {
                if (inFirstVideo) break
                inFirstVideo = false
            }
            if (inFirstVideo && line.startsWith(prefix)) result += line
        }
        return result
    }

    private fun normalizeVideoCodecName(value: String): String = when (value.trim().uppercase()) {
        "HEVC" -> "H265"
        else -> value.trim().uppercase()
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
