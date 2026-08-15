#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/reconnect-check"
rm -rf "$BUILD"
mkdir -p "$BUILD/session" "$BUILD/stream"
COROUTINES_JAR="/root/.sdkman/candidates/kotlin/current/lib/kotlinx-coroutines-core-jvm.jar"

if [ ! -f "$COROUTINES_JAR" ]; then
    echo "ERROR: coroutines jar missing: $COROUTINES_JAR" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Session-side same-ID reclaim fixture.
# ---------------------------------------------------------------------------
cat > "$BUILD/session/AndroidStubs.kt" <<'KT'
package android.content
import java.io.File
abstract class Context {
    open val applicationContext: Context get() = this
    abstract val noBackupFilesDir: File
}
KT
cat > "$BUILD/session/LogStub.kt" <<'KT'
package android.util
@Suppress("UNUSED_PARAMETER")
object Log {
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String): Int = 0
}
KT
cat > "$BUILD/session/AuthControllerStub.kt" <<'KT'
package dev.gfn.android.auth
import dev.gfn.auth.AuthSession
class AuthController(private var current: AuthSession?) {
    fun currentSession(): AuthSession? = current
    suspend fun refreshForApi(rejectedToken: String? = null): AuthSession? = current
}
KT
cat > "$BUILD/session/CloudMatchStub.kt" <<'KT'
package dev.gfn.cloudmatch

import dev.gfn.core.model.SessionClaimRequest
import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo

sealed class CloudMatchException(message: String) : Exception(message) {
    class Unauthorized : CloudMatchException("unauthorized")
    class Http(val code: Int, message: String) : CloudMatchException(message)
    class ApiStatus(val code: Int, message: String) : CloudMatchException(message)
    class Protocol(message: String) : CloudMatchException(message)
}

class GfnCloudMatchClient {
    var createCount = 0
    var claimCount = 0
    var pollCount = 0
    var stopCount = 0
    var lastClaim: SessionClaimRequest? = null
    lateinit var stableSession: SessionInfo

    suspend fun createSession(request: SessionCreateRequest): SessionInfo {
        createCount += 1
        return stableSession
    }

    suspend fun pollSession(session: SessionInfo, token: String): SessionInfo {
        pollCount += 1
        return stableSession
    }

    suspend fun claimSession(request: SessionClaimRequest): SessionInfo {
        claimCount += 1
        lastClaim = request
        return stableSession
    }

    suspend fun stopSession(session: SessionInfo, token: String) {
        stopCount += 1
    }
}
KT
cat > "$BUILD/session/LocaleStub.kt" <<'KT'
package dev.gfn.identity
object GfnLocale {
    fun keyboardLayoutCode(): String = "zh-CN"
    fun nvidiaCode(): String = "zh_CN"
}
KT
cat > "$BUILD/session/SettingsControllerStub.kt" <<'KT'
package dev.gfn.android.settings

import dev.gfn.core.model.SubscriptionInfo

class GfnStreamSettingsController(var resolved: ResolvedLaunchProfile) {
    var resolveCount: Int = 0
    fun resolveForNewSession(
        subscription: SubscriptionInfo,
        autoKeyboardLayout: String,
        gameLanguage: String,
    ): ResolvedLaunchProfile {
        resolveCount += 1
        return resolved
    }
}
KT
cat > "$BUILD/session/SessionReconnectFixture.kt" <<'KT'
import android.content.Context
import dev.gfn.android.auth.AuthController
import dev.gfn.android.session.AndroidSessionRecordStore
import dev.gfn.android.session.GfnSessionController
import dev.gfn.android.session.SessionUiState
import dev.gfn.android.session.StreamReconnectSessionResult
import dev.gfn.android.settings.GfnStreamSettingsController
import dev.gfn.android.settings.ResolvedLaunchProfile
import dev.gfn.auth.AuthSession
import dev.gfn.auth.AuthTokens
import dev.gfn.auth.AuthUser
import dev.gfn.auth.LoginProvider
import dev.gfn.cloudmatch.GfnCloudMatchClient
import dev.gfn.core.model.EntitledResolution
import dev.gfn.core.model.GameDetail
import dev.gfn.core.model.GameVariant
import dev.gfn.core.model.SessionInfo
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.stream.StreamConfig
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private class FakeContext(override val noBackupFilesDir: File) : Context()

private suspend fun waitUntil(timeoutSteps: Int = 600, predicate: () -> Boolean) {
    repeat(timeoutSteps) {
        if (predicate()) return
        delay(10)
    }
    error("condition timed out")
}

fun main() = runBlocking {
    val profile = ResolvedLaunchProfile(
        streamConfig = StreamConfig(maxBitrateKbps = 100_000),
        keyboardLayout = "en-US",
        gameLanguage = "zh_CN",
        entitlementVerified = true,
    )
    val changedFutureSetting = profile.copy(
        streamConfig = profile.streamConfig.copy(maxBitrateKbps = 20_000),
        keyboardLayout = "zh-CN",
    )
    val authSession = AuthSession(
        tokens = AuthTokens(
            accessToken = "access",
            idToken = "gfn-token",
            expiresAt = Instant.now().plusSeconds(3600),
        ),
        user = AuthUser("u", "User"),
        provider = LoginProvider("idp", "NVIDIA", "NVIDIA", "https://stream.example", 0),
    )
    val cloud = GfnCloudMatchClient().apply {
        stableSession = SessionInfo(
            sessionId = "same-session",
            status = 3,
            serverIp = "1.2.3.4",
            streamingBaseUrl = "https://1.2.3.4",
            signalingUrl = "wss://fresh.example/signaling",
            clientId = "client",
            deviceId = "device",
        )
    }
    val settings = GfnStreamSettingsController(profile)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = GfnSessionController(
        authController = AuthController(authSession),
        cloudMatchClient = cloud,
        recordStore = AndroidSessionRecordStore(FakeContext(Files.createTempDirectory("v521-session-").toFile())),
        streamSettingsController = settings,
        scope = scope,
    )
    val subscription = SubscriptionInfo(
        membershipTier = "TEST",
        entitledResolutions = listOf(EntitledResolution(1920, 1080, 60)),
    )
    controller.startGame(
        GameDetail(appId = "100", title = "Test", variants = listOf(GameVariant("100", "STEAM", "100", true))),
        GameVariant("100", "STEAM", "100", true),
        subscription,
    )
    waitUntil { controller.state.value is SessionUiState.Ready }
    check(cloud.createCount == 1)
    check(settings.resolveCount == 1)
    // Ready is published immediately before the create coroutine returns and clears activeJob.
    // Give that existing lifecycle a deterministic turn before invoking the manual Claim fixture.
    delay(50)

    controller.claimCurrent()
    waitUntil { controller.state.value is SessionUiState.Claimed }
    check(cloud.claimCount == 1)

    // Change what a future Session would resolve to. Reconnect must not read it.
    settings.resolved = changedFutureSetting

    val result = CompletableDeferred<StreamReconnectSessionResult>()
    controller.recoverForStreamReconnect(
        sessionId = "same-session",
        source = "ice.FAILED",
        reconnectAttempt = 1,
    ) { value -> result.complete(value) }
    val recovered = result.await()
    check(recovered is StreamReconnectSessionResult.Recovered)
    recovered as StreamReconnectSessionResult.Recovered
    check(recovered.session.sessionId == "same-session")
    check(recovered.profile == profile)
    check(controller.activeLaunchProfile.value == profile)
    check(settings.resolveCount == 1) { "Reconnect re-read persistent settings" }
    check(cloud.createCount == 1) { "Reconnect created a second Session" }
    check(cloud.claimCount == 2)
    check(cloud.lastClaim?.session?.sessionId == "same-session")
    check(cloud.lastClaim?.keyboardLayout == "en-US")
    check(cloud.lastClaim?.gameLanguage == "zh_CN")
    check(cloud.lastClaim?.audioChannels == 2)

    println("V521_SESSION_SAME_ID_RECLAIM=PASS")
    println("SESSION_ID=${recovered.session.sessionId}")
    println("CREATE_COUNT=${cloud.createCount}")
    println("CLAIM_COUNT=${cloud.claimCount}")
    println("SETTINGS_RESOLVE_COUNT=${settings.resolveCount}")
    println("PROFILE=${recovered.profile.summary}")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 -classpath "$COROUTINES_JAR" \
  "$BUILD/session/AndroidStubs.kt" \
  "$BUILD/session/LogStub.kt" \
  "$BUILD/session/AuthControllerStub.kt" \
  "$BUILD/session/CloudMatchStub.kt" \
  "$BUILD/session/LocaleStub.kt" \
  "$BUILD/session/SettingsControllerStub.kt" \
  "$ROOT/gfn-auth/src/main/kotlin/dev/gfn/auth/AuthContracts.kt" \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$ROOT/gfn-session/src/main/kotlin/dev/gfn/session/SessionOrchestrator.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnKeyboardLayoutCatalog.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/session/AndroidSessionPersistence.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/session/StreamReconnectSessionResult.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/session/GfnSessionController.kt" \
  "$BUILD/session/SessionReconnectFixture.kt" \
  -d "$BUILD/session/check.jar"
kotlin -J-Dfile.encoding=UTF-8 -classpath "$COROUTINES_JAR:$BUILD/session/check.jar" SessionReconnectFixtureKt

# ---------------------------------------------------------------------------
# Streaming-controller bounded recovery behavior with deterministic Android/WebRTC stubs.
# ---------------------------------------------------------------------------
cat > "$BUILD/stream/AndroidStubs.kt" <<'KT'
package android.content
open class Context { open val applicationContext: Context get() = this }
KT
cat > "$BUILD/stream/OsStubs.kt" <<'KT'
package android.os

class Looper private constructor() {
    companion object { private val main = Looper(); fun getMainLooper(): Looper = main }
}

class Handler(looper: Looper) {
    fun post(runnable: Runnable): Boolean { runnable.run(); return true }
    fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean {
        delayed += runnable
        return true
    }
    fun removeCallbacks(runnable: Runnable) { delayed.remove(runnable) }
    companion object {
        private val delayed = mutableListOf<Runnable>()
        fun runAllDelayed() {
            val copy = delayed.toList()
            delayed.clear()
            copy.forEach(Runnable::run)
        }
        fun delayedCount(): Int = delayed.size
    }
}
KT
cat > "$BUILD/stream/LogStub.kt" <<'KT'
package android.util
@Suppress("UNUSED_PARAMETER")
object Log {
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String): Int = 0
}
KT
cat > "$BUILD/stream/WebRtcStubs.kt" <<'KT'
package dev.gfn.webrtc

import android.content.Context
import dev.gfn.core.model.SessionInfo
import dev.gfn.stream.IceDiagnostics
import dev.gfn.stream.InputDiagnostics
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.StreamDiagnostics
import dev.gfn.stream.StreamState
import dev.gfn.stream.VideoDiagnostics

class GfnVideoSurfaceView

data class GfnVideoFrameLivenessSnapshot(
    val windowMs: Long,
    val framesInWindow: Int,
    val lastFrameAgeMs: Long?,
    val renderedFrameSeen: Boolean,
    val lastRenderedFrameAgeMs: Long?,
)

class GfnWebRtcEngine(context: Context, private val listener: Listener) {
    interface Listener {
        fun onUpdated(state: StreamState, diagnostics: StreamDiagnostics)
        fun onServerSessionEnded(sessionId: String, source: String)
        fun onTransportNeedsReconcile(sessionId: String, source: String)
        fun onTransportNeedsReconnect(sessionId: String, source: String, immediate: Boolean)
    }

    var diagnostics: StreamDiagnostics = StreamDiagnostics()
    var currentState: StreamState = StreamState.Idle
    val state: StreamState get() = currentState
    var livenessFrames: Int = 0
    var livenessLastAgeMs: Long? = null
    var renderedFrameSeen: Boolean = false
    var renderedFrameLastAgeMs: Long? = null
    val connectSessionIds = mutableListOf<String>()
    val connectConfigs = mutableListOf<StreamConfig>()
    var prepareReconnectCount = 0
    var disconnectCount = 0

    init { last = this }

    fun connect(session: SessionInfo, config: StreamConfig) {
        connectSessionIds += session.sessionId
        connectConfigs += config
        currentState = StreamState.OpeningSignaling
        listener.onUpdated(currentState, diagnostics)
    }

    fun disconnect() {
        disconnectCount += 1
        currentState = StreamState.Closed
        listener.onUpdated(currentState, diagnostics)
    }
    fun prepareForSessionEnd(onDrained: () -> Unit) { onDrained() }
    fun prepareForReconnect(onDrained: () -> Unit) { prepareReconnectCount += 1; onDrained() }
    fun onActivityResumed() = Unit
    fun onActivityPaused() = Unit
    fun onActivityDestroy() = Unit
    fun onOverlayChanged(open: Boolean) = Unit
    fun onFullscreenExit() = Unit
    fun bindVideoOutput(view: GfnVideoSurfaceView?) = Unit
    fun unbindVideoOutput(view: GfnVideoSurfaceView) = Unit

    fun triggerDisconnected(sessionId: String, source: String) {
        diagnostics = diagnostics.copy(
            ice = diagnostics.ice.copy(
                iceConnectionState = "DISCONNECTED",
                peerConnectionState = if (source.startsWith("pc.")) "DISCONNECTED" else diagnostics.ice.peerConnectionState,
            ),
        )
        listener.onUpdated(currentState, diagnostics)
        listener.onTransportNeedsReconnect(sessionId, source, false)
    }

    fun triggerReconnect(sessionId: String, source: String, immediate: Boolean) {
        listener.onTransportNeedsReconnect(sessionId, source, immediate)
    }

    fun emitConnected() {
        diagnostics = diagnostics.copy(
            ice = diagnostics.ice.copy(iceConnectionState = "CONNECTED", peerConnectionState = "CONNECTED"),
        )
        currentState = StreamState.Connected
        listener.onUpdated(currentState, diagnostics)
    }

    fun emitSteadyFirstFrame() {
        diagnostics = diagnostics.copy(
            ice = IceDiagnostics(iceConnectionState = "CONNECTED", peerConnectionState = "CONNECTED"),
            video = VideoDiagnostics(firstFrameRendered = true),
            input = InputDiagnostics(protocolReady = true, protocolVersion = 3, dataChannelOpen = true),
        )
        currentState = StreamState.FirstFrame
        listener.onUpdated(currentState, diagnostics)
    }

    fun emitRecoveredReady() {
        diagnostics = diagnostics.copy(
            ice = diagnostics.ice.copy(iceConnectionState = "CONNECTED", peerConnectionState = "CONNECTED"),
            video = VideoDiagnostics(firstFrameRendered = true),
            input = InputDiagnostics(protocolReady = true, protocolVersion = 3, dataChannelOpen = true),
        )
        currentState = StreamState.FirstFrame
        listener.onUpdated(currentState, diagnostics)
    }

    fun beginVideoRecoveryLiveness() {
        livenessFrames = 0
        livenessLastAgeMs = null
        renderedFrameSeen = false
        renderedFrameLastAgeMs = null
    }

    fun invalidateVideoRecoveryLiveness() {
        beginVideoRecoveryLiveness()
    }

    fun videoFrameLiveness(windowMs: Long): GfnVideoFrameLivenessSnapshot =
        GfnVideoFrameLivenessSnapshot(
            windowMs,
            livenessFrames,
            livenessLastAgeMs,
            renderedFrameSeen,
            renderedFrameLastAgeMs,
        )

    fun setVideoFrameLiveness(
        frames: Int,
        lastAgeMs: Long?,
        rendered: Boolean,
        renderedLastAgeMs: Long?,
    ) {
        livenessFrames = frames
        livenessLastAgeMs = lastAgeMs
        renderedFrameSeen = rendered
        renderedFrameLastAgeMs = renderedLastAgeMs
    }

    companion object { lateinit var last: GfnWebRtcEngine }
}
KT
cat > "$BUILD/stream/StreamReconnectFixture.kt" <<'KT'
import android.content.Context
import android.os.Handler
import dev.gfn.android.session.StreamReconnectSessionResult
import dev.gfn.android.settings.ResolvedLaunchProfile
import dev.gfn.android.stream.GfnStreamingController
import dev.gfn.core.model.SessionInfo
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.StreamState
import dev.gfn.webrtc.GfnWebRtcEngine

private class FakeContext : Context()

fun main() {
    val profile = ResolvedLaunchProfile(
        streamConfig = StreamConfig(maxBitrateKbps = 100_000),
        keyboardLayout = "en-US",
        gameLanguage = "zh_CN",
        entitlementVerified = true,
    )
    val original = SessionInfo(
        sessionId = "same-session",
        status = 3,
        signalingUrl = "wss://old",
    )
    val refreshed = original.copy(signalingUrl = "wss://fresh")
    var recoveryCalls = 0
    var lastRecoverySession: String? = null
    var lastRecoveryAttempt = 0
    val controller = GfnStreamingController(
        context = FakeContext(),
        transportRecoverySink = { sessionId, _, attempt, callback ->
            recoveryCalls += 1
            lastRecoverySession = sessionId
            lastRecoveryAttempt = attempt
            callback(StreamReconnectSessionResult.Recovered(refreshed, profile))
        },
    )
    val engine = GfnWebRtcEngine.last
    controller.connectClaimedSession(original, profile)
    check(engine.connectSessionIds == listOf("same-session"))

    engine.emitSteadyFirstFrame()

    // Regression for 51.log: StreamState may remain FirstFrame while ICE is already DISCONNECTED.
    // A stale logical state must never cancel the grace timer immediately.
    engine.triggerDisconnected("same-session", "ice.DISCONNECTED")
    check(controller.state.value is StreamState.Reconnecting)
    check(controller.diagnostics.value.reconnect.active)
    check(Handler.delayedCount() == 1)

    // A genuinely healthy transient recovery must prove transport health, sustained fresh input,
    // and at least one fresh frame that reached the existing renderer path. Input alone is not
    // enough because a missing Surface can still receive VideoSink frames while remaining black.
    engine.emitConnected()
    check(controller.state.value is StreamState.Reconnecting)
    engine.setVideoFrameLiveness(
        frames = profile.streamConfig.fps,
        lastAgeMs = 10,
        rendered = false,
        renderedLastAgeMs = null,
    )
    engine.emitConnected()
    check(controller.state.value is StreamState.Reconnecting)
    engine.setVideoFrameLiveness(
        frames = profile.streamConfig.fps,
        lastAgeMs = 10,
        rendered = true,
        renderedLastAgeMs = 10,
    )
    engine.emitConnected()
    check(controller.state.value is StreamState.Connected)
    check(recoveryCalls == 0)
    check(Handler.delayedCount() == 0)

    // 51.log failure model: ICE/PC can report CONNECTED while video activity is sparse/stalled.
    // The grace timer must rebuild the same Session instead of accepting a black-screen recovery.
    engine.triggerDisconnected("same-session", "ice.DISCONNECTED")
    check(controller.state.value is StreamState.Reconnecting)
    engine.emitConnected()
    engine.setVideoFrameLiveness(
        frames = 5,
        lastAgeMs = 100,
        rendered = true,
        renderedLastAgeMs = 100,
    )
    engine.emitConnected()
    check(controller.state.value is StreamState.Reconnecting)
    Handler.runAllDelayed()
    check(engine.prepareReconnectCount == 1)
    check(recoveryCalls == 1)
    check(lastRecoverySession == "same-session")
    check(lastRecoveryAttempt == 1)
    check(engine.connectSessionIds == listOf("same-session", "same-session"))
    check(engine.connectConfigs.last() == profile.streamConfig)
    check(controller.state.value is StreamState.Reconnecting)
    check(controller.diagnostics.value.reconnect.sameSessionIdVerified)
    check(controller.diagnostics.value.reconnect.frozenProfileVerified)

    // If the freshly reclaimed transport fails, use the bounded retry budget and reclaim
    // the same Session again; never CREATE a replacement Session.
    engine.triggerReconnect("same-session", "pc.FAILED", immediate = true)
    check(Handler.delayedCount() == 1)
    Handler.runAllDelayed()
    check(engine.prepareReconnectCount == 2)
    check(recoveryCalls == 2)
    check(lastRecoveryAttempt == 2)
    check(engine.connectSessionIds == listOf("same-session", "same-session", "same-session"))

    engine.emitRecoveredReady()
    check(controller.state.value is StreamState.FirstFrame)
    check(!controller.diagnostics.value.reconnect.active)
    check(controller.diagnostics.value.video.firstFrameRendered)
    check(controller.diagnostics.value.input.protocolReady)

    println("V521_STREAM_RECONNECT_STATE_MACHINE=PASS")
    println("CONNECT_SESSION_IDS=${engine.connectSessionIds.joinToString()}")
    println("PREPARE_RECONNECT_COUNT=${engine.prepareReconnectCount}")
    println("RECOVERY_CALLS=$recoveryCalls")
    println("FINAL_STATE=${controller.state.value}")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 -classpath "$COROUTINES_JAR" \
  "$BUILD/stream/AndroidStubs.kt" \
  "$BUILD/stream/OsStubs.kt" \
  "$BUILD/stream/LogStub.kt" \
  "$BUILD/stream/WebRtcStubs.kt" \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnKeyboardLayoutCatalog.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/session/StreamReconnectSessionResult.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/stream/GfnStreamingController.kt" \
  "$BUILD/stream/StreamReconnectFixture.kt" \
  -d "$BUILD/stream/check.jar"
kotlin -J-Dfile.encoding=UTF-8 -classpath "$COROUTINES_JAR:$BUILD/stream/check.jar" StreamReconnectFixtureKt

# ---------------------------------------------------------------------------
# Recovery liveness tracker: sustained-input window + generation-scoped rendered witness.
# ---------------------------------------------------------------------------
mkdir -p "$BUILD/liveness"
cat > "$BUILD/liveness/LivenessFixture.kt" <<'KT'
import dev.gfn.webrtc.GfnVideoFrameLivenessTracker

fun main() {
    var nowNs = Long.MAX_VALUE - 150_000_000L
    val tracker = GfnVideoFrameLivenessTracker { nowNs }

    val token1 = tracker.reset()
    repeat(60) {
        tracker.recordFrame()
        nowNs += 10_000_000L
    }
    // The synthetic clock crosses Long.MAX_VALUE here. nanoTime-style subtraction must still
    // classify these short intervals correctly despite the arbitrary/wrapping origin.
    check(tracker.recordRenderedFrame(token1))
    var snapshot = tracker.snapshot(2_000L)
    check(snapshot.framesInWindow == 60)
    check(snapshot.renderedFrameSeen)
    check(snapshot.lastFrameAgeMs == 10L)
    check(snapshot.lastRenderedFrameAgeMs == 0L)

    val token2 = tracker.reset()
    check(!tracker.recordRenderedFrame(token1)) { "stale render witness token was accepted" }
    check(!tracker.snapshot(2_000L).renderedFrameSeen)
    tracker.recordFrame()
    check(tracker.recordRenderedFrame(token2))
    snapshot = tracker.snapshot(2_000L)
    check(snapshot.framesInWindow == 1)
    check(snapshot.renderedFrameSeen)

    // Long sessions remain memory-bounded: only the newest 2048 timestamps are retained.
    tracker.reset()
    repeat(2_100) {
        tracker.recordFrame()
        nowNs += 1_000_000L
    }
    snapshot = tracker.snapshot(Long.MAX_VALUE)
    check(snapshot.framesInWindow == 2_048)

    println("V521_VIDEO_RECOVERY_LIVENESS_TRACKER=PASS")
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoFrameLiveness.kt" \
  "$BUILD/liveness/LivenessFixture.kt" \
  -d "$BUILD/liveness/check.jar"
kotlin -J-Dfile.encoding=UTF-8 -classpath "$BUILD/liveness/check.jar" LivenessFixtureKt

# ---------------------------------------------------------------------------
# Production architecture guards.
# ---------------------------------------------------------------------------
SESSION_SRC="$ROOT/app/src/main/java/dev/gfn/android/session/GfnSessionController.kt"
STREAM_SRC="$ROOT/app/src/main/java/dev/gfn/android/stream/GfnStreamingController.kt"
ENGINE_SRC="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt"

# Session recovery must use claim/RESUME and must not invoke createSession in its method body.
grep -Fq 'fun recoverForStreamReconnect(' "$SESSION_SRC"
grep -Fq 'orchestrator.claimSession(request, sessionAttempt)' "$SESSION_SRC"
grep -Fq 'verifySameReconnectSession(sessionId, reclaimed)' "$SESSION_SRC"
grep -Fq 'RECONNECT_CLAIM sessionId=' "$SESSION_SRC"
RECOVER_BODY=$(sed -n '/fun recoverForStreamReconnect(/,/private fun verifySameReconnectSession/p' "$SESSION_SRC")
if printf '%s\n' "$RECOVER_BODY" | grep -Fq 'createSession('; then
    echo 'ERROR: reconnect recovery method must never create a second Session' >&2
    exit 1
fi

# Frozen profile is owned by the stream/session snapshot; live settings are not read during recovery.
grep -Fq 'result.profile != expectedProfile || result.profile != frozenProfile' "$STREAM_SRC"
if grep -Fq 'GfnStreamSettingsController' "$STREAM_SRC"; then
    echo 'ERROR: live streaming reconnect must not read persistent settings' >&2
    exit 1
fi

# DISCONNECTED grace must not trust stale StreamState alone; it requires fresh media liveness.
grep -Fq 'isTransportHealthy(state: StreamState, diagnostics: StreamDiagnostics)' "$STREAM_SRC"
grep -Fq 'engine.beginVideoRecoveryLiveness()' "$STREAM_SRC"
grep -Fq 'engine.invalidateVideoRecoveryLiveness()' "$STREAM_SRC"
grep -Fq 'engine.videoFrameLiveness(GRACE_MEDIA_WINDOW_MS)' "$STREAM_SRC"
grep -Fq 'grace expired without verified media; rebuilding same Session' "$STREAM_SRC"
grep -Fq 'output.onFrameActivity = videoFrameLivenessTracker::recordFrame' "$ENGINE_SRC"
grep -Fq 'output.armRenderedFrameWitness' "$ENGINE_SRC"
grep -Fq 'liveness.renderedFrameSeen' "$STREAM_SRC"

# Old transport is drained before rebuild, and a new DataChannel handshake path remains present.
grep -Fq 'fun prepareForReconnect(onDrained: () -> Unit)' "$ENGINE_SRC"
grep -Fq 'disconnectWithReason(InputReleaseReason.WebRtcDisconnect, emitClosed = false, onComplete = onDrained)' "$ENGINE_SRC"
grep -Fq 'createExpectedDataChannels(pc, partialReliableThresholdMs, eventGeneration)' "$ENGINE_SRC"
grep -Fq 'GfnInputHandshake.parseProtocolVersion(bytes)' "$ENGINE_SRC"

# Reconnect must reinstall input dispatch on an already-bound video surface after old teardown clears it.
grep -Fq 'videoOutput?.let(::installVideoOutputCallbacksLocked)' "$ENGINE_SRC"
grep -Fq 'private fun installVideoOutputCallbacksLocked(output: GfnVideoSurfaceView)' "$ENGINE_SRC"
grep -Fq 'oldOutput.inputListener = null' "$ENGINE_SRC"

# Terminal server exit must remain non-recoverable.
grep -Fq 'if (generation.get() != eventGeneration || serverEnded) return' "$ENGINE_SRC"
grep -Fq 'listener.onServerSessionEnded(sessionId, "control_channel.exitMessage")' "$ENGINE_SRC"

# Keyboard semantics remain soft-frozen. v5.3 legitimately extends the shared protocol encoder
# with type-12 and VideoSurfaceView with gamepad routing, so byte-compare only the keyboard-specific
# implementation files. Packet semantics are independently covered by verify-keyboard-stable.sh.
BASE_ZIP="$ROOT/../../gfn-android-main-v5.2.1-same-session-reconnect-full-source.zip"
if [ ! -f "$BASE_ZIP" ]; then
    BASE_ZIP="$ROOT/../../gfn-android-main-v5.2.0-stream-settings-foundation-full-source.zip"
fi
if [ -f "$BASE_ZIP" ]; then
    BASE="$BUILD/keyboard-base"
    mkdir -p "$BASE"
    unzip -q "$BASE_ZIP" -d "$BASE"
    for rel in \
      stream-webrtc/src/main/java/dev/gfn/webrtc/AndroidKeyboardMapper.kt \
      stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt \
      stream-webrtc/src/main/java/dev/gfn/webrtc/GfnInputForensics.kt
    do
      cmp "$ROOT/$rel" "$BASE/gfn-android-main/$rel"
    done
    echo 'V530_KEYBOARD_CORE_SOFT_FREEZE_BYTE_IDENTICAL=PASS'
else
    echo 'V530_KEYBOARD_CORE_SOFT_FREEZE_BYTE_IDENTICAL=SKIP_BASE_ZIP_NOT_ADJACENT'
fi
if [ "${GFN_SKIP_UNRELATED_KEYBOARD_BASELINE:-0}" = "1" ]; then
    echo 'V519_KEYBOARD_STABLE_STATIC=SKIP_PREEXISTING_BASELINE_CONFLICT'
else
    sh "$ROOT/verify-keyboard-stable.sh"
fi

echo 'V521_RECONNECT_STATIC_GUARDS=PASS'
sh "$ROOT/verify-reconnect-engine.sh"
