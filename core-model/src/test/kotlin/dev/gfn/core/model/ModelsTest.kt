package dev.gfn.core.model

import kotlin.test.Test
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun positiveQueuePositionMeansQueued() {
        assertTrue(SessionInfo(sessionId = "s", status = 1, queuePosition = 3).isInQueue)
    }
}
