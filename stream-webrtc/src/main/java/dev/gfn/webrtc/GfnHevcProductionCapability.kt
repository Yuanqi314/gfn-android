package dev.gfn.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.Predicate
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory

internal enum class GfnHevcProfile(val sdpProfileId: String) {
    Main("1"),
}

internal enum class GfnHevcTier(val sdpTierFlag: String) {
    Main("0"),
    High("1"),
}

internal enum class GfnHevcLevel(
    val label: String,
    val rank: Int,
    val sdpLevelId: String,
) {
    Level1("1", 10, "30"),
    Level2("2", 20, "60"),
    Level21("2.1", 21, "63"),
    Level3("3", 30, "90"),
    Level31("3.1", 31, "93"),
    Level4("4", 40, "120"),
    Level41("4.1", 41, "123"),
    Level5("5", 50, "150"),
    Level51("5.1", 51, "153"),
    Level52("5.2", 52, "156"),
    Level6("6", 60, "180"),
    Level61("6.1", 61, "183"),
    Level62("6.2", 62, "186"),
    ;

    companion object {
        fun fromSdpLevelId(value: String?): GfnHevcLevel? =
            entries.firstOrNull { it.sdpLevelId == value?.trim() }
    }
}

internal data class GfnHevcDecoderCapability(
    val codecName: String,
    val profile: GfnHevcProfile,
    val tier: GfnHevcTier,
    val maxLevel: GfnHevcLevel,
    val hardwareAccelerated: Boolean,
    val supports1080p60: Boolean,
    val bitrateRangeKbps: IntRange?,
) {
    val sdpParameters: Map<String, String>
        get() = linkedMapOf(
            "profile-id" to profile.sdpProfileId,
            "tier-flag" to tier.sdpTierFlag,
            "level-id" to maxLevel.sdpLevelId,
        )
}

internal data class GfnHevcDecoderProbeResult(
    val candidates: List<GfnHevcDecoderCapability>,
    val selected: GfnHevcDecoderCapability?,
    val errors: List<String>,
)

internal data class GfnHevcStreamSupport(
    val supported: Boolean,
    val sizeAndRateSupported: Boolean,
    val bitrateSupported: Boolean,
    val bitrateRangeKbps: IntRange?,
    val reason: String,
)

internal object GfnHevcAndroidLevelMapper {
    data class NormalizedLevel(
        val tier: GfnHevcTier,
        val level: GfnHevcLevel,
    )

    /**
     * Android HEVC level constants are a bit-mask namespace, not a linear numeric rank. Every
     * supported constant is mapped explicitly before compatibility comparisons are performed.
     */
    fun normalize(androidLevel: Int): NormalizedLevel? = when (androidLevel) {
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level1)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level2)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level21)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level3)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level31)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level4)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level41)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level5)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level51)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level52)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level6)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level61)
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62 -> normalized(GfnHevcTier.Main, GfnHevcLevel.Level62)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel1 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level1)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel2 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level2)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel21 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level21)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level3)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level31)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level4)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level41)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level5)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level51)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level52)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level6)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level61)
        MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62 -> normalized(GfnHevcTier.High, GfnHevcLevel.Level62)
        else -> null
    }

    private fun normalized(tier: GfnHevcTier, level: GfnHevcLevel) = NormalizedLevel(tier, level)
}

internal object GfnHevcDecoderCapabilityProbe {
    private const val productionWidth = 1920
    private const val productionHeight = 1080
    private const val productionFps = 60

    fun probe(): GfnHevcDecoderProbeResult {
        val errors = mutableListOf<String>()
        val candidates = mutableListOf<GfnHevcDecoderCapability>()
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        }.getOrElse { error ->
            return GfnHevcDecoderProbeResult(
                candidates = emptyList(),
                selected = null,
                errors = listOf("MediaCodecList failed: ${error.message ?: error.javaClass.simpleName}"),
            )
        }

        codecInfos.forEach { info ->
            if (info.isEncoder) return@forEach
            if (info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }) {
                return@forEach
            }
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC) }
                .getOrElse { error ->
                    errors += "${info.name}: getCapabilitiesForType failed: ${error.message ?: error.javaClass.simpleName}"
                    return@forEach
                }
            val mainLevels = caps.profileLevels
                .asSequence()
                .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
                .mapNotNull { GfnHevcAndroidLevelMapper.normalize(it.level) }
                .toList()
            if (mainLevels.isEmpty()) {
                errors += "${info.name}: HEVCProfileMain has no recognized tier/level"
                return@forEach
            }
            val highestHigh = mainLevels
                .filter { it.tier == GfnHevcTier.High }
                .maxByOrNull { it.level.rank }
            val highestMain = mainLevels
                .filter { it.tier == GfnHevcTier.Main }
                .maxByOrNull { it.level.rank }
            val best = highestHigh ?: highestMain ?: return@forEach
            val videoCaps = caps.videoCapabilities
            val supports1080p60 = runCatching {
                videoCaps.areSizeAndRateSupported(
                    productionWidth,
                    productionHeight,
                    productionFps.toDouble(),
                )
            }.getOrDefault(false)
            val bitrateRangeKbps = runCatching {
                val range = videoCaps.bitrateRange
                (range.lower / 1_000)..(range.upper / 1_000)
            }.getOrNull()
            candidates += GfnHevcDecoderCapability(
                codecName = info.name,
                profile = GfnHevcProfile.Main,
                tier = best.tier,
                maxLevel = best.level,
                hardwareAccelerated = info.isHardwareAccelerated,
                supports1080p60 = supports1080p60,
                bitrateRangeKbps = bitrateRangeKbps,
            )
        }

        val selected = candidates.firstOrNull { candidate ->
            candidate.hardwareAccelerated &&
                candidate.tier == GfnHevcTier.High &&
                candidate.maxLevel.rank >= GfnHevcLevel.Level51.rank &&
                candidate.supports1080p60
        }
        return GfnHevcDecoderProbeResult(
            candidates = candidates,
            selected = selected,
            errors = errors,
        )
    }

    fun evaluateStream(
        capability: GfnHevcDecoderCapability,
        width: Int,
        height: Int,
        fps: Int,
        maxBitrateKbps: Int,
    ): GfnHevcStreamSupport {
        val info = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.name == capability.codecName
            }
        }.getOrNull()
            ?: return GfnHevcStreamSupport(
                supported = false,
                sizeAndRateSupported = false,
                bitrateSupported = false,
                bitrateRangeKbps = null,
                reason = "bound decoder ${capability.codecName} is no longer present",
            )
        val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC) }
            .getOrElse { error ->
                return GfnHevcStreamSupport(
                    supported = false,
                    sizeAndRateSupported = false,
                    bitrateSupported = false,
                    bitrateRangeKbps = null,
                    reason = "${capability.codecName} capability query failed: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        val videoCaps = caps.videoCapabilities
        val sizeRateSupported = runCatching {
            videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())
        }.getOrDefault(false)
        val bitrateRangeKbps = runCatching {
            val range = videoCaps.bitrateRange
            (range.lower / 1_000)..(range.upper / 1_000)
        }.getOrNull()
        val bitrateSupported = bitrateRangeKbps?.let { maxBitrateKbps in it } ?: false
        val supported = sizeRateSupported && bitrateSupported
        val reason = when {
            !sizeRateSupported -> "${capability.codecName} rejects ${width}x${height}@${fps}"
            bitrateRangeKbps == null -> "${capability.codecName} bitrate range unavailable"
            !bitrateSupported -> "${capability.codecName} bitrate ${maxBitrateKbps}kbps outside $bitrateRangeKbps"
            else -> "${capability.codecName} accepts ${width}x${height}@${fps} and ${maxBitrateKbps}kbps"
        }
        return GfnHevcStreamSupport(
            supported = supported,
            sizeAndRateSupported = sizeRateSupported,
            bitrateSupported = bitrateSupported,
            bitrateRangeKbps = bitrateRangeKbps,
            reason = reason,
        )
    }
}

/**
 * Production HEVC decoder factory for v6.0.4.
 *
 * Non-H265 codecs keep the upstream DefaultVideoDecoderFactory path. H265 is advertised only when
 * Android reports a concrete hardware decoder with HEVC Main / High Tier / Level >= 5.1 and
 * 1080p60 support. H265 decoder creation is then delegated to a HardwareVideoDecoderFactory whose
 * predicate accepts only that exact MediaCodec component.
 */
internal class GfnHevcAwareVideoDecoderFactory(
    sharedContext: EglBase.Context?,
    val probeResult: GfnHevcDecoderProbeResult = GfnHevcDecoderCapabilityProbe.probe(),
) : VideoDecoderFactory {
    private val fallbackFactory = DefaultVideoDecoderFactory(sharedContext)
    private val probedCapability = probeResult.selected
    private val boundHevcFactory = probedCapability?.let { capability ->
        HardwareVideoDecoderFactory(
            sharedContext,
            Predicate<MediaCodecInfo> { info -> info.name == capability.codecName },
        )
    }
    private val boundFactoryHasH265 = boundHevcFactory?.getSupportedCodecs()
        ?.any { normalizeCodecName(it.name) == "H265" }
        ?: false

    val productionCapability: GfnHevcDecoderCapability? =
        probedCapability?.takeIf { boundFactoryHasH265 }

    val advertisementReason: String = when {
        probedCapability == null ->
            "no hardware HEVC Main High-Tier Level>=5.1 decoder with 1080p60 capability"
        !boundFactoryHasH265 ->
            "bound WebRTC HardwareVideoDecoderFactory rejected ${probedCapability.codecName}"
        else ->
            "advertising ${probedCapability.codecName} as H265 Main/High level ${probedCapability.maxLevel.label}"
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val result = mutableListOf<VideoCodecInfo>()
        var insertedH265 = false
        fallbackFactory.getSupportedCodecs().forEach { codec ->
            if (normalizeCodecName(codec.name) == "H265") {
                if (!insertedH265) {
                    productionCapability?.let { capability ->
                        result += VideoCodecInfo(
                            "H265",
                            capability.sdpParameters,
                            emptyList(),
                        )
                    }
                    insertedH265 = true
                }
            } else {
                result += codec
            }
        }
        if (!insertedH265) {
            productionCapability?.let { capability ->
                result += VideoCodecInfo(
                    "H265",
                    capability.sdpParameters,
                    emptyList(),
                )
            }
        }
        return result.toTypedArray()
    }

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? =
        if (normalizeCodecName(info.name) == "H265") {
            if (productionCapability == null) null else boundHevcFactory?.createDecoder(info)
        } else {
            fallbackFactory.createDecoder(info)
        }

    private fun normalizeCodecName(value: String): String = when (value.trim().uppercase()) {
        "HEVC" -> "H265"
        else -> value.trim().uppercase()
    }
}
