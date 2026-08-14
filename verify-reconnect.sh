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
import dev.gfn.stream.InputDiagnostics
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.StreamDiagnostics
import dev.gfn.stream.StreamState
import dev.gfn.stream.VideoDiagnostics

class GfnVideoSurfaceView

class GfnWebRtcEngine(context: Context, private val listener: Listener) {
    interface Listener {
        fun onUpdated(state: StreamState, diagnostics: StreamDiagnostics)
        fun onServerSessionEnded(sessionId: String, source: String)
        fun onTransportNeedsReconcile(sessionId: String, source: String)
        fun onTransportNeedsReconnect(sessionId: String, source: String, immediate: Boolean)
    }

    var diagnostics: StreamDiagnostics = StreamDiagnostics()
    val connectSessionIds = mutableListOf<String>()
    val connectConfigs = mutableListOf<StreamConfig>()
    var prepareReconnectCount = 0
    var disconnectCount = 0

    init { last = this }

    fun connect(session: SessionInfo, config: StreamConfig) {
        connectSessionIds += session.sessionId
        connectConfigs += config
        listener.onUpdated(StreamState.OpeningSignaling, diagnostics)
    }

    fun disconnect() { disconnectCount += 1; listener.onUpdated(StreamState.Closed, diagnostics) }
    fun prepareForSessionEnd(onDrained: () -> Unit) { onDrained() }
    fun prepareForReconnect(onDrained: () -> Unit) { prepareReconnectCount += 1; onDrained() }
    fun onActivityResumed() = Unit
    fun onActivityPaused() = Unit
    fun onActivityDestroy() = Unit
    fun onOverlayChanged(open: Boolean) = Unit
    fun onFullscreenExit() = Unit
    fun bindVideoOutput(view: GfnVideoSurfaceView?) = Unit
    fun unbindVideoOutput(view: GfnVideoSurfaceView) = Unit

    fun triggerReconnect(sessionId: String, source: String, immediate: Boolean) {
        listener.onTransportNeedsReconnect(sessionId, source, immediate)
    }

    fun emitConnected() {
        listener.onUpdated(StreamState.Connected, diagnostics)
    }

    fun emitRecoveredReady() {
        diagnostics = diagnostics.copy(
            video = VideoDiagnostics(firstFrameRendered = true),
            input = InputDiagnostics(protocolReady = true, protocolVersion = 3, dataChannelOpen = true),
        )
        listener.onUpdated(StreamState.FirstFrame, diagnostics)
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

    // Transient DISCONNECTED heals before the 7s grace: no CloudMatch reclaim.
    engine.triggerReconnect("same-session", "ice.DISCONNECTED", immediate = false)
    check(controller.state.value is StreamState.Reconnecting)
    check(Handler.delayedCount() == 1)
    engine.emitConnected()
    check(controller.state.value is StreamState.Connected)
    check(recoveryCalls == 0)
    check(Handler.delayedCount() == 0)

    // Hard failure: same-session reclaim + new WebRTC generation.
    engine.triggerReconnect("same-session", "ice.FAILED", immediate = true)
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

# Keyboard packet semantics remain soft-frozen: compare the five production files against v5.2 source if available.
BASE_ZIP="$ROOT/../../gfn-android-main-v5.2.0-stream-settings-foundation-full-source.zip"
if [ -f "$BASE_ZIP" ]; then
    BASE="$BUILD/v520-base"
    mkdir -p "$BASE"
    unzip -q "$BASE_ZIP" -d "$BASE"
    for rel in \
      stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt \
      stream-webrtc/src/main/java/dev/gfn/webrtc/AndroidKeyboardMapper.kt \
      stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt \
      stream-webrtc/src/main/java/dev/gfn/webrtc/GfnInputForensics.kt \
      stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoSurfaceView.kt
    do
      cmp "$ROOT/$rel" "$BASE/gfn-android-main/$rel"
    done
    echo 'V521_KEYBOARD_SOFT_FREEZE_BYTE_IDENTICAL=PASS'
else
    echo 'V521_KEYBOARD_SOFT_FREEZE_BYTE_IDENTICAL=SKIP_BASE_ZIP_NOT_ADJACENT'
fi

echo 'V521_RECONNECT_STATIC_GUARDS=PASS'
"$ROOT/verify-reconnect-engine.sh"
