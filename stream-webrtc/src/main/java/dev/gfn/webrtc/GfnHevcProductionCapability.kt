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

internal enum class GfnHevcProfile(val sdpProfileId: String, val label: String) {
    Main("1", "Main"),
    Main10("2", "Main10"),
    ;

    companion object {
        fun fromSdpProfileId(value: String?): GfnHevcProfile? =
            entries.firstOrNull { it.sdpProfileId == value?.trim() }
    }
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

/**
 * v6.0.4 keeps [selected] as the production Main capability. v6.1.0 adds [selectedMain10]
 * independently; Main success is never used to infer Main10 support.
 */
internal data class GfnHevcDecoderProbeResult(
    val candidates: List<GfnHevcDecoderCapability>,
    val selected: GfnHevcDecoderCapability?,
    val errors: List<String>,
    val selectedMain10: GfnHevcDecoderCapability? = null,
) {
    fun selectedFor(profile: GfnHevcProfile): GfnHevcDecoderCapability? = when (profile) {
        GfnHevcProfile.Main -> selected
        GfnHevcProfile.Main10 -> selectedMain10
    }
}

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

    private data class AndroidProfile(
        val profile: GfnHevcProfile,
        val androidProfile: Int,
    )

    private val probedProfiles = listOf(
        AndroidProfile(GfnHevcProfile.Main, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
        AndroidProfile(GfnHevcProfile.Main10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
    )

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
                selectedMain10 = null,
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
            val videoCaps: MediaCodecInfo.VideoCapabilities? = caps.videoCapabilities
            if (videoCaps == null) {
                errors += "${info.name}: HEVC videoCapabilities unavailable"
            }
            val supports1080p60 = videoCaps?.let { capabilities ->
                runCatching {
                    capabilities.areSizeAndRateSupported(
                        productionWidth,
                        productionHeight,
                        productionFps.toDouble(),
                    )
                }.getOrDefault(false)
            } ?: false
            val bitrateRangeKbps = videoCaps?.let { capabilities ->
                runCatching {
                    val range = capabilities.bitrateRange
                    (range.lower / 1_000)..(range.upper / 1_000)
                }.getOrNull()
            }

            var foundRecognizedProfile = false
            probedProfiles.forEach profileLoop@ { target ->
                val profileLevels = caps.profileLevels
                    .asSequence()
                    .filter { it.profile == target.androidProfile }
                    .mapNotNull { GfnHevcAndroidLevelMapper.normalize(it.level) }
                    .toList()
                if (profileLevels.isEmpty()) return@profileLoop
                foundRecognizedProfile = true
                val highestHigh = profileLevels
                    .filter { it.tier == GfnHevcTier.High }
                    .maxByOrNull { it.level.rank }
                val highestMain = profileLevels
                    .filter { it.tier == GfnHevcTier.Main }
                    .maxByOrNull { it.level.rank }
                val best = highestHigh ?: highestMain ?: return@profileLoop
                candidates += GfnHevcDecoderCapability(
                    codecName = info.name,
                    profile = target.profile,
                    tier = best.tier,
                    maxLevel = best.level,
                    hardwareAccelerated = info.isHardwareAccelerated,
                    supports1080p60 = supports1080p60,
                    bitrateRangeKbps = bitrateRangeKbps,
                )
            }
            if (!foundRecognizedProfile) {
                errors += "${info.name}: HEVC Main/Main10 has no recognized tier/level"
            }
        }

        val selectedMain = selectProductionCandidate(candidates, GfnHevcProfile.Main)
        val selectedMain10 = selectProductionCandidate(candidates, GfnHevcProfile.Main10)
        return GfnHevcDecoderProbeResult(
            candidates = candidates,
            selected = selectedMain,
            errors = errors,
            selectedMain10 = selectedMain10,
        )
    }

    private fun selectProductionCandidate(
        candidates: List<GfnHevcDecoderCapability>,
        profile: GfnHevcProfile,
    ): GfnHevcDecoderCapability? = candidates.firstOrNull { candidate ->
        candidate.profile == profile &&
            candidate.hardwareAccelerated &&
            candidate.tier == GfnHevcTier.High &&
            candidate.maxLevel.rank >= GfnHevcLevel.Level51.rank &&
            candidate.supports1080p60
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
        val videoCaps: MediaCodecInfo.VideoCapabilities = caps.videoCapabilities
            ?: return GfnHevcStreamSupport(
                supported = false,
                sizeAndRateSupported = false,
                bitrateSupported = false,
                bitrateRangeKbps = null,
                reason = "${capability.codecName} video capabilities unavailable",
            )
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
 * Production HEVC decoder factory for v6.0.4 Main and v6.1.0 Main10 negotiation.
 *
 * Main and Main10 are probed independently. Each explicit SDP capability is bound to the exact
 * MediaCodec component that proved that profile. Main10 is never inferred from Main support.
 */
internal class GfnHevcAwareVideoDecoderFactory(
    sharedContext: EglBase.Context?,
    val probeResult: GfnHevcDecoderProbeResult = GfnHevcDecoderCapabilityProbe.probe(),
) : VideoDecoderFactory {
    private val fallbackFactory = DefaultVideoDecoderFactory(sharedContext)

    private val probedMainCapability = probeResult.selectedFor(GfnHevcProfile.Main)
    private val probedMain10Capability = probeResult.selectedFor(GfnHevcProfile.Main10)

    private val boundMainFactory = probedMainCapability?.let { capability ->
        hardwareFactoryFor(sharedContext, capability)
    }
    private val boundMain10Factory = probedMain10Capability?.let { capability ->
        hardwareFactoryFor(sharedContext, capability)
    }

    val productionCapability: GfnHevcDecoderCapability? =
        probedMainCapability?.takeIf { boundMainFactory.hasH265() }

    val main10ProductionCapability: GfnHevcDecoderCapability? =
        probedMain10Capability?.takeIf { boundMain10Factory.hasH265() }

    val productionCapabilities: List<GfnHevcDecoderCapability> =
        listOfNotNull(productionCapability, main10ProductionCapability)

    val advertisementReason: String = advertisementReasonFor(
        profile = GfnHevcProfile.Main,
        probed = probedMainCapability,
        advertised = productionCapability,
    )

    val main10AdvertisementReason: String = advertisementReasonFor(
        profile = GfnHevcProfile.Main10,
        probed = probedMain10Capability,
        advertised = main10ProductionCapability,
    )

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val result = mutableListOf<VideoCodecInfo>()
        var insertedExplicitH265 = false
        fallbackFactory.getSupportedCodecs().forEach { codec ->
            if (normalizeCodecName(codec.name) == "H265") {
                if (!insertedExplicitH265) {
                    productionCapabilities.forEach { capability ->
                        result += capability.toVideoCodecInfo()
                    }
                    insertedExplicitH265 = true
                }
            } else {
                result += codec
            }
        }
        if (!insertedExplicitH265) {
            productionCapabilities.forEach { capability ->
                result += capability.toVideoCodecInfo()
            }
        }
        return result.toTypedArray()
    }

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
        if (normalizeCodecName(info.name) != "H265") return fallbackFactory.createDecoder(info)
        val profile = GfnHevcProfile.fromSdpProfileId(info.params.orEmpty().parameter("profile-id"))
        val binding = when (profile) {
            GfnHevcProfile.Main -> explicitHevcBinding(
                capability = productionCapability,
                factory = boundMainFactory,
            )
            GfnHevcProfile.Main10 -> explicitHevcBinding(
                capability = main10ProductionCapability,
                factory = boundMain10Factory,
            )
            null -> genericHevcBinding()
        } ?: return null
        val decoder = binding.factory.createDecoder(info) ?: return null
        return GfnHevcBitstreamProbeVideoDecoder(
            delegate = decoder,
            decoderComponent = binding.decoderComponent,
            expectedProfile = binding.expectedProfile,
        )
    }

    private data class HevcDecoderBinding(
        val factory: HardwareVideoDecoderFactory,
        val decoderComponent: String,
        val expectedProfile: GfnHevcProfile?,
    )

    private fun explicitHevcBinding(
        capability: GfnHevcDecoderCapability?,
        factory: HardwareVideoDecoderFactory?,
    ): HevcDecoderBinding? {
        if (capability == null || factory == null) return null
        return HevcDecoderBinding(
            factory = factory,
            decoderComponent = capability.codecName,
            expectedProfile = capability.profile,
        )
    }

    /**
     * Some libwebrtc paths can request an H265 decoder without carrying profile-id back to Java.
     * Never guess Main when Main/Main10 were proven by different components. A generic request is
     * safe only when exactly one HEVC production capability exists or both profiles resolve to the
     * same MediaCodec component. If both profiles share one component, the forensic wrapper labels
     * the Java request as generic instead of inventing a profile; the SPS provides the actual proof.
     */
    private fun genericHevcBinding(): HevcDecoderBinding? = when {
        productionCapability != null && main10ProductionCapability == null ->
            explicitHevcBinding(productionCapability, boundMainFactory)
        productionCapability == null && main10ProductionCapability != null ->
            explicitHevcBinding(main10ProductionCapability, boundMain10Factory)
        productionCapability != null && main10ProductionCapability != null &&
            productionCapability.codecName == main10ProductionCapability.codecName && boundMainFactory != null ->
            HevcDecoderBinding(
                factory = boundMainFactory,
                decoderComponent = productionCapability.codecName,
                expectedProfile = null,
            )
        else -> null
    }

    private fun hardwareFactoryFor(
        sharedContext: EglBase.Context?,
        capability: GfnHevcDecoderCapability,
    ): HardwareVideoDecoderFactory = HardwareVideoDecoderFactory(
        sharedContext,
        Predicate<MediaCodecInfo> { info -> info.name == capability.codecName },
    )

    private fun HardwareVideoDecoderFactory?.hasH265(): Boolean =
        this?.getSupportedCodecs()?.any { normalizeCodecName(it.name) == "H265" } ?: false

    private fun GfnHevcDecoderCapability.toVideoCodecInfo(): VideoCodecInfo =
        VideoCodecInfo("H265", sdpParameters, emptyList())

    private fun advertisementReasonFor(
        profile: GfnHevcProfile,
        probed: GfnHevcDecoderCapability?,
        advertised: GfnHevcDecoderCapability?,
    ): String = when {
        probed == null ->
            "no hardware HEVC ${profile.label} High-Tier Level>=5.1 decoder with 1080p60 capability"
        advertised == null ->
            "bound WebRTC HardwareVideoDecoderFactory rejected ${probed.codecName} for ${profile.label}"
        else ->
            "advertising ${advertised.codecName} as H265 ${profile.label}/High level ${advertised.maxLevel.label}"
    }

    private fun Map<String, String>.parameter(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun normalizeCodecName(value: String): String = when (value.trim().uppercase()) {
        "HEVC" -> "H265"
        else -> value.trim().uppercase()
    }
}
