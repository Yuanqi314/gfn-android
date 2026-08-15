package dev.gfn.webrtc

import dev.gfn.stream.VideoCodecPreference

internal data class GfnVideoCodecDecision(
    val codec: VideoCodecPreference,
    val fallbackReason: String? = null,
)

/**
 * Pure v6.0 codec policy. It only selects H.264 or HEVC Main (profile-id=1) for SDR8.
 * CloudMatch/session creation is deliberately outside this object; the decision is made from
 * the frozen requested codec, local decoder capability, and the actual SDP intersection.
 */
internal object GfnVideoCodecNegotiationPolicy {
    fun selectForOffer(
        requested: VideoCodecPreference,
        localDecoderCodecs: Set<String>,
        h264Available: Boolean,
        hevcMainAvailable: Boolean,
    ): Result<GfnVideoCodecDecision> = when (requested) {
        VideoCodecPreference.H264 -> {
            if (h264Available) Result.success(GfnVideoCodecDecision(VideoCodecPreference.H264))
            else Result.failure(IllegalStateException("GFN Offer 未包含 H.264 payload type。"))
        }
        VideoCodecPreference.Hevc -> {
            val localHevc = localDecoderCodecs.any { normalizeCodecName(it) == "H265" }
            when {
                localHevc && hevcMainAvailable ->
                    Result.success(GfnVideoCodecDecision(VideoCodecPreference.Hevc))
                h264Available -> Result.success(
                    GfnVideoCodecDecision(
                        VideoCodecPreference.H264,
                        if (!localHevc) {
                            "本机 DefaultVideoDecoderFactory 未声明 H265 decoder；同 Session 回退 H264。"
                        } else {
                            "GFN Offer 未包含显式 HEVC Main(profile-id=1)；同 Session 回退 H264。"
                        },
                    ),
                )
                else -> Result.failure(
                    IllegalStateException("请求 HEVC Main，但本机/Offer 没有可用 HEVC Main，且 Offer 也没有 H.264 fallback。"),
                )
            }
        }
        VideoCodecPreference.Av1 ->
            Result.failure(IllegalStateException("v6.0 仅开放 H.264 / HEVC Main SDR8；requested=Av1。"))
    }

    fun selectAfterAnswer(
        selected: VideoCodecPreference,
        h264Available: Boolean,
        hevcMainAvailable: Boolean,
    ): Result<GfnVideoCodecDecision> = when (selected) {
        VideoCodecPreference.H264 -> {
            if (h264Available) Result.success(GfnVideoCodecDecision(VideoCodecPreference.H264))
            else Result.failure(IllegalStateException("libwebrtc Answer 未接受 H.264。"))
        }
        VideoCodecPreference.Hevc -> when {
            hevcMainAvailable -> Result.success(GfnVideoCodecDecision(VideoCodecPreference.Hevc))
            h264Available -> Result.success(
                GfnVideoCodecDecision(
                    VideoCodecPreference.H264,
                    "libwebrtc createAnswer 未接受 HEVC Main；同 Session 回退 H264。",
                ),
            )
            else -> Result.failure(IllegalStateException("libwebrtc Answer 同时缺少 HEVC Main 与 H.264 fallback。"))
        }
        VideoCodecPreference.Av1 ->
            Result.failure(IllegalStateException("v6.0 Answer policy 不接受 AV1。"))
    }

    private fun normalizeCodecName(value: String): String = when (value.trim().uppercase()) {
        "HEVC" -> "H265"
        else -> value.trim().uppercase()
    }
}
