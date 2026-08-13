package dev.gfn.stream

import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionInfo

enum class VideoCodecPreference {
    H264,
    Hevc,
    Av1,
}

data class StreamConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val codec: VideoCodecPreference = VideoCodecPreference.H264,
    val colorMode: RequestedColorMode = RequestedColorMode.CompatibilitySdr,
)

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data object Playing : StreamState
    data class Failed(val reason: String) : StreamState
    data object Closed : StreamState
}

interface StreamingEngine {
    val state: StreamState

    suspend fun connect(session: SessionInfo, config: StreamConfig)

    suspend fun disconnect()
}

/**
 * Future Android implementation boundary.
 *
 * H.264/HEVC SDR may use the standard WebRTC decoder path. Main10/HDR can be moved to a
 * direct MediaCodec -> SurfaceView path without changing UI, CloudMatch, or session modules.
 */
interface VideoOutputTarget
