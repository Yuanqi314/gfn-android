package dev.gfn.webrtc

import dev.gfn.stream.VideoCodecPreference

internal data class GfnVideoCodecDecision(
    val codec: VideoCodecPreference,
    val fallbackReason: String? = null,
)

/**
 * v6.1 production codec policy.
 *
 * HEVC is selected only when the original GFN Offer has a candidate compatible with the exact
 * requested local profile. SDR8/Main keeps the proven same-Session H264 fallback. SDR10/Main10 is
 * deliberately strict: falling back to H264 would silently turn a 10-bit Session into SDR8, so the
 * caller disables fallback and fails the Session instead.
 */
internal object GfnVideoCodecNegotiationPolicy {
    fun selectForOffer(
        requested: VideoCodecPreference,
        h264Available: Boolean,
        hevcCompatibleAvailable: Boolean,
        hevcIncompatibilityReason: String? = null,
        allowHevcFallback: Boolean = true,
        hevcProfileLabel: String = "Main",
    ): Result<GfnVideoCodecDecision> = when (requested) {
        VideoCodecPreference.H264 -> {
            if (h264Available) Result.success(GfnVideoCodecDecision(VideoCodecPreference.H264))
            else Result.failure(IllegalStateException("GFN Offer 未包含 H.264 payload type。"))
        }
        VideoCodecPreference.Hevc -> when {
            hevcCompatibleAvailable -> Result.success(GfnVideoCodecDecision(VideoCodecPreference.Hevc))
            allowHevcFallback && h264Available -> Result.success(
                GfnVideoCodecDecision(
                    VideoCodecPreference.H264,
                    "GFN 原始 HEVC $hevcProfileLabel/High Offer 与本机 production capability 不兼容：" +
                        (hevcIncompatibilityReason ?: "unknown reason") + "；同 Session 回退 H264。",
                ),
            )
            else -> Result.failure(
                IllegalStateException(
                    "请求 HEVC $hevcProfileLabel，但 GFN 原始 Offer 与本机 production capability 无兼容交集" +
                        (if (allowHevcFallback) "，且 Offer 也没有 H.264 fallback" else "；该模式禁止降级为 H264") +
                        "：${hevcIncompatibilityReason ?: "unknown reason"}",
                ),
            )
        }
        VideoCodecPreference.Av1 ->
            Result.failure(IllegalStateException("v6.1.0 仅开放 H.264 / HEVC Main SDR8 / HEVC Main10 SDR10；requested=Av1。"))
    }

    fun selectAfterAnswer(
        selected: VideoCodecPreference,
        h264Available: Boolean,
        hevcMainAvailable: Boolean,
        allowHevcFallback: Boolean = true,
        hevcProfileLabel: String = "Main",
    ): Result<GfnVideoCodecDecision> = when (selected) {
        VideoCodecPreference.H264 -> {
            if (h264Available) Result.success(GfnVideoCodecDecision(VideoCodecPreference.H264))
            else Result.failure(IllegalStateException("libwebrtc Answer 未接受 H.264。"))
        }
        VideoCodecPreference.Hevc -> when {
            hevcMainAvailable -> Result.success(GfnVideoCodecDecision(VideoCodecPreference.Hevc))
            allowHevcFallback && h264Available -> Result.success(
                GfnVideoCodecDecision(
                    VideoCodecPreference.H264,
                    "libwebrtc createAnswer 未接受与原始 Offer 绑定的 HEVC $hevcProfileLabel；同 Session 回退 H264。",
                ),
            )
            else -> Result.failure(
                IllegalStateException(
                    "libwebrtc Answer 缺少 HEVC $hevcProfileLabel" +
                        (if (allowHevcFallback) " 且无 H.264 fallback。" else "；该模式禁止降级为 H264。"),
                ),
            )
        }
        VideoCodecPreference.Av1 ->
            Result.failure(IllegalStateException("v6.1.0 Answer policy 不接受 AV1。"))
    }
}
