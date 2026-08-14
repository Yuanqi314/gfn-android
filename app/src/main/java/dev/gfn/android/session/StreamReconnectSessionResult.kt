package dev.gfn.android.session

import dev.gfn.android.settings.ResolvedLaunchProfile
import dev.gfn.core.model.SessionInfo

/**
 * v5.2.1 WebRTC transport recovery -> CloudMatch same-session reclaim result.
 *
 * Reconnect is never allowed to create a replacement Session. A successful result must
 * preserve both the original Session ID and the immutable ResolvedLaunchProfile snapshot.
 */
sealed interface StreamReconnectSessionResult {
    data class Recovered(
        val session: SessionInfo,
        val profile: ResolvedLaunchProfile,
    ) : StreamReconnectSessionResult

    data class RetryableFailure(val reason: String) : StreamReconnectSessionResult

    data class SessionEnded(val reason: String) : StreamReconnectSessionResult
}
