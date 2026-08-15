package dev.gfn.webrtc

import java.util.ArrayDeque

/**
 * Bounded recovery-media witness used only by reconnect health checks.
 *
 * [recordFrame] observes frame arrival at the existing VideoSink boundary without retaining,
 * releasing, mutating, or transforming the frame. A separate generation-scoped rendered-frame
 * witness is recorded only after the existing SurfaceViewRenderer render path reaches its one-shot
 * frame-listener callback. Reconnect recovery therefore requires both sustained fresh input and a
 * fresh render-path witness; neither signal alone is treated as proof that a black screen recovered.
 */
data class GfnVideoFrameLivenessSnapshot(
    val windowMs: Long,
    val framesInWindow: Int,
    val lastFrameAgeMs: Long?,
    val renderedFrameSeen: Boolean,
    val lastRenderedFrameAgeMs: Long?,
)

class GfnVideoFrameLivenessTracker(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val frameTimesNs = ArrayDeque<Long>()
    private var generation = 0L
    private var renderedFrameNs: Long? = null

    fun recordFrame() {
        val nowNs = nanoTime()
        synchronized(lock) {
            frameTimesNs.addLast(nowNs)
            while (frameTimesNs.size > MAX_RETAINED_FRAME_TIMESTAMPS) {
                frameTimesNs.removeFirst()
            }
        }
    }

    /**
     * Invalidates every previously armed render witness, clears old frame history, and returns a
     * token that can be used by exactly this recovery window.
     */
    fun reset(): Long = synchronized(lock) {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        frameTimesNs.clear()
        renderedFrameNs = null
        generation
    }

    /**
     * Records a render-path witness only if [token] still belongs to the current recovery window.
     * Stale callbacks from an old Surface/reconnect generation are ignored.
     */
    fun recordRenderedFrame(token: Long): Boolean {
        val nowNs = nanoTime()
        return synchronized(lock) {
            if (token != generation) {
                false
            } else {
                if (renderedFrameNs == null) renderedFrameNs = nowNs
                true
            }
        }
    }

    fun snapshot(windowMs: Long): GfnVideoFrameLivenessSnapshot {
        require(windowMs > 0L) { "windowMs must be > 0" }
        val nowNs = nanoTime()
        val windowNs = millisToNanosSaturated(windowMs)
        synchronized(lock) {
            var count = 0
            for (timestampNs in frameTimesNs) {
                val ageNs = nowNs - timestampNs
                if (ageNs >= 0L && ageNs <= windowNs) count += 1
            }
            val lastFrameAgeMs = frameTimesNs.lastOrNull()?.let { timestampNs ->
                ageMillis(nowNs, timestampNs)
            }
            val renderedNs = renderedFrameNs
            val lastRenderedFrameAgeMs = renderedNs?.let { timestampNs ->
                ageMillis(nowNs, timestampNs)
            }
            return GfnVideoFrameLivenessSnapshot(
                windowMs = windowMs,
                framesInWindow = count,
                lastFrameAgeMs = lastFrameAgeMs,
                renderedFrameSeen = renderedNs != null,
                lastRenderedFrameAgeMs = lastRenderedFrameAgeMs,
            )
        }
    }

    private fun ageMillis(nowNs: Long, timestampNs: Long): Long {
        val ageNs = nowNs - timestampNs
        // System.nanoTime() is monotonic but has an arbitrary origin and may wrap. For the short
        // reconnect windows used here, subtraction is the supported comparison model. A negative
        // value can only mean an out-of-order/synthetic future timestamp, which is treated as age 0.
        return if (ageNs < 0L) 0L else ageNs / NANOS_PER_MILLISECOND
    }

    private fun millisToNanosSaturated(valueMs: Long): Long =
        if (valueMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) Long.MAX_VALUE
        else valueMs * NANOS_PER_MILLISECOND

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_RETAINED_FRAME_TIMESTAMPS = 2_048
    }
}
