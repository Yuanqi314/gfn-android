package dev.gfn.webrtc

import android.util.Log
import dev.gfn.signaling.GfnSdpTools
import dev.gfn.signaling.VideoCodecDescription
import dev.gfn.stream.VideoCodecPreference
import org.webrtc.RtpCapabilities

/** v6.0.4 production HEVC Main capability matching and pre-createAnswer preference planning. */
internal data class GfnVideoCodecPreferencePlan(
    val orderedCapabilities: List<RtpCapabilities.CodecCapability>,
    val orderedLabels: List<String>,
    val compatibleHevcMainCount: Int,
    val h264Count: Int,
    val auxiliaryCount: Int,
) {
    val hasHevcCandidate: Boolean get() = compatibleHevcMainCount > 0
}

internal data class GfnHevcOfferCompatibility(
    val compatiblePayloadTypes: List<Int>,
    val rejectedCandidates: List<String>,
    val localCapability: GfnHevcDecoderCapability?,
    val streamSupport: GfnHevcStreamSupport,
) {
    val compatible: Boolean get() = compatiblePayloadTypes.isNotEmpty()

    val reason: String
        get() = when {
            localCapability == null -> streamSupport.reason
            !streamSupport.supported -> streamSupport.reason
            compatible -> "compatible H265 Main/High payloads=$compatiblePayloadTypes"
            rejectedCandidates.isNotEmpty() -> rejectedCandidates.joinToString(" | ")
            else -> "GFN Offer has no compatible H265 Main/High candidate"
        }
}

internal object GfnHevcProductionCompatibilityMatcher {
    fun evaluate(
        remoteCodecs: List<VideoCodecDescription>,
        localCapability: GfnHevcDecoderCapability?,
        streamSupport: GfnHevcStreamSupport,
    ): GfnHevcOfferCompatibility {
        val rejected = mutableListOf<String>()
        val compatible = mutableListOf<Int>()
        val remoteH265 = remoteCodecs.filter { it.normalizedName == "H265" }

        remoteH265.forEach { remote ->
            val prefix = "pt=${remote.payloadType}"
            if (remote.profileId != GfnHevcProfile.Main.sdpProfileId) {
                rejected += "$prefix profile=${remote.profileId ?: "default"} is not HEVC Main"
                return@forEach
            }
            if (remote.tierFlag != GfnHevcTier.High.sdpTierFlag) {
                rejected += "$prefix tier=${remote.tierFlag ?: "default"} is not High Tier"
                return@forEach
            }
            val remoteLevel = GfnHevcLevel.fromSdpLevelId(remote.levelId)
            if (remoteLevel == null) {
                rejected += "$prefix level=${remote.levelId ?: "default"} is not a recognized explicit HEVC level"
                return@forEach
            }
            val txMode = remote.txMode?.trim()?.uppercase() ?: "SRST"
            if (txMode != "SRST") {
                rejected += "$prefix tx-mode=$txMode is not supported by v6.0.4"
                return@forEach
            }
            if (localCapability == null) {
                rejected += "$prefix has no bound local production HEVC decoder"
                return@forEach
            }
            if (localCapability.profile != GfnHevcProfile.Main || localCapability.tier != GfnHevcTier.High) {
                rejected += "$prefix local decoder is not Main/High"
                return@forEach
            }
            if (remoteLevel.rank > localCapability.maxLevel.rank) {
                rejected += "$prefix remote level=${remoteLevel.label} exceeds local max=${localCapability.maxLevel.label}"
                return@forEach
            }
            if (!streamSupport.supported) {
                rejected += "$prefix local stream safety gate failed: ${streamSupport.reason}"
                return@forEach
            }
            compatible += remote.payloadType
        }

        return GfnHevcOfferCompatibility(
            compatiblePayloadTypes = compatible.distinct(),
            rejectedCandidates = rejected,
            localCapability = localCapability,
            streamSupport = streamSupport,
        )
    }
}

internal object GfnHevcCodecPreferencePlanner {
    /**
     * Production ordering is restricted to local H265 capabilities that are compatible with an
     * actual remote H265 Main/High/SRST candidate. Generic H265 is intentionally excluded.
     */
    fun build(
        capabilities: List<RtpCapabilities.CodecCapability>,
        remoteCodecs: List<VideoCodecDescription>,
    ): GfnVideoCodecPreferencePlan {
        val compatibleHevc = capabilities.filter { local ->
            normalize(local.name) == "H265" && remoteCodecs.any { remote ->
                isCompatibleHevcMain(local, remote)
            }
        }
        val h264 = capabilities.filter { normalize(it.name) == "H264" }
        val primary = compatibleHevc + h264
        val includedLocalPrimaryPts = primary.map { it.preferredPayloadType }.toSet()
        val auxiliary = capabilities.filter { codec ->
            when (normalize(codec.name)) {
                "RTX" -> {
                    val apt = codec.parameters.orEmpty().parameter("apt")?.toIntOrNull()
                    apt == null || apt in includedLocalPrimaryPts
                }
                "RED", "ULPFEC", "FLEXFEC-03" -> true
                else -> false
            }
        }
        val ordered = (primary + auxiliary).distinctBy(::capabilityIdentity)
        return GfnVideoCodecPreferencePlan(
            orderedCapabilities = ordered,
            orderedLabels = ordered.map(::capabilityLabel),
            compatibleHevcMainCount = compatibleHevc.size,
            h264Count = h264.size,
            auxiliaryCount = auxiliary.size,
        )
    }

    internal fun isCompatibleHevcMain(
        local: RtpCapabilities.CodecCapability,
        remote: VideoCodecDescription,
    ): Boolean {
        if (normalize(local.name) != "H265" || remote.normalizedName != "H265") return false
        val localParameters = local.parameters.orEmpty()
        val localProfile = localParameters.parameter("profile-id")
        val localTier = localParameters.parameter("tier-flag")
        val localLevel = GfnHevcLevel.fromSdpLevelId(localParameters.parameter("level-id"))
        val remoteLevel = GfnHevcLevel.fromSdpLevelId(remote.levelId)
        if (localProfile != GfnHevcProfile.Main.sdpProfileId) return false
        if (localTier != GfnHevcTier.High.sdpTierFlag) return false
        if (remote.profileId != GfnHevcProfile.Main.sdpProfileId) return false
        if (remote.tierFlag != GfnHevcTier.High.sdpTierFlag) return false
        if (localLevel == null || remoteLevel == null || remoteLevel.rank > localLevel.rank) return false
        val localTxMode = localParameters.parameter("tx-mode")?.trim()?.uppercase() ?: "SRST"
        val remoteTxMode = remote.txMode?.trim()?.uppercase() ?: "SRST"
        return localTxMode == remoteTxMode
    }

    private fun Map<String, String>.parameter(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun capabilityIdentity(codec: RtpCapabilities.CodecCapability): String = buildString {
        append(normalize(codec.name))
        append('|')
        append(codec.clockRate ?: -1)
        append('|')
        append(codec.parameters.orEmpty().toSortedMap(String.CASE_INSENSITIVE_ORDER))
    }

    fun capabilityLabel(codec: RtpCapabilities.CodecCapability): String = buildString {
        append(normalize(codec.name))
        append("(pt=")
        append(codec.preferredPayloadType)
        append(",params=")
        append(formatParameters(codec.parameters.orEmpty()))
        append(')')
    }

    private fun normalize(value: String): String = when (value.trim().uppercase()) {
        "HEVC" -> "H265"
        else -> value.trim().uppercase()
    }

    internal fun formatParameters(parameters: Map<String, String>): String =
        if (parameters.isEmpty()) "-" else parameters.entries
            .sortedBy { it.key.lowercase() }
            .joinToString(";") { (key, value) -> if (value.isBlank()) key else "$key=$value" }
}

/** One Logcat tag for the entire HEVC negotiation chain. */
internal object GfnHevcCompatLog {
    const val TAG = "GfnHevcCompat"

    fun sessionStart(
        generation: Long,
        requestedCodec: VideoCodecPreference,
        decoderCapabilities: List<GfnVideoCodecCapabilitySnapshot>,
        receiverCapabilities: List<GfnVideoCodecCapabilitySnapshot>,
        probeResult: GfnHevcDecoderProbeResult,
        productionCapability: GfnHevcDecoderCapability?,
        advertisementReason: String,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=SESSION requested=${requestedCodec.name} color=SDR8 " +
                "main10=false hdr=false decoderCaps=${decoderCapabilities.size} receiverCaps=${receiverCapabilities.size}",
        )
        probeResult.candidates.forEachIndexed { index, capability ->
            Log.i(TAG, hevcDecoderCapabilityLogLine(generation, "HEVC_DECODER_CANDIDATE", index, capability))
        }
        probeResult.errors.forEachIndexed { index, error ->
            Log.i(TAG, "gen=$generation phase=HEVC_DECODER_PROBE_ERROR index=$index error=${quote(error)}")
        }
        Log.i(
            TAG,
            "gen=$generation phase=HEVC_PRODUCTION_ADVERTISEMENT enabled=${productionCapability != null} " +
                "decoder=${productionCapability?.codecName ?: "-"} " +
                "profile=${productionCapability?.profile?.sdpProfileId ?: "-"} " +
                "tier=${productionCapability?.tier?.sdpTierFlag ?: "-"} " +
                "level=${productionCapability?.maxLevel?.sdpLevelId ?: "-"} " +
                "reason=${quote(advertisementReason)}",
        )
        decoderCapabilities.forEach { capability ->
            Log.i(TAG, capabilityLogLine(generation, "LOCAL_DECODER", capability))
        }
        receiverCapabilities.forEach { capability ->
            Log.i(TAG, capabilityLogLine(generation, "LOCAL_RECEIVER", capability))
        }
    }

    fun offerCompatibility(generation: Long, result: GfnHevcOfferCompatibility) {
        val local = result.localCapability
        Log.i(
            TAG,
            "gen=$generation phase=OFFER_HEVC_COMPATIBLE compatible=${result.compatible} " +
                "matched=${result.compatiblePayloadTypes} decoder=${local?.codecName ?: "-"} " +
                "profile=${local?.profile?.sdpProfileId ?: "-"} tier=${local?.tier?.sdpTierFlag ?: "-"} " +
                "maxLevel=${local?.maxLevel?.sdpLevelId ?: "-"} streamSafe=${result.streamSupport.supported} " +
                "reason=${quote(result.reason)}",
        )
        result.rejectedCandidates.forEachIndexed { index, reason ->
            Log.i(TAG, "gen=$generation phase=OFFER_HEVC_REJECT index=$index reason=${quote(reason)}")
        }
    }

    fun sdp(generation: Long, phase: String, sdp: String) {
        val summary = GfnSdpTools.summarize(sdp, isOffer = phase.startsWith("OFFER"))
        Log.i(
            TAG,
            "gen=$generation phase=$phase mlinePts=${GfnSdpTools.firstVideoPayloadOrder(sdp)} " +
                "h264=${summary.h264PayloadTypes} hevc=${summary.hevcPayloadTypes} " +
                "hevcMain=${summary.hevcMainPayloadTypes}",
        )
        GfnSdpTools.firstVideoCodecDetails(sdp).forEach { codec ->
            Log.i(TAG, codecLogLine(generation, phase, codec))
        }
    }

    fun answerHevcMainLineage(
        generation: Long,
        stage: String,
        offerMainPayloadTypes: List<Int>,
        answerHevcPayloadTypes: List<Int>,
        matchedPayloadTypes: List<Int>,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=ANSWER_HEVC_MAIN_LINEAGE stage=$stage " +
                "offerMain=$offerMainPayloadTypes answerHevc=$answerHevcPayloadTypes " +
                "matched=$matchedPayloadTypes",
        )
    }

    fun preferencePlan(generation: Long, plan: GfnVideoCodecPreferencePlan) {
        Log.i(
            TAG,
            "gen=$generation phase=PREFERENCE_PLAN compatibleHevcMain=${plan.compatibleHevcMainCount} " +
                "h264=${plan.h264Count} aux=${plan.auxiliaryCount} count=${plan.orderedLabels.size}",
        )
        plan.orderedLabels.forEachIndexed { index, label ->
            Log.i(TAG, "gen=$generation phase=PREFERENCE_ITEM order=$index capability=${quote(label)}")
        }
    }

    fun preferenceApply(
        generation: Long,
        attempted: Boolean,
        applied: Boolean,
        transceiverMid: String?,
        reason: String?,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=PREFERENCE_APPLY attempted=$attempted applied=$applied " +
                "mid=${transceiverMid ?: "-"} reason=${quote(reason ?: "-")}",
        )
    }

    fun decision(
        generation: Long,
        stage: String,
        requested: VideoCodecPreference,
        effective: VideoCodecPreference,
        fallbackReason: String?,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=DECISION stage=$stage requested=${requested.name} " +
                "effective=${effective.name} fallback=${fallbackReason != null} " +
                "reason=${quote(fallbackReason ?: "-")}",
        )
    }

    fun milestone(
        generation: Long,
        stage: String,
        effective: VideoCodecPreference,
        detail: String? = null,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=MEDIA stage=$stage effective=${effective.name} detail=${quote(detail ?: "-")}",
        )
    }

    private fun hevcDecoderCapabilityLogLine(
        generation: Long,
        phase: String,
        index: Int,
        capability: GfnHevcDecoderCapability,
    ): String = buildString {
        append("gen=$generation phase=$phase index=$index decoder=${capability.codecName}")
        append(" profile=${capability.profile.sdpProfileId}")
        append(" tier=${capability.tier.sdpTierFlag}")
        append(" maxLevel=${capability.maxLevel.sdpLevelId}")
        append(" maxLevelLabel=${capability.maxLevel.label}")
        append(" hardware=${capability.hardwareAccelerated}")
        append(" supports1080p60=${capability.supports1080p60}")
        append(" bitrateKbps=${capability.bitrateRangeKbps ?: "-"}")
    }

    private fun capabilityLogLine(
        generation: Long,
        phase: String,
        capability: GfnVideoCodecCapabilitySnapshot,
    ): String = buildString {
        append("gen=$generation phase=$phase source=${capability.source} index=${capability.index}")
        append(" codec=${capability.normalizedName}")
        append(" pt=${capability.preferredPayloadType ?: -1}")
        append(" mime=${capability.mimeType ?: "-"}")
        append(" clock=${capability.clockRate ?: -1}")
        append(" params=")
        append(quote(GfnHevcCodecPreferencePlanner.formatParameters(capability.parameters)))
    }

    private fun codecLogLine(generation: Long, phase: String, codec: VideoCodecDescription): String = buildString {
        append("gen=$generation phase=${phase}_CODEC")
        append(" pt=${codec.payloadType}")
        append(" codec=${codec.normalizedName}")
        append(" clock=${codec.clockRate ?: -1}")
        append(" profile=${codec.profileId ?: "-"}")
        append(" tier=${codec.tierFlag ?: "-"}")
        append(" level=${codec.levelId ?: "-"}")
        append(" txMode=${codec.txMode ?: "-"}")
        append(" rtx=${codec.rtxPayloadTypes}")
        append(" fmtp=")
        append(quote(codec.fmtp ?: "-"))
    }

    private fun quote(value: String): String = buildString {
        append('"')
        append(value.replace("\\", "\\\\").replace("\"", "\\\""))
        append('"')
    }
}
