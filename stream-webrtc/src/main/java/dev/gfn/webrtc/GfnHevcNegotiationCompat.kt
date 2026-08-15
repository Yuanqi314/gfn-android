package dev.gfn.webrtc

import android.util.Log
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.signaling.GfnSdpTools
import dev.gfn.signaling.VideoCodecDescription
import dev.gfn.stream.VideoCodecPreference
import org.webrtc.RtpCapabilities

/** v6.1.0 production HEVC Main/Main10 capability matching and pre-createAnswer planning. */
internal data class GfnVideoCodecPreferencePlan(
    val targetProfile: GfnHevcProfile,
    val orderedCapabilities: List<RtpCapabilities.CodecCapability>,
    val orderedLabels: List<String>,
    val compatibleHevcCount: Int,
    val h264Count: Int,
    val auxiliaryCount: Int,
) {
    val hasHevcCandidate: Boolean get() = compatibleHevcCount > 0
    val compatibleHevcMainCount: Int get() = if (targetProfile == GfnHevcProfile.Main) compatibleHevcCount else 0
    val compatibleHevcMain10Count: Int get() = if (targetProfile == GfnHevcProfile.Main10) compatibleHevcCount else 0
}

internal data class GfnHevcOfferCompatibility(
    val targetProfile: GfnHevcProfile,
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
            compatible -> "compatible H265 ${targetProfile.label}/High payloads=$compatiblePayloadTypes"
            rejectedCandidates.isNotEmpty() -> rejectedCandidates.joinToString(" | ")
            else -> "GFN Offer has no compatible H265 ${targetProfile.label}/High candidate"
        }
}

internal object GfnHevcProductionCompatibilityMatcher {
    fun evaluate(
        remoteCodecs: List<VideoCodecDescription>,
        targetProfile: GfnHevcProfile,
        localCapability: GfnHevcDecoderCapability?,
        streamSupport: GfnHevcStreamSupport,
    ): GfnHevcOfferCompatibility {
        val rejected = mutableListOf<String>()
        val compatible = mutableListOf<Int>()
        val remoteH265 = remoteCodecs.filter { it.normalizedName == "H265" }

        remoteH265.forEach { remote ->
            val prefix = "pt=${remote.payloadType}"
            if (remote.profileId != targetProfile.sdpProfileId) {
                rejected += "$prefix profile=${remote.profileId ?: "default"} is not HEVC ${targetProfile.label}"
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
                rejected += "$prefix tx-mode=$txMode is not supported by v6.1.0"
                return@forEach
            }
            if (localCapability == null) {
                rejected += "$prefix has no bound local production HEVC ${targetProfile.label} decoder"
                return@forEach
            }
            if (localCapability.profile != targetProfile || localCapability.tier != GfnHevcTier.High) {
                rejected += "$prefix local decoder is not ${targetProfile.label}/High"
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
            targetProfile = targetProfile,
            compatiblePayloadTypes = compatible.distinct(),
            rejectedCandidates = rejected,
            localCapability = localCapability,
            streamSupport = streamSupport,
        )
    }
}

internal object GfnHevcCodecPreferencePlanner {
    /**
     * Only explicit local H265 capabilities matching the requested profile and an actual remote
     * High-Tier/SRST candidate are placed ahead of H264. Generic H265 is intentionally excluded.
     */
    fun build(
        capabilities: List<RtpCapabilities.CodecCapability>,
        remoteCodecs: List<VideoCodecDescription>,
        targetProfile: GfnHevcProfile,
    ): GfnVideoCodecPreferencePlan {
        val compatibleHevc = capabilities.filter { local ->
            normalize(local.name) == "H265" && remoteCodecs.any { remote ->
                isCompatibleHevcProfile(local, remote, targetProfile)
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
            targetProfile = targetProfile,
            orderedCapabilities = ordered,
            orderedLabels = ordered.map(::capabilityLabel),
            compatibleHevcCount = compatibleHevc.size,
            h264Count = h264.size,
            auxiliaryCount = auxiliary.size,
        )
    }

    internal fun isCompatibleHevcProfile(
        local: RtpCapabilities.CodecCapability,
        remote: VideoCodecDescription,
        targetProfile: GfnHevcProfile,
    ): Boolean {
        if (normalize(local.name) != "H265" || remote.normalizedName != "H265") return false
        val localParameters = local.parameters.orEmpty()
        val localProfile = localParameters.parameter("profile-id")
        val localTier = localParameters.parameter("tier-flag")
        val localLevel = GfnHevcLevel.fromSdpLevelId(localParameters.parameter("level-id"))
        val remoteLevel = GfnHevcLevel.fromSdpLevelId(remote.levelId)
        if (localProfile != targetProfile.sdpProfileId) return false
        if (localTier != GfnHevcTier.High.sdpTierFlag) return false
        if (remote.profileId != targetProfile.sdpProfileId) return false
        if (remote.tierFlag != GfnHevcTier.High.sdpTierFlag) return false
        if (localLevel == null || remoteLevel == null || remoteLevel.rank > localLevel.rank) return false
        val localTxMode = localParameters.parameter("tx-mode")?.trim()?.uppercase() ?: "SRST"
        val remoteTxMode = remote.txMode?.trim()?.uppercase() ?: "SRST"
        return localTxMode == remoteTxMode
    }

    internal fun isCompatibleHevcMain(
        local: RtpCapabilities.CodecCapability,
        remote: VideoCodecDescription,
    ): Boolean = isCompatibleHevcProfile(local, remote, GfnHevcProfile.Main)

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
        requestedColorMode: RequestedColorMode,
        targetProfile: GfnHevcProfile,
        decoderCapabilities: List<GfnVideoCodecCapabilitySnapshot>,
        receiverCapabilities: List<GfnVideoCodecCapabilitySnapshot>,
        probeResult: GfnHevcDecoderProbeResult,
        mainCapability: GfnHevcDecoderCapability?,
        mainAdvertisementReason: String,
        main10Capability: GfnHevcDecoderCapability?,
        main10AdvertisementReason: String,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=SESSION requested=${requestedCodec.name} color=${requestedColorMode.name} " +
                "targetProfile=${targetProfile.sdpProfileId} main10=${targetProfile == GfnHevcProfile.Main10} " +
                "hdr=false decoderCaps=${decoderCapabilities.size} receiverCaps=${receiverCapabilities.size}",
        )
        probeResult.candidates.forEachIndexed { index, capability ->
            Log.i(TAG, hevcDecoderCapabilityLogLine(generation, "HEVC_DECODER_CANDIDATE", index, capability))
        }
        probeResult.errors.forEachIndexed { index, error ->
            Log.i(TAG, "gen=$generation phase=HEVC_DECODER_PROBE_ERROR index=$index error=${quote(error)}")
        }
        advertisement(
            generation = generation,
            phase = "HEVC_PRODUCTION_ADVERTISEMENT",
            capability = mainCapability,
            reason = mainAdvertisementReason,
        )
        advertisement(
            generation = generation,
            phase = "HEVC_MAIN10_ADVERTISEMENT",
            capability = main10Capability,
            reason = main10AdvertisementReason,
        )
        decoderCapabilities.forEach { capability ->
            Log.i(TAG, capabilityLogLine(generation, "LOCAL_DECODER", capability))
        }
        receiverCapabilities.forEach { capability ->
            Log.i(TAG, capabilityLogLine(generation, "LOCAL_RECEIVER", capability))
        }
    }

    private fun advertisement(
        generation: Long,
        phase: String,
        capability: GfnHevcDecoderCapability?,
        reason: String,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=$phase enabled=${capability != null} " +
                "decoder=${capability?.codecName ?: "-"} " +
                "profile=${capability?.profile?.sdpProfileId ?: "-"} " +
                "tier=${capability?.tier?.sdpTierFlag ?: "-"} " +
                "level=${capability?.maxLevel?.sdpLevelId ?: "-"} " +
                "reason=${quote(reason)}",
        )
    }

    fun offerCompatibility(generation: Long, result: GfnHevcOfferCompatibility) {
        val local = result.localCapability
        Log.i(
            TAG,
            "gen=$generation phase=OFFER_HEVC_COMPATIBLE targetProfile=${result.targetProfile.sdpProfileId} " +
                "compatible=${result.compatible} matched=${result.compatiblePayloadTypes} " +
                "decoder=${local?.codecName ?: "-"} profile=${local?.profile?.sdpProfileId ?: "-"} " +
                "tier=${local?.tier?.sdpTierFlag ?: "-"} maxLevel=${local?.maxLevel?.sdpLevelId ?: "-"} " +
                "streamSafe=${result.streamSupport.supported} reason=${quote(result.reason)}",
        )
        result.rejectedCandidates.forEachIndexed { index, reason ->
            Log.i(
                TAG,
                "gen=$generation phase=OFFER_HEVC_REJECT targetProfile=${result.targetProfile.sdpProfileId} " +
                    "index=$index reason=${quote(reason)}",
            )
        }
    }

    fun sdp(generation: Long, phase: String, sdp: String) {
        val summary = GfnSdpTools.summarize(sdp, isOffer = phase.startsWith("OFFER"))
        Log.i(
            TAG,
            "gen=$generation phase=$phase mlinePts=${GfnSdpTools.firstVideoPayloadOrder(sdp)} " +
                "h264=${summary.h264PayloadTypes} hevc=${summary.hevcPayloadTypes} " +
                "hevcMain=${summary.hevcMainPayloadTypes} hevcMain10=${summary.hevcMain10PayloadTypes}",
        )
        GfnSdpTools.firstVideoCodecDetails(sdp).forEach { codec ->
            Log.i(TAG, codecLogLine(generation, phase, codec))
        }
    }

    fun answerHevcProfileLineage(
        generation: Long,
        stage: String,
        targetProfile: GfnHevcProfile,
        offerProfilePayloadTypes: List<Int>,
        answerHevcPayloadTypes: List<Int>,
        matchedPayloadTypes: List<Int>,
    ) {
        val phase = when (targetProfile) {
            GfnHevcProfile.Main -> "ANSWER_HEVC_MAIN_LINEAGE"
            GfnHevcProfile.Main10 -> "ANSWER_HEVC_MAIN10_LINEAGE"
        }
        Log.i(
            TAG,
            "gen=$generation phase=$phase stage=$stage targetProfile=${targetProfile.sdpProfileId} " +
                "offerProfile=$offerProfilePayloadTypes answerHevc=$answerHevcPayloadTypes matched=$matchedPayloadTypes",
        )
    }

    fun preferencePlan(generation: Long, plan: GfnVideoCodecPreferencePlan) {
        Log.i(
            TAG,
            "gen=$generation phase=PREFERENCE_PLAN targetProfile=${plan.targetProfile.sdpProfileId} " +
                "compatibleHevc=${plan.compatibleHevcCount} h264=${plan.h264Count} " +
                "aux=${plan.auxiliaryCount} count=${plan.orderedLabels.size}",
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
        targetProfile: GfnHevcProfile? = null,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=DECISION stage=$stage requested=${requested.name} " +
                "targetProfile=${targetProfile?.sdpProfileId ?: "-"} effective=${effective.name} " +
                "fallback=${fallbackReason != null} reason=${quote(fallbackReason ?: "-")}",
        )
    }

    fun nvstConfig(
        generation: Long,
        colorMode: RequestedColorMode,
        bitDepth: Int,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=NVST_CONFIG color=${colorMode.name} bitDepth=$bitDepth hdr=false",
        )
    }

    fun milestone(
        generation: Long,
        stage: String,
        effective: VideoCodecPreference,
        detail: String? = null,
        targetProfile: GfnHevcProfile? = null,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=MEDIA stage=$stage effective=${effective.name} " +
                "targetProfile=${targetProfile?.sdpProfileId ?: "-"} detail=${quote(detail ?: "-")}",
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
