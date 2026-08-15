#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/hevc-production-check"
rm -rf "$BUILD"
mkdir -p "$BUILD"

FACTORY="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevcProductionCapability.kt"
COMPAT="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevcNegotiationCompat.kt"
POLICY="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoCodecNegotiationPolicy.kt"
RUNTIME="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt"
ENGINE="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt"
SIGNALING="$ROOT/stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt"
UI="$ROOT/app/src/main/java/dev/gfn/android/ui/GfnAndroidApp.kt"

# Production source guards.
grep -Fq 'MediaCodecList(MediaCodecList.ALL_CODECS)' "$FACTORY" || { echo 'ERROR: HEVC production probe does not enumerate MediaCodecList' >&2; exit 1; }
grep -Fq 'it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain' "$FACTORY" || { echo 'ERROR: HEVC Main profile gate missing' >&2; exit 1; }
grep -Fq 'MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51' "$FACTORY" || { echo 'ERROR: explicit HEVC High Tier 5.1 mapping missing' >&2; exit 1; }
grep -Fq 'candidate.maxLevel.rank >= GfnHevcLevel.Level51.rank' "$FACTORY" || { echo 'ERROR: normalized High Tier level safety gate missing' >&2; exit 1; }
grep -Fq 'Predicate<MediaCodecInfo> { info -> info.name == capability.codecName }' "$FACTORY" || { echo 'ERROR: advertised HEVC capability is not bound to the exact decoder component' >&2; exit 1; }
grep -Fq '"tier-flag" to tier.sdpTierFlag' "$FACTORY" || { echo 'ERROR: explicit H265 tier advertisement missing' >&2; exit 1; }
grep -Fq 'GfnHevcAwareVideoDecoderFactory(eglContext())' "$RUNTIME" || { echo 'ERROR: HEVC-aware factory is not installed in PeerConnectionFactory runtime' >&2; exit 1; }
grep -Fq 'hevcCompatibleAvailable = hevcCompatibility.compatible' "$ENGINE" || { echo 'ERROR: Offer policy is not gated by production compatibility' >&2; exit 1; }
grep -Fq 'remoteLevel.rank > localCapability.maxLevel.rank' "$COMPAT" || { echo 'ERROR: remote level <= local maxLevel safety gate missing' >&2; exit 1; }
grep -Fq 'remoteCodecs = GfnSdpTools.firstVideoCodecDetails(offerSdp)' "$ENGINE" || { echo 'ERROR: pre-answer planner is not matched against the original Offer candidates' >&2; exit 1; }
grep -Fq 'val fixedOffer = mediaIp?.let { GfnSdpTools.rewriteOfferConnectionAddresses(offerSdp, it) } ?: offerSdp' "$ENGINE" || { echo 'ERROR: production remote description path changed unexpectedly' >&2; exit 1; }
grep -Fq 'v6.0.4 保持 GFN 原始 HEVC Main/High Tier Offer，不再改写 tier-flag' "$UI" || { echo 'ERROR: current UI still describes the old A/B path' >&2; exit 1; }

if grep -RqsE 'rewriteFirstVideoHevcMainTierFlagForAb|HevcTierFlagRewriteResult|OFFER_TIER_AB|tierFlagAbRewrite' \
  "$ENGINE" "$COMPAT" "$SIGNALING"; then
  echo 'ERROR: diagnostic tier rewrite residue remains in production source' >&2
  exit 1
fi
if grep -Fq 'c2.qti.hevc.decoder' "$FACTORY"; then
  echo 'ERROR: production decoder component name must not be hard-coded' >&2
  exit 1
fi
if grep -Eq 'androidLevel[[:space:]]*(>=|<=|>|<)|\.level[[:space:]]*(>=|<=|>|<)[[:space:]]*MediaCodecInfo\.CodecProfileLevel|MediaCodecInfo\.CodecProfileLevel[^[:cntrl:]]*(>=|<=|>|<)[[:space:]]*[[:alnum:]_.]*\.level' "$FACTORY"; then
  echo 'ERROR: raw Android CodecProfileLevel constants must not be numerically ordered' >&2
  exit 1
fi
printf '%s\n' 'V604_HEVC_PRODUCTION_SOURCE_GUARDS=PASS'

cat > "$BUILD/AndroidMedia.kt" <<'KT'
package android.media

object MediaCodecRegistry {
    var codecInfos: Array<MediaCodecInfo> = emptyArray()
}

class MediaCodecInfo(
    val name: String,
    val isEncoder: Boolean = false,
    val supportedTypes: Array<String> = arrayOf(MediaFormat.MIMETYPE_VIDEO_HEVC),
    val isHardwareAccelerated: Boolean = true,
    private val caps: CodecCapabilities,
) {
    fun getCapabilitiesForType(type: String): CodecCapabilities {
        check(supportedTypes.any { it.equals(type, true) })
        return caps
    }

    class CodecCapabilities(
        val profileLevels: Array<CodecProfileLevel>,
        val videoCapabilities: VideoCapabilities?,
    )

    class VideoCapabilities(
        val bitrateRange: Range,
        private val sizeRateSupported: Boolean,
    ) {
        fun areSizeAndRateSupported(width: Int, height: Int, fps: Double): Boolean =
            sizeRateSupported && width == 1920 && height == 1080 && fps == 60.0
    }

    class Range(val lower: Int, val upper: Int)

    class CodecProfileLevel(var profile: Int, var level: Int) {
        companion object {
            const val HEVCProfileMain = 1
            const val HEVCMainTierLevel1 = 1; const val HEVCHighTierLevel1 = 2
            const val HEVCMainTierLevel2 = 4; const val HEVCHighTierLevel2 = 8
            const val HEVCMainTierLevel21 = 16; const val HEVCHighTierLevel21 = 32
            const val HEVCMainTierLevel3 = 64; const val HEVCHighTierLevel3 = 128
            const val HEVCMainTierLevel31 = 256; const val HEVCHighTierLevel31 = 512
            const val HEVCMainTierLevel4 = 1024; const val HEVCHighTierLevel4 = 2048
            const val HEVCMainTierLevel41 = 4096; const val HEVCHighTierLevel41 = 8192
            const val HEVCMainTierLevel5 = 16384; const val HEVCHighTierLevel5 = 32768
            const val HEVCMainTierLevel51 = 65536; const val HEVCHighTierLevel51 = 131072
            const val HEVCMainTierLevel52 = 262144; const val HEVCHighTierLevel52 = 524288
            const val HEVCMainTierLevel6 = 1048576; const val HEVCHighTierLevel6 = 2097152
            const val HEVCMainTierLevel61 = 4194304; const val HEVCHighTierLevel61 = 8388608
            const val HEVCMainTierLevel62 = 16777216; const val HEVCHighTierLevel62 = 33554432
        }
    }
}

class MediaCodecList(kind: Int) {
    val codecInfos: Array<MediaCodecInfo> get() = MediaCodecRegistry.codecInfos
    companion object { const val ALL_CODECS = 1 }
}

object MediaFormat { const val MIMETYPE_VIDEO_HEVC = "video/hevc" }
KT

cat > "$BUILD/WebRtc.kt" <<'KT'
package org.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCodecRegistry

open class EglBase { open class Context }
class VideoCodecInfo(val name: String, val params: Map<String, String>, val scalabilityModes: List<String>)
interface VideoDecoder
class BoundDecoder(val codecName: String) : VideoDecoder
fun interface Predicate<T> { fun test(arg: T): Boolean }
interface VideoDecoderFactory {
    fun createDecoder(info: VideoCodecInfo): VideoDecoder?
    fun getSupportedCodecs(): Array<VideoCodecInfo>
}

class DefaultVideoDecoderFactory(context: EglBase.Context?) : VideoDecoderFactory {
    override fun getSupportedCodecs(): Array<VideoCodecInfo> = arrayOf(
        VideoCodecInfo("H264", emptyMap(), emptyList()),
        VideoCodecInfo("H265", emptyMap(), emptyList()),
    )
    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? = BoundDecoder("default:${info.name}")
}

class HardwareVideoDecoderFactory(
    context: EglBase.Context?,
    private val predicate: Predicate<MediaCodecInfo>,
) : VideoDecoderFactory {
    private fun selected(): MediaCodecInfo? = MediaCodecRegistry.codecInfos.firstOrNull {
        !it.isEncoder && it.isHardwareAccelerated && predicate.test(it) &&
            it.supportedTypes.any { type -> type.equals("video/hevc", true) }
    }
    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        if (selected() != null) arrayOf(VideoCodecInfo("H265", emptyMap(), emptyList())) else emptyArray()
    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? = selected()?.let { BoundDecoder(it.name) }
}

class RtpCapabilities {
    class CodecCapability(
        var preferredPayloadType: Int = 0,
        var name: String = "",
        var clockRate: Int? = 90000,
        var parameters: Map<String, String>? = emptyMap(),
        var mimeType: String? = null,
    )
}
KT

cat > "$BUILD/AndroidLog.kt" <<'KT'
package android.util
object Log { fun i(tag: String, message: String): Int = 0 }
KT

cat > "$BUILD/Signaling.kt" <<'KT'
package dev.gfn.signaling

data class VideoCodecDescription(
    val payloadType: Int,
    val name: String,
    val clockRate: Int? = 90000,
    val fmtp: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val rtxPayloadTypes: List<Int> = emptyList(),
) {
    val normalizedName: String get() = if (name.equals("HEVC", true)) "H265" else name.uppercase()
    val profileId: String? get() = parameters["profile-id"]
    val tierFlag: String? get() = parameters["tier-flag"]
    val levelId: String? get() = parameters["level-id"]
    val txMode: String? get() = parameters["tx-mode"]
}

data class SdpSummary(
    val h264PayloadTypes: List<Int> = emptyList(),
    val hevcPayloadTypes: List<Int> = emptyList(),
    val hevcMainPayloadTypes: List<Int> = emptyList(),
)

object GfnSdpTools {
    fun summarize(sdp: String, isOffer: Boolean) = SdpSummary()
    fun firstVideoPayloadOrder(sdp: String) = emptyList<Int>()
    fun firstVideoCodecDetails(sdp: String) = emptyList<VideoCodecDescription>()
}
KT

cat > "$BUILD/Stream.kt" <<'KT'
package dev.gfn.stream
enum class VideoCodecPreference { H264, Hevc, Av1 }
KT

cat > "$BUILD/Snapshot.kt" <<'KT'
package dev.gfn.webrtc
data class GfnVideoCodecCapabilitySnapshot(
    val source: String,
    val index: Int,
    val preferredPayloadType: Int? = null,
    val name: String,
    val mimeType: String? = null,
    val clockRate: Int? = null,
    val parameters: Map<String, String> = emptyMap(),
) {
    val normalizedName: String get() = if (name.equals("HEVC", true)) "H265" else name.uppercase()
}
KT

cat > "$BUILD/Probe.kt" <<'KT'
package dev.gfn.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCodecRegistry
import dev.gfn.signaling.VideoCodecDescription
import dev.gfn.stream.VideoCodecPreference
import org.webrtc.BoundDecoder
import org.webrtc.RtpCapabilities

private fun profileLevel(level: Int) = MediaCodecInfo.CodecProfileLevel(
    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
    level,
)

private fun decoder(
    name: String,
    level: Int,
    hardware: Boolean = true,
    sizeRate: Boolean = true,
    bitrateUpper: Int = 200_000_000,
    withVideoCapabilities: Boolean = true,
) = MediaCodecInfo(
    name = name,
    isHardwareAccelerated = hardware,
    caps = MediaCodecInfo.CodecCapabilities(
        profileLevels = arrayOf(profileLevel(level)),
        videoCapabilities = if (withVideoCapabilities) {
            MediaCodecInfo.VideoCapabilities(
                MediaCodecInfo.Range(1_000, bitrateUpper),
                sizeRate,
            )
        } else {
            null
        },
    ),
)

private fun remote(
    pt: Int,
    profile: String,
    tier: String,
    level: String,
    txMode: String? = null,
) = VideoCodecDescription(
    payloadType = pt,
    name = "H265",
    parameters = buildMap {
        put("profile-id", profile)
        put("tier-flag", tier)
        put("level-id", level)
        if (txMode != null) put("tx-mode", txMode)
    },
)

private fun local(pt: Int, profile: String?, tier: String?, level: String?) =
    RtpCapabilities.CodecCapability(
        pt,
        "H265",
        90000,
        buildMap {
            if (profile != null) put("profile-id", profile)
            if (tier != null) put("tier-flag", tier)
            if (level != null) put("level-id", level)
        },
        "video/H265",
    )

fun main() {
    MediaCodecRegistry.codecInfos = arrayOf(
        decoder("software.hevc", MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62, hardware = false),
        decoder("bound.hevc", MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52),
    )
    val factory = GfnHevcAwareVideoDecoderFactory(null)
    val capability = requireNotNull(factory.productionCapability)
    check(capability.codecName == "bound.hevc")
    check(capability.tier == GfnHevcTier.High)
    check(capability.maxLevel == GfnHevcLevel.Level52)
    val advertised = factory.getSupportedCodecs().single { it.name == "H265" }
    check(advertised.params == mapOf(
        "profile-id" to "1",
        "tier-flag" to "1",
        "level-id" to "156",
    )) { advertised.params }
    val decoder = factory.createDecoder(advertised) as BoundDecoder
    check(decoder.codecName == "bound.hevc") { decoder.codecName }
    val support = GfnHevcDecoderCapabilityProbe.evaluateStream(capability, 1920, 1080, 60, 100_000)
    check(support.supported) { support }

    MediaCodecRegistry.codecInfos = arrayOf(
        decoder(
            "bound.hevc",
            MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52,
            withVideoCapabilities = false,
        ),
    )
    val missingVideoCapsSupport = GfnHevcDecoderCapabilityProbe.evaluateStream(
        capability,
        1920,
        1080,
        60,
        100_000,
    )
    check(!missingVideoCapsSupport.supported) { missingVideoCapsSupport }
    check(missingVideoCapsSupport.reason.contains("video capabilities unavailable")) {
        missingVideoCapsSupport.reason
    }

    MediaCodecRegistry.codecInfos = arrayOf(
        decoder("bound.hevc", MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52),
    )

    val remotes = listOf(
        remote(107, "2", "1", "153"),
        remote(103, "1", "1", "153"),
        remote(117, "1", "0", "153"),
        remote(119, "1", "1", "180"),
        remote(121, "1", "1", "153", "MRST"),
    )
    val compatibility = GfnHevcProductionCompatibilityMatcher.evaluate(remotes, capability, support)
    check(compatibility.compatiblePayloadTypes == listOf(103)) { compatibility }

    val localCapabilities = listOf(
        local(43, "1", "1", "156"),
        local(44, null, null, null),
        local(45, "2", "1", "156"),
        RtpCapabilities.CodecCapability(96, "H264", 90000, emptyMap(), "video/H264"),
    )
    val plan = GfnHevcCodecPreferencePlanner.build(localCapabilities, remotes)
    check(plan.compatibleHevcMainCount == 1) { plan.orderedLabels }
    check(plan.orderedCapabilities.map { it.preferredPayloadType }.take(2) == listOf(43, 96)) { plan.orderedLabels }

    val decision = GfnVideoCodecNegotiationPolicy.selectForOffer(
        requested = VideoCodecPreference.Hevc,
        h264Available = true,
        hevcCompatibleAvailable = compatibility.compatible,
        hevcIncompatibilityReason = compatibility.reason,
    ).getOrThrow()
    check(decision.codec == VideoCodecPreference.Hevc)

    MediaCodecRegistry.codecInfos = arrayOf(
        decoder(
            "null-video-cap.hevc",
            MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62,
            withVideoCapabilities = false,
        ),
    )
    val nullVideoCapsFactory = GfnHevcAwareVideoDecoderFactory(null)
    check(nullVideoCapsFactory.productionCapability == null)
    check(nullVideoCapsFactory.probeResult.errors.any { it.contains("videoCapabilities unavailable") }) {
        nullVideoCapsFactory.probeResult.errors
    }

    MediaCodecRegistry.codecInfos = arrayOf(
        decoder("main-tier-only", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62),
    )
    val noHighFactory = GfnHevcAwareVideoDecoderFactory(null)
    check(noHighFactory.productionCapability == null)
    check(noHighFactory.getSupportedCodecs().none { it.name == "H265" })

    println("V604_HEVC_FACTORY_BINDING_FIXTURE=PASS")
    println("V604_HEVC_VIDEO_CAPS_NULLABILITY_FIXTURE=PASS")
    println("V604_HEVC_COMPATIBILITY_FIXTURE=PASS")
    println("BOUND=${capability.codecName} ADVERTISED=${advertised.params} MATCHED=${compatibility.compatiblePayloadTypes}")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$BUILD/AndroidMedia.kt" \
  "$BUILD/WebRtc.kt" \
  "$BUILD/AndroidLog.kt" \
  "$BUILD/Signaling.kt" \
  "$BUILD/Stream.kt" \
  "$BUILD/Snapshot.kt" \
  "$FACTORY" \
  "$COMPAT" \
  "$POLICY" \
  "$BUILD/Probe.kt" \
  -include-runtime -d "$BUILD/probe.jar"
java -jar "$BUILD/probe.jar"

printf '%s\n' 'V604_HEVC_PRODUCTION_VERIFY=PASS'
