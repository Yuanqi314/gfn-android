package dev.gfn.android.settings

import android.util.Log
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.stream.VideoCodecPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GfnStreamSettingsController(
    private val store: AndroidStreamSettingsStore,
) {
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<PersistentStreamSettings> = _settings.asStateFlow()

    fun setKeyboardLayout(selection: String) = update("keyboardLayout") {
        copy(keyboardLayoutSelection = selection)
    }

    fun setResolution(selection: String) = update("resolution") {
        copy(resolutionSelection = selection)
    }

    fun setFps(fps: Int) = update("fps") { copy(fpsSelection = fps) }

    fun setMaxBitrateKbps(kbps: Int) = update("maxBitrate") { copy(maxBitrateKbps = kbps) }

    fun setVideoCodec(codec: VideoCodecPreference) = update("videoCodec") { copy(videoCodec = codec) }

    fun setAudioChannels(channels: Int) = update("audioChannels") { copy(audioChannels = channels) }

    fun resolveForNewSession(
        subscription: SubscriptionInfo,
        autoKeyboardLayout: String,
        gameLanguage: String,
    ): ResolvedLaunchProfile = GfnStreamSettingsResolver.resolve(
        persistent = _settings.value,
        subscription = subscription,
        autoKeyboardLayout = autoKeyboardLayout,
        gameLanguage = gameLanguage,
    )

    private fun update(reason: String, transform: PersistentStreamSettings.() -> PersistentStreamSettings) {
        val previous = _settings.value
        val next = store.save(previous.transform())
        if (next == previous) return
        _settings.value = next
        Log.i(TAG, "nextSession=true reason=$reason settings=$next")
    }

    private companion object {
        const val TAG = "GfnStreamSettings"
    }
}
