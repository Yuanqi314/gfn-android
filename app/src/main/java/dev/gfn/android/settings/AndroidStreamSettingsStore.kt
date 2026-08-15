package dev.gfn.android.settings

import android.content.Context
import dev.gfn.stream.VideoCodecPreference

/** Persistent next-Session stream settings. Existing v5.1.8 keyboard preference is migrated in-place. */
class AndroidStreamSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersistentStreamSettings = GfnStreamSettingsCatalog.normalize(
        PersistentStreamSettings(
            keyboardLayoutSelection = prefs.getString(KEY_KEYBOARD_LAYOUT, null)
                ?: GfnKeyboardLayoutCatalog.DEFAULT,
            resolutionSelection = prefs.getString(KEY_RESOLUTION, null)
                ?: GfnStreamSettingsCatalog.RESOLUTION_AUTO,
            fpsSelection = prefs.getInt(KEY_FPS, GfnStreamSettingsCatalog.FPS_AUTO),
            maxBitrateKbps = prefs.getInt(
                KEY_MAX_BITRATE,
                GfnStreamSettingsCatalog.DEFAULT_MAX_BITRATE_KBPS,
            ),
            videoCodec = prefs.getString(KEY_VIDEO_CODEC, null)?.let { raw ->
                runCatching { VideoCodecPreference.valueOf(raw) }.getOrNull()
            } ?: VideoCodecPreference.H264,
            audioChannels = prefs.getInt(KEY_AUDIO_CHANNELS, GfnStreamSettingsCatalog.DEFAULT_AUDIO_CHANNELS),
        ),
    )

    fun save(settings: PersistentStreamSettings): PersistentStreamSettings {
        val normalized = GfnStreamSettingsCatalog.normalize(settings)
        prefs.edit()
            .putString(KEY_KEYBOARD_LAYOUT, normalized.keyboardLayoutSelection)
            .putString(KEY_RESOLUTION, normalized.resolutionSelection)
            .putInt(KEY_FPS, normalized.fpsSelection)
            .putInt(KEY_MAX_BITRATE, normalized.maxBitrateKbps)
            .putString(KEY_VIDEO_CODEC, normalized.videoCodec.name)
            .putInt(KEY_AUDIO_CHANNELS, normalized.audioChannels)
            .apply()
        return normalized
    }

    private companion object {
        const val PREFS_NAME = "gfn-stream-settings"
        // Preserve the v5.1.8 key so existing en-US selections migrate without a reset.
        const val KEY_KEYBOARD_LAYOUT = "keyboardLayoutSelection"
        const val KEY_RESOLUTION = "resolutionSelection"
        const val KEY_FPS = "fpsSelection"
        const val KEY_MAX_BITRATE = "maxBitrateKbps"
        const val KEY_VIDEO_CODEC = "videoCodec"
        const val KEY_AUDIO_CHANNELS = "audioChannels"
    }
}
