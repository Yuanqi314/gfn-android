package dev.gfn.android.session

import android.content.Context
import dev.gfn.core.model.SessionInfo
import java.io.File
import java.util.Properties
import java.util.UUID

/** CloudMatch x-device-id：跨启动稳定，但不进入 Android Auto Backup。 */
class AndroidStableDeviceId(context: Context) {
    private val file = File(context.noBackupFilesDir, "gfn-device-id.txt")

    @Synchronized
    fun getOrCreate(): String {
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(generated)
        if (!temp.renameTo(file)) {
            file.writeText(generated)
            temp.delete()
        }
        return generated
    }
}

data class PersistedSessionRecord(
    val sessionId: String,
    val appId: String,
    val gameTitle: String,
    val appStore: String,
    val status: Int,
    val serverIp: String?,
    val streamingBaseUrl: String,
    val routingZoneUrl: String?,
    val clientId: String,
    val deviceId: String,
    val createdAtEpochMillis: Long,
) {
    fun toSessionInfo(): SessionInfo = SessionInfo(
        sessionId = sessionId,
        status = status,
        serverIp = serverIp,
        streamingBaseUrl = streamingBaseUrl,
        routingZoneUrl = routingZoneUrl,
        clientId = clientId,
        deviceId = deviceId,
    )
}

/**
 * 只持久化 resume 所需的非凭据 session 元数据；token 仍完全由 gfn-auth/KeyStore 管理。
 */
class AndroidSessionRecordStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "gfn-session-v4.properties")

    @Synchronized
    fun load(): PersistedSessionRecord? {
        if (!file.isFile) return null
        return runCatching {
            val props = Properties().apply { file.inputStream().use(::load) }
            PersistedSessionRecord(
                sessionId = props.required("sessionId"),
                appId = props.required("appId"),
                gameTitle = props.required("gameTitle"),
                appStore = props.getProperty("appStore").orEmpty(),
                status = props.getProperty("status")?.toIntOrNull() ?: 0,
                serverIp = props.getProperty("serverIp")?.takeIf { it.isNotBlank() },
                streamingBaseUrl = props.required("streamingBaseUrl"),
                routingZoneUrl = props.getProperty("routingZoneUrl")?.takeIf { it.isNotBlank() },
                clientId = props.required("clientId"),
                deviceId = props.required("deviceId"),
                createdAtEpochMillis = props.getProperty("createdAtEpochMillis")?.toLongOrNull() ?: 0L,
            )
        }.getOrElse {
            file.delete()
            null
        }
    }

    @Synchronized
    fun save(record: PersistedSessionRecord) {
        val props = Properties().apply {
            setProperty("sessionId", record.sessionId)
            setProperty("appId", record.appId)
            setProperty("gameTitle", record.gameTitle)
            setProperty("appStore", record.appStore)
            setProperty("status", record.status.toString())
            setProperty("serverIp", record.serverIp.orEmpty())
            setProperty("streamingBaseUrl", record.streamingBaseUrl)
            setProperty("routingZoneUrl", record.routingZoneUrl.orEmpty())
            setProperty("clientId", record.clientId)
            setProperty("deviceId", record.deviceId)
            setProperty("createdAtEpochMillis", record.createdAtEpochMillis.toString())
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { props.store(it, "GFN Android v4 resumable session") }
        if (!temp.renameTo(file)) {
            file.outputStream().use { props.store(it, "GFN Android v4 resumable session") }
            temp.delete()
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun Properties.required(name: String): String =
        getProperty(name)?.takeIf { it.isNotBlank() }
            ?: error("session record 缺少 $name")
}
