package dev.gfn.session

import dev.gfn.core.model.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionReadinessTrackerTest {
    @Test
    fun requiresTwoConsecutiveReadyResponses() {
        val tracker = SessionReadinessTracker(requiredReadyResponses = 2)
        val session = SessionInfo(sessionId = "s", status = 2)
        assertEquals(SessionReadinessState.Preparing, tracker.observe(session, 1_000))
        assertEquals(SessionReadinessState.Ready, tracker.observe(session, 2_000))
    }

    @Test
    fun queuedSessionReportsPosition() {
        val tracker = SessionReadinessTracker()
        val state = tracker.observe(
            SessionInfo(sessionId = "s", status = 1, queuePosition = 7),
            1_000,
        )
        assertEquals(SessionReadinessState.InQueue(7), state)
    }
}
