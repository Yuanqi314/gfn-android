package dev.gfn.webrtc

import android.util.Log
import dev.gfn.signaling.GfnSdpTools
import dev.gfn.signaling.HevcTierFlagRewriteResult
import dev.gfn.signaling.VideoCodecDescription
import dev.gfn.stream.VideoCodecPreference
import org.webrtc.RtpCapabilities

/**
 * v6.0.2 HEVC Main tier-only A/B evidence + pre-createAnswer preference planning.
 *
 * Important boundary: dynamic SDP payload numbers are only diagnostics. Codec compatibility is
 * classified by codec name / fmtp parameters; PT equality is never used as a compatibility test.
 */
internal data class GfnVideoCodecPreferencePlan(
    val orderedCapabilities: List<RtpCapabilities.CodecCapability>,
    val orderedLabels: List<String>,
    val explicitHevcMainCount: Int,
    val genericHevcCount: Int,
    val h264Count: Int,
    val auxiliaryCount: Int,
) {
    val hasHevcCandidate: Boolean get() = explicitHevcMainCount + genericHevcCount > 0
}

internal object GfnHevcCodecPreferencePlanner {
    private val auxiliaryCodecNames = setOf("RTX", "RED", "ULPFEC", "FLEXFEC-03")

    /**
     * Order only the codecs needed by the v6.0.2 experiment:
     * explicit H265 Main -> generic H265 -> H264 fallback -> repair/RTX codecs.
     *
     * Explicit non-Main H265 (for example profile-id=2/Main10), AV1, VP8 and VP9 are excluded so
     * this experiment cannot silently widen into a second codec/bit-depth variable.
     */
    fun build(
        capabilities: List<RtpCapabilities.CodecCapability>,
    ): GfnVideoCodecPreferencePlan {
        val explicitMain = capabilities.filter { codec ->
            normalize(codec.name) == "H265" && codec.parameters.orEmpty().parameter("profile-id") == "1"
        }
        val genericHevc = capabilities.filter { codec ->
            normalize(codec.name) == "H265" && codec.parameters.orEmpty().parameter("profile-id") == null
        }
        val h264 = capabilities.filter { normalize(it.name) == "H264" }
        val primary = explicitMain + genericHevc + h264
        // RTX apt is meaningful only inside this *local receiver capability snapshot*. It is used
        // solely to avoid carrying an RTX entry whose local primary codec was deliberately
        // excluded (for example profile-id=2). It is never compared with a remote Offer PT.
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
            explicitHevcMainCount = explicitMain.size,
            genericHevcCount = genericHevc.size,
            h264Count = h264.size,
            auxiliaryCount = auxiliary.size,
        )
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
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=SESSION requested=${requestedCodec.name} color=SDR8 " +
                "main10=false hdr=false decoderCaps=${decoderCapabilities.size} receiverCaps=${receiverCapabilities.size}",
        )
        decoderCapabilities.forEach { capability ->
            Log.i(TAG, capabilityLogLine(generation, "LOCAL_DECODER", capability))
        }
        receiverCapabilities.forEach { capability ->
            Log.i(TAG, capabilityLogLine(generation, "LOCAL_RECEIVER", capability))
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

    fun tierFlagAbRewrite(
        generation: Long,
        result: HevcTierFlagRewriteResult,
    ) {
        Log.i(
            TAG,
            "gen=$generation phase=OFFER_TIER_AB_REWRITE applied=${result.changed} " +
                "from=${result.fromTierFlag} to=${result.toTierFlag} " +
                "candidates=${result.candidatePayloadTypes} rewritten=${result.rewrittenPayloadTypes}",
        )
    }

    fun preferencePlan(generation: Long, plan: GfnVideoCodecPreferencePlan) {
        Log.i(
            TAG,
            "gen=$generation phase=PREFERENCE_PLAN explicitMain=${plan.explicitHevcMainCount} " +
                "genericHevc=${plan.genericHevcCount} h264=${plan.h264Count} aux=${plan.auxiliaryCount} " +
                "count=${plan.orderedLabels.size}",
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
