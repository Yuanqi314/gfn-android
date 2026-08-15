#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/main10-check"
rm -rf "$BUILD"
mkdir -p "$BUILD"

SETTINGS="$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt"
STORE="$ROOT/app/src/main/java/dev/gfn/android/settings/AndroidStreamSettingsStore.kt"
CLOUDMATCH="$ROOT/gfn-cloudmatch/src/main/kotlin/dev/gfn/cloudmatch/CloudMatchProtocol.kt"
ENGINE="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt"
FACTORY="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevcProductionCapability.kt"
SIGNALING="$ROOT/stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt"
UI="$ROOT/app/src/main/java/dev/gfn/android/ui/GfnAndroidApp.kt"

# Cross-layer invariants for v6.1.0 Main10/SDR10 negotiation.
grep -Fq 'PreferSdr10' "$SETTINGS" || { echo 'ERROR: Settings do not expose SDR10' >&2; exit 1; }
grep -Fq 'KEY_COLOR_MODE' "$STORE" || { echo 'ERROR: color mode is not persisted for the next Session' >&2; exit 1; }
grep -Fq 'request.requestedColorMode == RequestedColorMode.PreferSdr10' "$CLOUDMATCH" || { echo 'ERROR: CloudMatch does not map SDR10 request' >&2; exit 1; }
grep -Fq '"bitDepth" to if (tenBitRequested) 1 else 0' "$CLOUDMATCH" || { echo 'ERROR: CloudMatch SDR10 bitDepth mapping missing' >&2; exit 1; }
grep -Fq '"sdrHdrMode" to 0' "$CLOUDMATCH" || { echo 'ERROR: v6.1.0 must keep HDR Session mode off' >&2; exit 1; }
grep -Fq '"clientDisplayHdrCapabilities" to null' "$CLOUDMATCH" || { echo 'ERROR: v6.1.0 must keep HDR display activation null' >&2; exit 1; }
grep -Fq 'Main10("2", "Main10")' "$FACTORY" || { echo 'ERROR: explicit Main10 profile model missing' >&2; exit 1; }
grep -Fq 'HEVCProfileMain10' "$FACTORY" || { echo 'ERROR: Android Main10 profile probe missing' >&2; exit 1; }
grep -Fq 'matchingAnswerHevcMain10PayloadTypes' "$SIGNALING" || { echo 'ERROR: Main10 Answer lineage helper missing' >&2; exit 1; }
grep -Fq 'allowHevcFallback(config' "$ENGINE" || { echo 'ERROR: Main10 strict fallback policy missing' >&2; exit 1; }
grep -Fq 'bitDepth = nvstBitDepth' "$ENGINE" || { echo 'ERROR: NVST does not carry frozen Session bit depth' >&2; exit 1; }
grep -Fq 'phase=NVST_CONFIG' "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevcNegotiationCompat.kt" || { echo 'ERROR: NVST bit-depth diagnostics missing' >&2; exit 1; }
grep -Fq 'HDR Session 仍关闭' "$UI" || { echo 'ERROR: UI does not preserve Main10/HDR separation' >&2; exit 1; }
if grep -Eq 'HEVCProfileMain10HDR10|HEVCProfileMain10HDR10Plus' "$FACTORY"; then
  echo 'ERROR: HDR-only decoder profiles must not activate v6.1.0 SDR10 capability' >&2
  exit 1
fi
printf '%s\n' 'V610_MAIN10_CROSS_LAYER_SOURCE_GUARDS=PASS'

cat > "$BUILD/KeyboardStub.kt" <<'KT'
package dev.gfn.android.settings
object GfnKeyboardLayoutCatalog {
    const val DEFAULT = "en-US"
    const val AUTO = "auto"
    fun normalize(value: String?) = value ?: DEFAULT
}
KT

cat > "$BUILD/SettingsProbe.kt" <<'KT'
import dev.gfn.android.settings.GfnStreamSettingsCatalog
import dev.gfn.android.settings.GfnStreamSettingsResolver
import dev.gfn.android.settings.PersistentStreamSettings
import dev.gfn.core.model.EntitledResolution
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.stream.VideoCodecPreference

fun main() {
    val normalizedMain10 = GfnStreamSettingsCatalog.normalize(
        PersistentStreamSettings(videoCodec = VideoCodecPreference.Hevc, colorMode = RequestedColorMode.PreferSdr10),
    )
    check(normalizedMain10.videoCodec == VideoCodecPreference.Hevc)
    check(normalizedMain10.colorMode == RequestedColorMode.PreferSdr10)

    val invalidH264Sdr10 = GfnStreamSettingsCatalog.normalize(
        PersistentStreamSettings(videoCodec = VideoCodecPreference.H264, colorMode = RequestedColorMode.PreferSdr10),
    )
    check(invalidH264Sdr10.colorMode == RequestedColorMode.CompatibilitySdr)

    val profile = GfnStreamSettingsResolver.resolve(
        persistent = normalizedMain10,
        subscription = SubscriptionInfo(
            membershipTier = "fixture",
            entitledResolutions = listOf(EntitledResolution(1920, 1080, 60)),
        ),
        autoKeyboardLayout = "en-US",
        gameLanguage = "en_US",
    )
    check(profile.streamConfig.codec == VideoCodecPreference.Hevc)
    check(profile.streamConfig.colorMode == RequestedColorMode.PreferSdr10)
    check(profile.streamConfig.width == 1920 && profile.streamConfig.height == 1080 && profile.streamConfig.fps == 60)
    println("V610_MAIN10_SETTINGS_SNAPSHOT_FIXTURE=PASS")
    println("PROFILE=${profile.summary}")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$BUILD/KeyboardStub.kt" "$SETTINGS" "$BUILD/SettingsProbe.kt" \
  -include-runtime -d "$BUILD/settings.jar"
java -jar "$BUILD/settings.jar"

cat > "$BUILD/IdentityStub.kt" <<'KT'
package dev.gfn.identity

data class GfnClientIdentity(
    val clientIdentification: String = "GFN-PC",
    val clientPlatformName: String = "windows",
    val deviceMake: String = "UNKNOWN",
    val deviceModel: String = "UNKNOWN",
    val deviceOs: String = "WINDOWS",
    val deviceType: String = "DESKTOP",
) { companion object { val WindowsDesktop = GfnClientIdentity() } }
object GfnProtocolDefaults {
    const val clientVersion = "2.0.77.175"
    const val userAgent = "fixture"
    const val webOrigin = "https://play.geforcenow.com"
    const val webReferer = "https://play.geforcenow.com/"
}
KT

cat > "$BUILD/SessionStub.kt" <<'KT'
package dev.gfn.session
import dev.gfn.core.model.SessionClaimRequest
import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo
interface CloudMatchPort {
    suspend fun createSession(request: SessionCreateRequest): SessionInfo
    suspend fun pollSession(session: SessionInfo, token: String): SessionInfo
    suspend fun claimSession(request: SessionClaimRequest): SessionInfo
    suspend fun stopSession(session: SessionInfo, token: String)
}
KT

cat > "$BUILD/CloudMatchProbe.kt" <<'KT'
import dev.gfn.cloudmatch.CloudMatchException
import dev.gfn.cloudmatch.GfnCloudMatchClient
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.network.HttpRequest
import dev.gfn.network.HttpResponse
import dev.gfn.network.HttpTransport
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

private class CaptureTransport : HttpTransport {
    var last: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        last = request
        return HttpResponse(statusCode = 500, body = "{}".toByteArray())
    }
}

private fun runNow(block: suspend () -> Unit): Result<Unit> {
    var completed: Result<Unit>? = null
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) { completed = result }
    })
    return requireNotNull(completed) { "fixture unexpectedly suspended" }
}

private fun request(mode: RequestedColorMode) = SessionCreateRequest(
    appId = "fixture-app",
    token = "fixture-token",
    streamingBaseUrl = "https://fixture.invalid",
    width = 1920,
    height = 1080,
    fps = 60,
    requestedColorMode = mode,
)

fun main() {
    val transport = CaptureTransport()
    val client = GfnCloudMatchClient(transport, deviceId = { "fixture-device" })
    val result = runNow { client.createSession(request(RequestedColorMode.PreferSdr10)); Unit }
    check(result.exceptionOrNull() is CloudMatchException.Http)
    val body = requireNotNull(transport.last?.body).toString(Charsets.UTF_8)
    check("\"bitDepth\":1" in body) { body }
    check("\"sdrHdrMode\":0" in body) { body }
    check("\"clientDisplayHdrCapabilities\":null" in body) { body }
    check("\"chromaFormat\":1" in body) { body }

    transport.last = null
    val hdr = runNow { client.createSession(request(RequestedColorMode.PreferHdr10)); Unit }
    check(hdr.exceptionOrNull() is IllegalArgumentException)
    check(transport.last == null) { "HDR request reached transport" }

    println("V610_MAIN10_CLOUDMATCH_SDR10_FIXTURE=PASS")
    println("V610_HDR_SESSION_ACTIVATION_OFF=PASS")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/HttpTransport.kt" \
  "$BUILD/IdentityStub.kt" "$BUILD/SessionStub.kt" \
  "$CLOUDMATCH" "$BUILD/CloudMatchProbe.kt" \
  -include-runtime -d "$BUILD/cloudmatch.jar"
java -jar "$BUILD/cloudmatch.jar"

printf '%s\n' 'V610_MAIN10_VERIFY=PASS'
