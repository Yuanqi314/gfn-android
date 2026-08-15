#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/hevc-check"
rm -rf "$BUILD"
mkdir -p "$BUILD/sdp" "$BUILD/core"

CORE="$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt"
SETTINGS="$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt"
STORE="$ROOT/app/src/main/java/dev/gfn/android/settings/AndroidStreamSettingsStore.kt"
RUNTIME="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt"
ENGINE="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt"
POLICY="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoCodecNegotiationPolicy.kt"
SIGNALING="$ROOT/stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt"
CLOUDMATCH="$ROOT/gfn-cloudmatch/src/main/kotlin/dev/gfn/cloudmatch/CloudMatchProtocol.kt"

# Production guards: v6.0 is HEVC Main + SDR8 only, with H.264 fallback.
grep -Fq 'codecs = setOf(VideoCodecPreference.H264, VideoCodecPreference.Hevc)' "$CORE" || {
  echo 'ERROR: v6.0 capability profile must expose only H264 + HEVC' >&2; exit 1;
}
grep -Fq 'colorModes = setOf(RequestedColorMode.CompatibilitySdr)' "$CORE" || {
  echo 'ERROR: v6.0 must remain CompatibilitySdr only' >&2; exit 1;
}
grep -Fq 'StreamCodecChoice(VideoCodecPreference.Hevc, "HEVC Main · SDR8")' "$SETTINGS" || {
  echo 'ERROR: HEVC Main SDR8 is not exposed in next-session settings' >&2; exit 1;
}
grep -Fq 'codec = settings.videoCodec' "$SETTINGS" || {
  echo 'ERROR: resolver is not carrying selected codec into frozen StreamConfig' >&2; exit 1;
}
grep -Fq 'const val KEY_VIDEO_CODEC = "videoCodec"' "$STORE" || {
  echo 'ERROR: codec preference is not persisted' >&2; exit 1;
}
grep -Fq '.putString(KEY_VIDEO_CODEC, normalized.videoCodec.name)' "$STORE" || {
  echo 'ERROR: codec preference is not saved' >&2; exit 1;
}
grep -Fq 'videoDecoderFactory.supportedCodecs' "$RUNTIME" || {
  echo 'ERROR: local libwebrtc decoder capabilities are not inspected' >&2; exit 1;
}
grep -Fq 'GfnVideoCodecNegotiationPolicy.selectForOffer' "$ENGINE" || {
  echo 'ERROR: Offer codec policy is not wired' >&2; exit 1;
}
grep -Fq 'GfnVideoCodecNegotiationPolicy.selectAfterAnswer' "$ENGINE" || {
  echo 'ERROR: Answer codec policy is not wired' >&2; exit 1;
}
grep -Fq 'matchingAnswerHevcMainPayloadTypes(offerSdp, rawAnswer)' "$ENGINE" || {
  echo 'ERROR: v6.0 HEVC Answer path must bind H265 back to the offered Main payload' >&2; exit 1;
}
grep -Fq 'fun preferVideoCodecInAnswer' "$SIGNALING" || {
  echo 'ERROR: generic video codec Answer filter is missing' >&2; exit 1;
}
# Do not move codec selection into CloudMatch in this single-variable version.
if grep -Fq 'streamConfig.codec' "$CLOUDMATCH" || grep -Fq 'VideoCodecPreference' "$CLOUDMATCH"; then
  echo 'ERROR: v6.0 must not add client codec selection to CloudMatch request semantics' >&2; exit 1;
fi
# Main10/HDR/AV1 must not be in the user-facing v6.0 codec choices.
if grep -F 'StreamCodecChoice(' "$SETTINGS" | grep -Eiq 'main10|hdr|av1'; then
  echo 'ERROR: v6.0 codec choices accidentally expose Main10/HDR/AV1' >&2; exit 1;
fi
printf '%s\n' 'V600_HEVC_STATIC_GUARDS=PASS'

# --- Pure SDP fixture --------------------------------------------------------
cat > "$BUILD/sdp/Probe.kt" <<'KT'
import dev.gfn.signaling.GfnSdpTools

fun main() {
    val mixed = """
        v=0
        a=group:BUNDLE 0 1
        m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99 100 101 116 117
        a=mid:1
        a=rtpmap:96 H264/90000
        a=fmtp:96 profile-level-id=42e01f;packetization-mode=1
        a=rtpmap:97 rtx/90000
        a=fmtp:97 apt=96
        a=rtpmap:98 H265/90000
        a=fmtp:98 profile-id=1;tier-flag=0;level-id=120
        a=rtpmap:99 rtx/90000
        a=fmtp:99 apt=98
        a=rtpmap:100 H265/90000
        a=fmtp:100 profile-id=2;tier-flag=0;level-id=120
        a=rtpmap:101 rtx/90000
        a=fmtp:101 apt=100
        a=rtpmap:116 red/90000
        a=rtpmap:117 ulpfec/90000
        a=ice-ufrag:u
        a=ice-pwd:p
        a=fingerprint:sha-256 FF
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=mid:0
        a=rtpmap:111 opus/48000/2
    """.trimIndent()

    val original = GfnSdpTools.summarize(mixed, true)
    check(original.h264PayloadTypes == listOf(96))
    check(original.hevcPayloadTypes == listOf(98, 100))
    check(original.hevcMainPayloadTypes == listOf(98))

    val hevc = GfnSdpTools.preferVideoCodecInAnswer(mixed, "H265", preferredHevcProfileId = 1)
    val hs = GfnSdpTools.summarize(hevc, false)
    check(hs.h264PayloadTypes.isEmpty())
    check(hs.hevcPayloadTypes == listOf(98))
    check(hs.hevcMainPayloadTypes == listOf(98))
    check(hevc.contains("m=video 9 UDP/TLS/RTP/SAVPF 98 99 116 117")) { hevc }
    check(!hevc.contains("a=rtpmap:100 H265"))
    check(!hevc.contains("a=rtpmap:101 rtx"))
    check(hevc.contains("a=rtpmap:111 opus/48000/2"))

    val h264 = GfnSdpTools.preferVideoCodecInAnswer(mixed, "H264")
    val h264s = GfnSdpTools.summarize(h264, false)
    check(h264s.h264PayloadTypes == listOf(96))
    check(h264s.hevcPayloadTypes.isEmpty())
    check(h264.contains("m=video 9 UDP/TLS/RTP/SAVPF 96 97 116 117")) { h264 }

    val missingMain = mixed.replace("profile-id=1;tier-flag=0;level-id=120", "profile-id=2;tier-flag=0;level-id=120")
    val unchanged = GfnSdpTools.preferVideoCodecInAnswer(missingMain, "H265", preferredHevcProfileId = 1)
    check(unchanged == missingMain) // No Main fabrication.

    println("V600_HEVC_SDP_FIXTURE=PASS")
    println("OFFER_H264=${original.h264PayloadTypes} HEVC=${original.hevcPayloadTypes} MAIN=${original.hevcMainPayloadTypes}")
    println("HEVC_ANSWER=${hs.videoCodecs} MAIN=${hs.hevcMainPayloadTypes}")
    println("H264_ANSWER=${h264s.videoCodecs}")
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" \
  "$SIGNALING" \
  "$BUILD/sdp/Probe.kt" -include-runtime -d "$BUILD/sdp/probe.jar"
java -jar "$BUILD/sdp/probe.jar"

# --- Policy + settings + persistence in one Kotlin module ------------------
cat > "$BUILD/core/AndroidContent.kt" <<'KT'
package android.content
abstract class Context {
    open val applicationContext: Context get() = this
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    companion object { const val MODE_PRIVATE = 0 }
}
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun apply()
    }
}
KT
cat > "$BUILD/core/Probe.kt" <<'KT'
package dev.gfn.webrtc

import android.content.Context
import android.content.SharedPreferences
import dev.gfn.android.settings.AndroidStreamSettingsStore
import dev.gfn.android.settings.GfnStreamSettingsCatalog
import dev.gfn.android.settings.GfnStreamSettingsResolver
import dev.gfn.android.settings.PersistentStreamSettings
import dev.gfn.core.model.EntitledResolution
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.stream.StreamCapabilityProfiles
import dev.gfn.stream.VideoCodecPreference

private class MemoryPrefs : SharedPreferences {
    val values = linkedMapOf<String, Any?>()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun edit() = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor { values[key] = value; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { values[key] = value; return this }
        override fun apply() = Unit
    }
}
private class FakeContext(private val prefs: MemoryPrefs) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
}

fun main() {
    val hevc = GfnVideoCodecNegotiationPolicy.selectForOffer(
        requested = VideoCodecPreference.Hevc,
        localDecoderCodecs = setOf("H264", "H265"),
        h264Available = true,
        hevcMainAvailable = true,
    ).getOrThrow()
    check(hevc.codec == VideoCodecPreference.Hevc && hevc.fallbackReason == null)
    check(GfnVideoCodecNegotiationPolicy.selectForOffer(
        VideoCodecPreference.Hevc, setOf("H264", "HEVC"), true, true,
    ).getOrThrow().codec == VideoCodecPreference.Hevc)

    val noLocal = GfnVideoCodecNegotiationPolicy.selectForOffer(
        VideoCodecPreference.Hevc, setOf("H264"), true, true,
    ).getOrThrow()
    check(noLocal.codec == VideoCodecPreference.H264)
    check(noLocal.fallbackReason?.contains("未声明 H265") == true)

    val noMain = GfnVideoCodecNegotiationPolicy.selectForOffer(
        VideoCodecPreference.Hevc, setOf("H264", "HEVC"), true, false,
    ).getOrThrow()
    check(noMain.codec == VideoCodecPreference.H264)
    check(noMain.fallbackReason?.contains("profile-id=1") == true)

    check(GfnVideoCodecNegotiationPolicy.selectForOffer(
        VideoCodecPreference.Hevc, setOf("H265"), false, false,
    ).isFailure)
    val answerFallback = GfnVideoCodecNegotiationPolicy.selectAfterAnswer(
        VideoCodecPreference.Hevc, h264Available = true, hevcMainAvailable = false,
    ).getOrThrow()
    check(answerFallback.codec == VideoCodecPreference.H264)
    check(answerFallback.fallbackReason?.contains("createAnswer") == true)
    check(GfnVideoCodecNegotiationPolicy.selectForOffer(
        VideoCodecPreference.Av1, setOf("AV1", "H264"), true, true,
    ).isFailure)
    println("V600_HEVC_POLICY_FIXTURE=PASS")
    println("HEVC=${hevc.codec} NO_LOCAL=${noLocal.codec} NO_OFFER_MAIN=${noMain.codec} ANSWER_FALLBACK=${answerFallback.codec}")

    check(GfnStreamSettingsCatalog.codecChoices.map { it.codec } == listOf(
        VideoCodecPreference.H264, VideoCodecPreference.Hevc,
    ))
    check(StreamCapabilityProfiles.V60_ANDROID_WEBRTC.codecs == setOf(
        VideoCodecPreference.H264, VideoCodecPreference.Hevc,
    ))
    check(StreamCapabilityProfiles.V60_ANDROID_WEBRTC.colorModes == setOf(RequestedColorMode.CompatibilitySdr))
    val subscription = SubscriptionInfo(
        membershipTier = "fixture",
        entitledResolutions = listOf(EntitledResolution(1920, 1080, 60)),
    )
    val defaultProfile = GfnStreamSettingsResolver.resolve(
        PersistentStreamSettings(keyboardLayoutSelection = "en-US"), subscription, "zh-CN", "zh_CN",
    )
    check(defaultProfile.streamConfig.codec == VideoCodecPreference.H264)
    val hevcProfile = GfnStreamSettingsResolver.resolve(
        PersistentStreamSettings(
            keyboardLayoutSelection = "en-US",
            maxBitrateKbps = 100_000,
            videoCodec = VideoCodecPreference.Hevc,
            audioChannels = 6,
        ),
        subscription, "zh-CN", "zh_CN",
    )
    check(hevcProfile.streamConfig.codec == VideoCodecPreference.Hevc)
    check(hevcProfile.streamConfig.colorMode == RequestedColorMode.CompatibilitySdr)
    check(hevcProfile.streamConfig.maxBitrateKbps == 100_000)
    check(hevcProfile.streamConfig.audioChannels == 6)
    check("codec=Hevc" in hevcProfile.summary)
    check(GfnStreamSettingsCatalog.normalize(
        PersistentStreamSettings(videoCodec = VideoCodecPreference.Av1),
    ).videoCodec == VideoCodecPreference.H264)
    println("V600_HEVC_SETTINGS_FIXTURE=PASS")
    println("DEFAULT=${defaultProfile.summary}")
    println("HEVC_PROFILE=${hevcProfile.summary}")

    val prefs = MemoryPrefs()
    val store = AndroidStreamSettingsStore(FakeContext(prefs))
    check(store.load().videoCodec == VideoCodecPreference.H264)
    check(store.save(PersistentStreamSettings(videoCodec = VideoCodecPreference.Hevc)).videoCodec == VideoCodecPreference.Hevc)
    check(prefs.values["videoCodec"] == "Hevc")
    check(store.load().videoCodec == VideoCodecPreference.Hevc)
    prefs.values["videoCodec"] = "Main10"
    val recovered = store.load().videoCodec
    check(recovered == VideoCodecPreference.H264)
    println("V600_HEVC_STORE_FIXTURE=PASS")
    println("CORRUPT_VALUE_FALLBACK=$recovered")
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$BUILD/core/AndroidContent.kt" \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$CORE" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnKeyboardLayoutCatalog.kt" \
  "$SETTINGS" \
  "$STORE" \
  "$POLICY" \
  "$BUILD/core/Probe.kt" -include-runtime -d "$BUILD/core/probe.jar"
java -jar "$BUILD/core/probe.jar"

printf '%s\n' 'V600_HEVC_VERIFY=PASS'
