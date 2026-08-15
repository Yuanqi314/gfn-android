#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/stream-settings-check"
rm -rf "$BUILD"
mkdir -p "$BUILD/resolver" "$BUILD/store" "$BUILD/record" "$BUILD/controller"
COROUTINES_JAR="/root/.sdkman/candidates/kotlin/current/lib/kotlinx-coroutines-core-jvm.jar"

cat > "$BUILD/resolver/StreamSettingsFixture.kt" <<'KT'
import dev.gfn.android.settings.GfnKeyboardLayoutCatalog
import dev.gfn.android.settings.GfnStreamSettingsCatalog
import dev.gfn.android.settings.GfnStreamSettingsResolver
import dev.gfn.android.settings.PersistentStreamSettings
import dev.gfn.core.model.EntitledResolution
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.stream.StreamCapabilityProfiles
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.VideoCodecPreference

fun main() {
    val subscription = SubscriptionInfo(
        membershipTier = "TEST",
        entitledResolutions = listOf(EntitledResolution(1920, 1080, 60)),
    )
    val normalized = GfnStreamSettingsCatalog.normalize(
        PersistentStreamSettings(
            keyboardLayoutSelection = "en-US",
            maxBitrateKbps = 33_000,
        ),
    )
    check(normalized.maxBitrateKbps == 35_000)
    val profile = GfnStreamSettingsResolver.resolve(
        persistent = normalized,
        subscription = subscription,
        autoKeyboardLayout = "zh-CN",
        gameLanguage = "zh_CN",
    )
    check(profile.streamConfig == StreamConfig(maxBitrateKbps = 35_000))
    check(profile.keyboardLayout == "en-US")
    check(profile.gameLanguage == "zh_CN")
    check(profile.entitlementVerified)

    val autoProfile = GfnStreamSettingsResolver.resolve(
        persistent = normalized.copy(keyboardLayoutSelection = GfnKeyboardLayoutCatalog.AUTO),
        subscription = subscription,
        autoKeyboardLayout = "zh-CN",
        gameLanguage = "zh_CN",
    )
    check(autoProfile.keyboardLayout == "zh-CN")

    val unknownEntitlement = GfnStreamSettingsResolver.resolve(
        persistent = normalized,
        subscription = SubscriptionInfo(membershipTier = "UNKNOWN"),
        autoKeyboardLayout = "zh-CN",
        gameLanguage = "zh_CN",
    )
    check(!unknownEntitlement.entitlementVerified)

    val mismatch = runCatching {
        GfnStreamSettingsResolver.resolve(
            persistent = normalized,
            subscription = SubscriptionInfo(
                membershipTier = "TEST",
                entitledResolutions = listOf(EntitledResolution(1280, 720, 60)),
            ),
            autoKeyboardLayout = "zh-CN",
            gameLanguage = "zh_CN",
        )
    }
    check(mismatch.isFailure)

    check(StreamCapabilityProfiles.V52_ANDROID_WEBRTC.rejectionReason(StreamConfig()) == null)
    check(
        StreamCapabilityProfiles.V52_ANDROID_WEBRTC.rejectionReason(
            StreamConfig(codec = VideoCodecPreference.Hevc),
        ) != null,
    )

    println("V520_STREAM_SETTINGS_RESOLVER=PASS")
    println("PROFILE=${profile.summary}")
    println("AUTO_KEYBOARD=${autoProfile.keyboardLayout}")
    println("ENTITLEMENT_MISMATCH_BLOCKED=${mismatch.isFailure}")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnKeyboardLayoutCatalog.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt" \
  "$BUILD/resolver/StreamSettingsFixture.kt" \
  -include-runtime -d "$BUILD/resolver/check.jar"
java -Dfile.encoding=UTF-8 -jar "$BUILD/resolver/check.jar"

cat > "$BUILD/store/AndroidContentStub.kt" <<'KT'
package android.content

import java.io.File

abstract class Context {
    open val applicationContext: Context get() = this
    open val noBackupFilesDir: File get() = File(".")
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    companion object { const val MODE_PRIVATE: Int = 0 }
}

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun apply()
    }
}
KT

cat > "$BUILD/store/StoreFixture.kt" <<'KT'
import android.content.Context
import android.content.SharedPreferences
import dev.gfn.android.settings.AndroidStreamSettingsStore
import dev.gfn.android.settings.PersistentStreamSettings
import java.io.File
import java.nio.file.Files

private class MemoryPrefs : SharedPreferences {
    val values = linkedMapOf<String, Any?>()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor { values[key] = value; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { values[key] = value; return this }
        override fun apply() = Unit
    }
}

private class FakeContext(private val prefs: MemoryPrefs) : Context() {
    override val noBackupFilesDir: File = Files.createTempDirectory("gfn-v520-store-").toFile()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
}

fun main() {
    val prefs = MemoryPrefs().apply {
        values["keyboardLayoutSelection"] = "en-US"
    }
    val store = AndroidStreamSettingsStore(FakeContext(prefs))
    val migrated = store.load()
    check(migrated.keyboardLayoutSelection == "en-US")
    check(migrated.maxBitrateKbps == 20_000)
    check(migrated.resolutionSelection == "auto")
    check(migrated.fpsSelection == 0)
    check(migrated.audioChannels == 2)

    val saved = store.save(
        PersistentStreamSettings(
            keyboardLayoutSelection = "zh-CN",
            resolutionSelection = "1920x1080",
            fpsSelection = 60,
            maxBitrateKbps = 37_000,
            audioChannels = 2,
        ),
    )
    check(saved.maxBitrateKbps == 35_000)
    check(store.load() == saved)
    println("V520_STREAM_SETTINGS_STORE=PASS")
    println("MIGRATED_KEYBOARD=${migrated.keyboardLayoutSelection}")
    println("RELOADED=$saved")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$BUILD/store/AndroidContentStub.kt" \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnKeyboardLayoutCatalog.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/AndroidStreamSettingsStore.kt" \
  "$BUILD/store/StoreFixture.kt" \
  -include-runtime -d "$BUILD/store/check.jar"
java -Dfile.encoding=UTF-8 -jar "$BUILD/store/check.jar"

cat > "$BUILD/record/AndroidContentStub.kt" <<'KT'
package android.content

import java.io.File

abstract class Context {
    open val applicationContext: Context get() = this
    abstract val noBackupFilesDir: File
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    companion object { const val MODE_PRIVATE: Int = 0 }
}

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun apply()
    }
}
KT

cat > "$BUILD/record/RecordFixture.kt" <<'KT'
import android.content.Context
import android.content.SharedPreferences
import dev.gfn.android.session.AndroidSessionRecordStore
import dev.gfn.android.session.PersistedSessionRecord
import dev.gfn.android.settings.ResolvedLaunchProfile
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.VideoCodecPreference
import java.io.File
import java.nio.file.Files
import java.util.Properties

private class NoPrefs : SharedPreferences {
    override fun getString(key: String, defValue: String?): String? = defValue
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun edit(): SharedPreferences.Editor = error("unused")
}
private class FakeContext(override val noBackupFilesDir: File) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = NoPrefs()
}

fun main() {
    val dir = Files.createTempDirectory("gfn-v520-record-").toFile()
    val store = AndroidSessionRecordStore(FakeContext(dir))
    val profile = ResolvedLaunchProfile(
        streamConfig = StreamConfig(
            width = 1920,
            height = 1080,
            fps = 60,
            maxBitrateKbps = 35_000,
            codec = VideoCodecPreference.H264,
            colorMode = RequestedColorMode.CompatibilitySdr,
            audioChannels = 2,
        ),
        keyboardLayout = "en-US",
        gameLanguage = "zh_CN",
        entitlementVerified = true,
    )
    val record = PersistedSessionRecord(
        sessionId = "s1",
        appId = "100",
        gameTitle = "Test",
        appStore = "STEAM",
        status = 3,
        serverIp = "1.2.3.4",
        streamingBaseUrl = "https://1.2.3.4",
        routingZoneUrl = null,
        clientId = "c1",
        deviceId = "d1",
        createdAtEpochMillis = 123L,
        keyboardLayout = profile.keyboardLayout,
        gameLanguage = profile.gameLanguage,
        launchProfile = profile,
    )
    store.save(record)
    val loaded = checkNotNull(store.load())
    check(loaded.launchProfile == profile)

    val props = Properties().apply {
        setProperty("sessionId", "legacy")
        setProperty("appId", "100")
        setProperty("gameTitle", "Legacy")
        setProperty("appStore", "STEAM")
        setProperty("status", "3")
        setProperty("serverIp", "")
        setProperty("streamingBaseUrl", "https://legacy")
        setProperty("routingZoneUrl", "")
        setProperty("clientId", "c2")
        setProperty("deviceId", "d2")
        setProperty("createdAtEpochMillis", "1")
        setProperty("keyboardLayout", "en-US")
        setProperty("gameLanguage", "en_US")
    }
    File(dir, "gfn-session-v4.properties").outputStream().use { props.store(it, "legacy") }
    val legacy = checkNotNull(store.load())
    check(legacy.launchProfile == null)

    println("V520_SESSION_PROFILE_PERSISTENCE=PASS")
    println("PROFILE=${checkNotNull(loaded.launchProfile).summary}")
    println("LEGACY_PROFILE_NULL=true")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$BUILD/record/AndroidContentStub.kt" \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnKeyboardLayoutCatalog.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/session/AndroidSessionPersistence.kt" \
  "$BUILD/record/RecordFixture.kt" \
  -include-runtime -d "$BUILD/record/check.jar"
java -Dfile.encoding=UTF-8 -jar "$BUILD/record/check.jar"

# Compile the production settings controller itself with minimal Android stubs.
cat > "$BUILD/controller/AndroidStubs.kt" <<'KT'
package android.content

import java.io.File

abstract class Context {
    open val applicationContext: Context get() = this
    open val noBackupFilesDir: File get() = File(".")
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    companion object { const val MODE_PRIVATE: Int = 0 }
}

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun apply()
    }
}
KT
cat > "$BUILD/controller/AndroidLogStub.kt" <<'KT'
package android.util
@Suppress("UNUSED_PARAMETER")
object Log {
    fun i(tag: String, msg: String): Int = 0
}
KT

if [ ! -f "$COROUTINES_JAR" ]; then
    echo "ERROR: coroutines jar missing: $COROUTINES_JAR" >&2
    exit 1
fi
kotlinc -J-Dfile.encoding=UTF-8 -classpath "$COROUTINES_JAR" \
  "$BUILD/controller/AndroidStubs.kt" \
  "$BUILD/controller/AndroidLogStub.kt" \
  "$ROOT/core-model/src/main/kotlin/dev/gfn/core/model/Models.kt" \
  "$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnKeyboardLayoutCatalog.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/AndroidStreamSettingsStore.kt" \
  "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettingsController.kt" \
  -d "$BUILD/controller/check.jar"
test -s "$BUILD/controller/check.jar"
echo 'V520_STREAM_SETTINGS_CONTROLLER_COMPILE=PASS'

# Architectural guards: live Session/WebRTC must consume a resolved snapshot rather than Settings.
grep -Fq 'data class ResolvedLaunchProfile' "$ROOT/app/src/main/java/dev/gfn/android/settings/GfnStreamSettings.kt"
grep -Fq 'streamSettingsController.resolveForNewSession' "$ROOT/app/src/main/java/dev/gfn/android/session/GfnSessionController.kt"
grep -Fq 'launchProfile = active.profile' "$ROOT/app/src/main/java/dev/gfn/android/session/GfnSessionController.kt"
grep -Fq 'val launchProfile = record.launchProfile' "$ROOT/app/src/main/java/dev/gfn/android/session/GfnSessionController.kt"
grep -Fq 'engine.connect(session, profile.streamConfig)' "$ROOT/app/src/main/java/dev/gfn/android/stream/GfnStreamingController.kt"
grep -Fq 'val owned = orchestrator.currentOwnedSession()' "$ROOT/app/src/main/java/dev/gfn/android/session/GfnSessionController.kt"
grep -Fq 'val hasOwnedSession = orchestrator.currentOwnedSession() != null' "$ROOT/app/src/main/java/dev/gfn/android/session/GfnSessionController.kt"
if grep -Fq 'StreamConfig()' "$ROOT/app/src/main/java/dev/gfn/android/stream/GfnStreamingController.kt"; then
    echo 'ERROR: WebRTC controller regressed to default StreamConfig instead of frozen profile' >&2
    exit 1
fi
if grep -Rqs 'GfnStreamSettingsController' "$ROOT/stream-webrtc/src/main"; then
    echo 'ERROR: live WebRTC layer must not read persistent settings controller' >&2
    exit 1
fi

echo 'V520_STREAM_SETTINGS_SNAPSHOT_STATIC_GUARDS=PASS'
