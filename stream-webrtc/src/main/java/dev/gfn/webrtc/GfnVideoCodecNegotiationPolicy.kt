package dev.gfn.webrtc

import dev.gfn.stream.VideoCodecPreference

internal data class GfnVideoCodecDecision(
    val codec: VideoCodecPreference,
    val fallbackReason: String? = null,
)

/**
 * v6.0 production codec policy. HEVC is selected only when the original GFN Offer has a candidate
 * that passed the real Android decoder capability matcher. H.264 remains the same-session fallback.
 */
internal object GfnVideoCodecNegotiationPolicy {
    fun selectForOffer(
        requested: VideoCodecPreference,
        h264Available: Boolean,
        hevcCompatibleAvailable: Boolean,
        hevcIncompatibilityReason: String? = null,
    ): Result<GfnVideoCodecDecision> = when (requested) {
        VideoCodecPreference.H264 -> {
            if (h264Available) Result.success(GfnVideoCodecDecision(VideoCodecPreference.H264))
            else Result.failure(IllegalStateException("GFN Offer 未包含 H.264 payload type。"))
        }
        VideoCodecPreference.Hevc -> when {
            hevcCompatibleAvailable -> Result.success(GfnVideoCodecDecision(VideoCodecPreference.Hevc))
            h264Available -> Result.success(
                GfnVideoCodecDecision(
                    VideoCodecPreference.H264,
                    "GFN 原始 HEVC Main/High Offer 与本机 production capability 不兼容：" +
                        (hevcIncompatibilityReason ?: "unknown reason") + "；同 Session 回退 H264。",
                ),
            )
            else -> Result.failure(
                IllegalStateException(
                    "请求 HEVC Main，但 GFN 原始 Offer 与本机 production capability 无兼容交集，" +
                        "且 Offer 也没有 H.264 fallback：${hevcIncompatibilityReason ?: "unknown reason"}",
                ),
            )
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
                    "libwebrtc createAnswer 未接受与原始 Offer 绑定的 HEVC Main；同 Session 回退 H264。",
                ),
            )
            else -> Result.failure(IllegalStateException("libwebrtc Answer 同时缺少 HEVC Main 与 H.264 fallback。"))
        }
        VideoCodecPreference.Av1 ->
            Result.failure(IllegalStateException("v6.0 Answer policy 不接受 AV1。"))
    }
}
