package dev.gfn.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Best-effort Android output-route diagnostics.
 *
 * This deliberately does NOT claim to identify libwebrtc's final active AudioTrack route. Android's
 * public AudioManager APIs can expose candidate output devices/capabilities, while the actual route
 * can still be changed by policy or OEM audio services after playout starts. The result is therefore
 * diagnostic evidence only and is labelled "likely" throughout the UI.
 */
data class GfnAudioRouteSnapshot(
    val likelyMaxChannels: Int?,
    val summary: String,
)

internal object GfnAndroidAudioRouteProbe {
    private val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun detect(context: Context): GfnAudioRouteSnapshot {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return GfnAudioRouteSnapshot(null, "AudioManager unavailable")

        val devices = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                audioManager.getAudioDevicesForAttributes(mediaAttributes).toTypedArray()
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            }
        }.getOrElse {
            return GfnAudioRouteSnapshot(null, "route query failed: ${it::class.java.simpleName}")
        }

        if (devices.isEmpty()) return GfnAudioRouteSnapshot(null, "no candidate output device")

        val summaries = devices.map { device ->
            val counts = device.channelCounts.filter { it > 0 }
            val maxChannels = counts.maxOrNull()
            val channelText = maxChannels?.let { "${it}ch" } ?: "channels?"
            "${device.typeName()}:$channelText"
        }
        val likelyMax = devices
            .flatMap { it.channelCounts.asIterable() }
            .filter { it > 0 }
            .maxOrNull()

        return GfnAudioRouteSnapshot(
            likelyMaxChannels = likelyMax,
            summary = summaries.distinct().joinToString(", ").ifBlank { "candidate outputs present" },
        )
    }

    private fun AudioDeviceInfo.typeName(): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth-sco"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb-device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi-arc"
        else -> "type-$type"
    }
}
