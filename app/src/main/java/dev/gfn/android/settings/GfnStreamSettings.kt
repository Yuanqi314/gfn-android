package dev.gfn.android.settings

import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.stream.StreamCapabilityProfiles
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.StreamResolution
import dev.gfn.stream.VideoCodecPreference

data class StreamResolutionChoice(
    val code: String,
    val label: String,
    val width: Int? = null,
    val height: Int? = null,
    val automatic: Boolean = false,
)

data class StreamFpsChoice(
    val fps: Int,
    val label: String,
    val automatic: Boolean = false,
)

data class StreamAudioChoice(
    val channels: Int,
    val label: String,
)

data class StreamCodecChoice(
    val codec: VideoCodecPreference,
    val label: String,
)

/**
 * Persistent user intent. Values here are never read directly by the live WebRTC engine.
 * A new Session resolves this object exactly once into [ResolvedLaunchProfile].
 */
data class PersistentStreamSettings(
    val keyboardLayoutSelection: String = GfnKeyboardLayoutCatalog.DEFAULT,
    val resolutionSelection: String = GfnStreamSettingsCatalog.RESOLUTION_AUTO,
    val fpsSelection: Int = GfnStreamSettingsCatalog.FPS_AUTO,
    val maxBitrateKbps: Int = GfnStreamSettingsCatalog.DEFAULT_MAX_BITRATE_KBPS,
    val videoCodec: VideoCodecPreference = VideoCodecPreference.H264,
    val audioChannels: Int = GfnStreamSettingsCatalog.DEFAULT_AUDIO_CHANNELS,
)

/** Immutable Session/WebRTC launch snapshot. */
data class ResolvedLaunchProfile(
    val streamConfig: StreamConfig,
    val keyboardLayout: String,
    val gameLanguage: String,
    val entitlementVerified: Boolean,
) {
    val summary: String
        get() = "${streamConfig.width}x${streamConfig.height}@${streamConfig.fps} " +
            "${streamConfig.maxBitrateKbps / 1_000}Mbps codec=${streamConfig.codec} audio=${streamConfig.audioChannels}ch " +
            "keyboard=$keyboardLayout language=$gameLanguage"
}

class StreamProfileResolutionException(message: String) : IllegalArgumentException(message)

object GfnStreamSettingsCatalog {
    const val RESOLUTION_AUTO = "auto"
    const val FPS_AUTO = 0
    const val DEFAULT_MAX_BITRATE_KBPS = 20_000
    const val DEFAULT_AUDIO_CHANNELS = 2
    const val BITRATE_STEP_KBPS = 5_000

    private val capabilities = StreamCapabilityProfiles.V60_ANDROID_WEBRTC

    val resolutionChoices: List<StreamResolutionChoice> = buildList {
        add(StreamResolutionChoice(RESOLUTION_AUTO, "自动（按账号能力）", automatic = true))
        capabilities.resolutions
            .sortedWith(compareByDescending<StreamResolution> { it.width * it.height }.thenByDescending { it.width })
            .forEach { resolution ->
                add(
                    StreamResolutionChoice(
                        code = "${resolution.width}x${resolution.height}",
                        label = "${resolution.width} × ${resolution.height}",
                        width = resolution.width,
                        height = resolution.height,
                    ),
                )
            }
    }

    val fpsChoices: List<StreamFpsChoice> = buildList {
        add(StreamFpsChoice(FPS_AUTO, "自动（按当前引擎）", automatic = true))
        capabilities.frameRates.sortedDescending().forEach { add(StreamFpsChoice(it, "$it FPS")) }
    }

    val codecChoices: List<StreamCodecChoice> = listOf(
        StreamCodecChoice(VideoCodecPreference.H264, "H.264 · SDR8（稳定 fallback）"),
        StreamCodecChoice(VideoCodecPreference.Hevc, "HEVC Main · SDR8"),
    ).filter { it.codec in capabilities.codecs }

    val audioChoices: List<StreamAudioChoice> =
        capabilities.audioChannels.sorted().map { channels ->
            StreamAudioChoice(
                channels = channels,
                label = when (channels) {
                    2 -> "Stereo · 2ch（ADM stereo）"
                    6 -> "5.1 / 6ch（实验：multiopus；ADM 仍 2ch）"
                    else -> "$channels channels"
                },
            )
        }

    val nativeAudioOutputChannels: Set<Int> = capabilities.nativeAudioOutputChannels

    val bitrateRangeKbps: IntRange = capabilities.maxBitrateKbpsRange

    fun normalize(settings: PersistentStreamSettings): PersistentStreamSettings =
        settings.copy(
            keyboardLayoutSelection = GfnKeyboardLayoutCatalog.normalize(settings.keyboardLayoutSelection),
            resolutionSelection = normalizeResolution(settings.resolutionSelection),
            fpsSelection = normalizeFps(settings.fpsSelection),
            maxBitrateKbps = normalizeBitrate(settings.maxBitrateKbps),
            videoCodec = normalizeVideoCodec(settings.videoCodec),
            audioChannels = normalizeAudio(settings.audioChannels),
        )

    fun normalizeResolution(value: String?): String =
        value?.takeIf { candidate -> resolutionChoices.any { it.code == candidate } } ?: RESOLUTION_AUTO

    fun normalizeFps(value: Int): Int =
        value.takeIf { candidate -> fpsChoices.any { it.fps == candidate } } ?: FPS_AUTO

    fun normalizeVideoCodec(value: VideoCodecPreference): VideoCodecPreference =
        value.takeIf { candidate -> codecChoices.any { it.codec == candidate } } ?: VideoCodecPreference.H264

    fun normalizeAudio(value: Int): Int =
        value.takeIf { candidate -> audioChoices.any { it.channels == candidate } } ?: DEFAULT_AUDIO_CHANNELS

    fun normalizeBitrate(value: Int): Int {
        val clamped = value.coerceIn(bitrateRangeKbps.first, bitrateRangeKbps.last)
        val steps = (clamped + BITRATE_STEP_KBPS / 2) / BITRATE_STEP_KBPS
        return (steps * BITRATE_STEP_KBPS).coerceIn(bitrateRangeKbps.first, bitrateRangeKbps.last)
    }
}

object GfnStreamSettingsResolver {
    private val capabilities = StreamCapabilityProfiles.V60_ANDROID_WEBRTC

    fun resolve(
        persistent: PersistentStreamSettings,
        subscription: SubscriptionInfo,
        autoKeyboardLayout: String,
        gameLanguage: String,
    ): ResolvedLaunchProfile {
        val settings = GfnStreamSettingsCatalog.normalize(persistent)
        val keyboardLayout = if (settings.keyboardLayoutSelection == GfnKeyboardLayoutCatalog.AUTO) {
            autoKeyboardLayout.takeIf { it.isNotBlank() } ?: GfnKeyboardLayoutCatalog.DEFAULT
        } else {
            settings.keyboardLayoutSelection
        }

        val entitlementKnown = subscription.entitledResolutions.isNotEmpty()
        val resolution = resolveResolution(settings, subscription, entitlementKnown)
        val fps = resolveFps(settings, subscription, resolution, entitlementKnown)

        if (entitlementKnown && subscription.entitledResolutions.none {
                it.width == resolution.width && it.height == resolution.height && it.fps >= fps
            }
        ) {
            throw StreamProfileResolutionException(
                "账号 entitlement 未包含 ${resolution.width}x${resolution.height}@$fps；" +
                    "v6.0.2 当前 Android WebRTC 引擎只开放 1080p60 SDR8。",
            )
        }

        val streamConfig = StreamConfig(
            width = resolution.width,
            height = resolution.height,
            fps = fps,
            maxBitrateKbps = settings.maxBitrateKbps,
            codec = settings.videoCodec,
            colorMode = RequestedColorMode.CompatibilitySdr,
            audioChannels = settings.audioChannels,
        )
        capabilities.rejectionReason(streamConfig)?.let { reason ->
            throw StreamProfileResolutionException(reason)
        }

        return ResolvedLaunchProfile(
            streamConfig = streamConfig,
            keyboardLayout = keyboardLayout,
            gameLanguage = gameLanguage,
            entitlementVerified = entitlementKnown,
        )
    }

    private fun resolveResolution(
        settings: PersistentStreamSettings,
        subscription: SubscriptionInfo,
        entitlementKnown: Boolean,
    ): StreamResolution {
        val explicit = GfnStreamSettingsCatalog.resolutionChoices
            .firstOrNull { it.code == settings.resolutionSelection && !it.automatic }
            ?.let { choice -> StreamResolution(requireNotNull(choice.width), requireNotNull(choice.height)) }
        if (explicit != null) return explicit

        val candidates = capabilities.resolutions.sortedByDescending { it.width * it.height }
        if (!entitlementKnown) return candidates.first()
        return candidates.firstOrNull { candidate ->
            subscription.entitledResolutions.any {
                it.width == candidate.width && it.height == candidate.height &&
                    capabilities.frameRates.any { fps -> it.fps >= fps }
            }
        } ?: throw StreamProfileResolutionException(
            "账号 entitlement 与 v6.0.2 当前引擎能力没有交集；当前仅开放 1920x1080@60。",
        )
    }

    private fun resolveFps(
        settings: PersistentStreamSettings,
        subscription: SubscriptionInfo,
        resolution: StreamResolution,
        entitlementKnown: Boolean,
    ): Int {
        if (settings.fpsSelection != GfnStreamSettingsCatalog.FPS_AUTO) return settings.fpsSelection
        val candidates = capabilities.frameRates.sortedDescending()
        if (!entitlementKnown) return candidates.first()
        return candidates.firstOrNull { fps ->
            subscription.entitledResolutions.any {
                it.width == resolution.width && it.height == resolution.height && it.fps >= fps
            }
        } ?: throw StreamProfileResolutionException(
            "账号 entitlement 没有 ${resolution.width}x${resolution.height} 的可用 FPS。",
        )
    }
}
