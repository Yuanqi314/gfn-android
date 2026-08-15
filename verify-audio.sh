#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/audio-check"
rm -rf "$BUILD"
mkdir -p "$BUILD/sdp" "$BUILD/settings" "$BUILD/runtime"

# Production guards: native stereo enabled; 6ch is explicitly experimental/downmix-only.
grep -Fq '.setUseStereoOutput(true)' "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt" || {
  echo 'ERROR: JavaAudioDeviceModule native stereo output is not enabled' >&2; exit 1;
}
grep -Fq 'audioChannels = setOf(2, 6)' "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" || {
  echo 'ERROR: v5.4 audio request capability must expose 2ch + experimental 6ch' >&2; exit 1;
}
grep -Fq 'nativeAudioOutputChannels = setOf(2)' "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" || {
  echo 'ERROR: native audio output capability must remain 2ch only' >&2; exit 1;
}
grep -Fq 'GfnSdpTools.mungeAudioAnswer' "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt" || {
  echo 'ERROR: audio answer munging is not wired into WebRTC answer flow' >&2; exit 1;
}
grep -Fq 'val audioKbps = if (config.audioChannels >= 6) 256 else 128' "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt" || {
  echo 'ERROR: audio bandwidth mode is not channel-aware' >&2; exit 1;
}
grep -Fq '"surroundAudioInfo" to surroundAudioMask(audioChannels)' "$ROOT/gfn-cloudmatch/src/main/kotlin/dev/gfn/cloudmatch/CloudMatchProtocol.kt" || {
  echo 'ERROR: CloudMatch surroundAudioInfo is not wired' >&2; exit 1;
}
grep -Fq 'audioChannels = streamConfig.audioChannels' "$ROOT/app/src/main/java/dev/gfn/android/session/GfnSessionController.kt" || {
  echo 'ERROR: CREATE does not consume frozen audioChannels' >&2; exit 1;
}
AUDIO_SNAPSHOT_CLAIMS=$(grep -Fc 'audioChannels = active.profile.streamConfig.audioChannels' "$ROOT/app/src/main/java/dev/gfn/android/session/GfnSessionController.kt")
[ "$AUDIO_SNAPSHOT_CLAIMS" -ge 2 ] || {
  echo 'ERROR: CLAIM/reconnect do not both consume frozen audioChannels' >&2; exit 1;
}
grep -Fq 'setProperty("streamAudioChannels", profile.streamConfig.audioChannels.toString())' "$ROOT/app/src/main/java/dev/gfn/android/session/AndroidSessionPersistence.kt" || {
  echo 'ERROR: frozen audioChannels are not persisted with Session profile' >&2; exit 1;
}
echo 'V540_AUDIO_SNAPSHOT_CHAIN=PASS'
echo 'V540_AUDIO_STATIC_GUARDS=PASS'

# --- Pure Kotlin SDP fixture -------------------------------------------------
cat > "$BUILD/sdp/Probe.kt" <<'KT'
import dev.gfn.signaling.GfnSdpTools

private fun count(text: String, needle: String): Int = text.windowed(needle.length).count { it == needle }

fun main() {
    val stereoOffer = """
        v=0
        a=group:BUNDLE 0 1
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=mid:0
        a=rtpmap:111 opus/48000/2
        a=fmtp:111 minptime=10;useinbandfec=1
        m=video 9 UDP/TLS/RTP/SAVPF 96
        a=mid:1
        a=rtpmap:96 H264/90000
    """.trimIndent()
    val stereoAnswer = stereoOffer
    val stereoOnce = GfnSdpTools.mungeAudioAnswer(stereoAnswer, stereoOffer, 2)
    val stereoTwice = GfnSdpTools.mungeAudioAnswer(stereoOnce.sdp, stereoOffer, 2)
    check(stereoOnce.mode == "STEREO_NATIVE")
    check(stereoOnce.opusStereoEnabled)
    check(stereoOnce.sdp == stereoTwice.sdp)
    check(count(stereoOnce.sdp, "stereo=1") == 1)
    val stereoZero = stereoAnswer.replace("useinbandfec=1", "useinbandfec=1;stereo=0")
    val stereoReplaced = GfnSdpTools.mungeAudioAnswer(stereoZero, stereoOffer, 2)
    check(stereoReplaced.sdp.contains("stereo=1"))
    check(!stereoReplaced.sdp.contains("stereo=0"))
    check(count(stereoReplaced.sdp, "stereo=1") == 1)
    val stereoBounded = GfnSdpTools.injectBandwidth(stereoOnce.sdp, 100_000, 128)
    check(stereoBounded.contains("b=AS:128"))

    val surroundOffer = """
        v=0
        a=group:BUNDLE game video mic
        m=audio 9 UDP/TLS/RTP/SAVPF 112
        a=mid:game
        a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
        a=rtpmap:112 multiopus/48000/6
        a=fmtp:112 channel_mapping=0,4,1,2,3,5;num_streams=4;coupled_streams=2
        m=video 9 UDP/TLS/RTP/SAVPF 96
        a=mid:video
        a=ice-ufrag:test-ufrag
        a=ice-pwd:test-pwd
        a=ice-options:trickle
        a=fingerprint:sha-256 00:11:22:33
        a=setup:active
        a=rtpmap:96 H264/90000
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=mid:mic
        a=rtpmap:111 opus/48000/2
    """.trimIndent()
    val rejectedAnswer = """
        v=0
        a=group:BUNDLE video mic
        m=audio 0 UDP/TLS/RTP/SAVPF 111
        a=mid:game
        a=rtpmap:111 opus/48000/2
        m=video 9 UDP/TLS/RTP/SAVPF 96
        a=mid:video
        a=ice-ufrag:test-ufrag
        a=ice-pwd:test-pwd
        a=ice-options:trickle
        a=fingerprint:sha-256 00:11:22:33
        a=setup:active
        a=rtpmap:96 H264/90000
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=mid:mic
        a=rtpmap:111 opus/48000/2
    """.trimIndent()
    val surround = GfnSdpTools.mungeAudioAnswer(rejectedAnswer, surroundOffer, 6)
    check(surround.surroundAccepted)
    check(surround.mode == "SURROUND_MULTI_OPUS_ADM_2CH_PROBE")
    check(surround.sdp.contains("a=group:BUNDLE game video mic"))
    check(surround.sdp.contains("m=audio 9 UDP/TLS/RTP/SAVPF 112"))
    check(surround.sdp.contains("a=rtpmap:112 multiopus/48000/6"))
    check(surround.sdp.contains("channel_mapping=0,4,1,2,3,5;num_streams=4;coupled_streams=2"))
    check(surround.sdp.contains("a=ice-ufrag:test-ufrag"))
    val surroundTwice = GfnSdpTools.mungeAudioAnswer(surround.sdp, surroundOffer, 6)
    check(surroundTwice.sdp == surround.sdp)
    val surroundBounded = GfnSdpTools.injectBandwidth(surround.sdp, 100_000, 256)
    check(surroundBounded.contains("b=AS:256"))

    val noSurround = GfnSdpTools.mungeAudioAnswer(rejectedAnswer, stereoOffer, 6)
    check(!noSurround.surroundAccepted)
    check(noSurround.mode == "SURROUND_UNAVAILABLE")
    check(noSurround.sdp == rejectedAnswer)

    println("V540_AUDIO_SDP_FIXTURE=PASS")
    println("STEREO=${stereoOnce.selectedCodec?.name}/${stereoOnce.selectedCodec?.channels} stereo1=${stereoOnce.opusStereoEnabled}")
    println("SURROUND=${surround.selectedCodec?.name}/${surround.selectedCodec?.channels} mode=${surround.mode}")
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" \
  "$ROOT/stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt" \
  "$BUILD/sdp/Probe.kt" -include-runtime -d "$BUILD/sdp/probe.jar"
java -jar "$BUILD/sdp/probe.jar"

# --- Stream settings/capability fixture -------------------------------------
cat > "$BUILD/settings/Probe.kt" <<'KT'
import dev.gfn.android.settings.GfnStreamSettingsCatalog
import dev.gfn.android.settings.GfnStreamSettingsResolver
import dev.gfn.android.settings.PersistentStreamSettings
import dev.gfn.core.model.EntitledResolution
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.stream.StreamCapabilityProfiles

fun main() {
    check(GfnStreamSettingsCatalog.audioChoices.map { it.channels } == listOf(2, 6))
    check(GfnStreamSettingsCatalog.nativeAudioOutputChannels == setOf(2))
    check(StreamCapabilityProfiles.V54_ANDROID_WEBRTC.audioChannels == setOf(2, 6))
    check(StreamCapabilityProfiles.V54_ANDROID_WEBRTC.nativeAudioOutputChannels == setOf(2))

    val profile = GfnStreamSettingsResolver.resolve(
        persistent = PersistentStreamSettings(
            keyboardLayoutSelection = "en-US",
            maxBitrateKbps = 100_000,
            audioChannels = 6,
        ),
        subscription = SubscriptionInfo(
            membershipTier = "fixture",
            entitledResolutions = listOf(EntitledResolution(1920, 1080, 60)),
        ),
        autoKeyboardLayout = "zh-CN",
        gameLanguage = "zh_CN",
    )
    check(profile.streamConfig.audioChannels == 6)
    check(profile.streamConfig.maxBitrateKbps == 100_000)
    check(profile.keyboardLayout == "en-US")
    println("V540_AUDIO_SETTINGS_FIXTURE=PASS")
    println("PROFILE=${profile.summary}")
    println("NATIVE_OUTPUT=${GfnStreamSettingsCatalog.nativeAudioOutputChannels.sorted()}")
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnKeyboardLayoutCatalog.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt" \
  "$BUILD/settings/Probe.kt" -include-runtime -d "$BUILD/settings/probe.jar"
java -jar "$BUILD/settings/probe.jar"

# --- Android/WebRTC API-shaped compile for the actual runtime + route probe --
cat > "$BUILD/runtime/AndroidManifest.kt" <<'KT'
package android
object Manifest { object permission { const val ACCESS_NETWORK_STATE="android.permission.ACCESS_NETWORK_STATE"; const val CHANGE_NETWORK_STATE="android.permission.CHANGE_NETWORK_STATE" } }
KT
cat > "$BUILD/runtime/AndroidContent.kt" <<'KT'
package android.content
import android.media.AudioManager
open class Context {
    open val applicationContext: Context get() = this
    open fun checkSelfPermission(permission: String): Int = 0
    open fun getSystemService(name: String): Any? = if (name == AUDIO_SERVICE) AudioManager() else null
    companion object { const val AUDIO_SERVICE = "audio" }
}
KT
cat > "$BUILD/runtime/AndroidPm.kt" <<'KT'
package android.content.pm
object PackageManager { const val PERMISSION_GRANTED = 0 }
KT
cat > "$BUILD/runtime/AndroidOs.kt" <<'KT'
package android.os
object Build { object VERSION { const val SDK_INT=33 }; object VERSION_CODES { const val TIRAMISU=33 } }
KT
cat > "$BUILD/runtime/AndroidMedia.kt" <<'KT'
package android.media
class AudioAttributes private constructor() {
    class Builder { fun setUsage(v:Int)=this; fun setContentType(v:Int)=this; fun build()=AudioAttributes() }
    companion object { const val USAGE_GAME=14; const val CONTENT_TYPE_MUSIC=2 }
}
open class AudioDeviceInfo(val type:Int=TYPE_BUILTIN_SPEAKER, val channelCounts:IntArray=intArrayOf(2)) {
    companion object {
        const val TYPE_BUILTIN_SPEAKER=2; const val TYPE_BUILTIN_EARPIECE=1
        const val TYPE_WIRED_HEADPHONES=4; const val TYPE_WIRED_HEADSET=3
        const val TYPE_BLUETOOTH_A2DP=8; const val TYPE_BLUETOOTH_SCO=7
        const val TYPE_USB_DEVICE=11; const val TYPE_USB_HEADSET=22
        const val TYPE_HDMI=9; const val TYPE_HDMI_ARC=10
    }
}
class AudioManager {
    fun getAudioDevicesForAttributes(a:AudioAttributes): List<AudioDeviceInfo> = listOf(AudioDeviceInfo())
    fun getDevices(flags:Int): Array<AudioDeviceInfo> = arrayOf(AudioDeviceInfo())
    companion object { const val GET_DEVICES_OUTPUTS=2 }
}
KT
cat > "$BUILD/runtime/WebRtc.kt" <<'KT'
package org.webrtc
class EglBase { val eglBaseContext: Context = Context(); class Context; companion object { fun create()=EglBase() } }
class VideoCodecInfo(val name:String, val params:Map<String,String> = emptyMap())
interface VideoDecoderFactory { fun getSupportedCodecs(): Array<VideoCodecInfo> }
class DefaultVideoDecoderFactory(c:EglBase.Context) : VideoDecoderFactory { override fun getSupportedCodecs(): Array<VideoCodecInfo> = arrayOf(VideoCodecInfo("H264"), VideoCodecInfo("H265", mapOf("profile-id" to "1"))) }
class DefaultVideoEncoderFactory(c:EglBase.Context, a:Boolean, b:Boolean)
open class MediaStreamTrack { enum class MediaType { MEDIA_TYPE_VIDEO, MEDIA_TYPE_AUDIO, MEDIA_TYPE_DATA } }
class RtpCapabilities(val codecs:List<CodecCapability> = emptyList()) {
    class CodecCapability(
        var preferredPayloadType:Int=0,
        var name:String="",
        var clockRate:Int?=90_000,
        var parameters:Map<String,String>?=emptyMap(),
        var mimeType:String?=null,
    )
}
class PeerConnectionFactory {
    class InitializationOptions { companion object { fun builder(c:android.content.Context)=Builder() }; class Builder { fun setEnableInternalTracer(v:Boolean)=this; fun createInitializationOptions()=InitializationOptions() } }
    class Builder { fun setAudioDeviceModule(v:org.webrtc.audio.JavaAudioDeviceModule)=this; fun setVideoEncoderFactory(v:DefaultVideoEncoderFactory)=this; fun setVideoDecoderFactory(v:VideoDecoderFactory)=this; fun createPeerConnectionFactory()=PeerConnectionFactory() }
    companion object { fun initialize(v:InitializationOptions)=Unit; fun builder()=Builder() }
    fun getRtpReceiverCapabilities(type:MediaStreamTrack.MediaType)=RtpCapabilities(listOf(RtpCapabilities.CodecCapability(96,"H265",90_000,mapOf("profile-id" to "1"),"video/H265")))
}
KT
cat > "$BUILD/runtime/HevcStub.kt" <<'KT'
package dev.gfn.webrtc
import org.webrtc.EglBase
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoDecoderFactory
data class GfnHevcDecoderCapability(val codecName:String, val profile:GfnHevcProfile=GfnHevcProfile.Main, val tier:GfnHevcTier=GfnHevcTier.High, val maxLevel:GfnHevcLevel=GfnHevcLevel.Level51)
enum class GfnHevcProfile(val sdpProfileId:String) { Main("1"), Main10("2") }
enum class GfnHevcTier(val sdpTierFlag:String) { High("1") }
enum class GfnHevcLevel(val sdpLevelId:String) { Level51("153") }
data class GfnHevcDecoderProbeResult(
    val candidates:List<GfnHevcDecoderCapability> = emptyList(),
    val selected:GfnHevcDecoderCapability? = null,
    val errors:List<String> = emptyList(),
    val selectedMain10:GfnHevcDecoderCapability? = null,
)
class GfnHevcStreamSupport(val supported:Boolean, val sizeAndRateSupported:Boolean, val bitrateSupported:Boolean, val bitrateRangeKbps:IntRange?, val reason:String)
class GfnHevcAwareVideoDecoderFactory(c:EglBase.Context) : VideoDecoderFactory {
    val probeResult = GfnHevcDecoderProbeResult()
    val productionCapability: GfnHevcDecoderCapability? = null
    val main10ProductionCapability: GfnHevcDecoderCapability? = null
    val advertisementReason = "stub Main"
    val main10AdvertisementReason = "stub Main10"
    override fun getSupportedCodecs(): Array<VideoCodecInfo> = arrayOf(VideoCodecInfo("H264"))
}
object GfnHevcDecoderCapabilityProbe {
    fun evaluateStream(capability:GfnHevcDecoderCapability, width:Int, height:Int, fps:Int, maxBitrateKbps:Int) =
        GfnHevcStreamSupport(false, false, false, null, "stub")
}
KT
cat > "$BUILD/runtime/WebRtcAudio.kt" <<'KT'
package org.webrtc.audio
import android.media.AudioAttributes
class JavaAudioDeviceModule {
    fun release()=Unit
    class Builder {
        fun setUseStereoOutput(v:Boolean)=this
        fun setAudioAttributes(v:AudioAttributes)=this
        fun createAudioDeviceModule()=JavaAudioDeviceModule()
    }
    companion object { fun builder(c:android.content.Context)=Builder() }
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$BUILD/runtime/AndroidManifest.kt" "$BUILD/runtime/AndroidContent.kt" "$BUILD/runtime/AndroidPm.kt" \
  "$BUILD/runtime/AndroidOs.kt" "$BUILD/runtime/AndroidMedia.kt" "$BUILD/runtime/WebRtc.kt" "$BUILD/runtime/WebRtcAudio.kt" "$BUILD/runtime/HevcStub.kt" \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnAndroidAudioRouteProbe.kt" \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt" \
  -d "$BUILD/runtime/check.jar" > "$BUILD/runtime/compile.log" 2>&1
test -s "$BUILD/runtime/check.jar"
echo 'V540_AUDIO_RUNTIME_API_SHAPED_COMPILE=PASS'

echo 'V540_AUDIO_VERIFY=PASS'
