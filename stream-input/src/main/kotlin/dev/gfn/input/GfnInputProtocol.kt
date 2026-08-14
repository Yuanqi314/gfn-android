package dev.gfn.input

import java.util.concurrent.atomic.AtomicLong

/**
 * GFN 键鼠输入协议。字段布局依据当前可工作的 CloudNow InputEncoder 行为整理。
 * 这里不依赖 Android / WebRTC，便于做跨模块编译和 fixture 回归。
 */
object GfnInputType {
    const val HEARTBEAT: Int = 2
    const val KEY_DOWN: Int = 3
    const val KEY_UP: Int = 4
    const val MOUSE_REL: Int = 7
    const val MOUSE_BUTTON_DOWN: Int = 8
    const val MOUSE_BUTTON_UP: Int = 9
    const val MOUSE_WHEEL: Int = 10
}

enum class InputReleaseReason {
    ActivityPause,
    WindowFocusLost,
    PointerCaptureLost,
    WebRtcDisconnect,
    DataChannelClose,
    OverlayOpen,
    SessionEnd,
    SessionSwitch,
    InputDisabled,
    ActivityDestroy,
    FullscreenExit,
    Reconnect,
    UserDisconnect,
}

enum class RemoteInputState {
    ASSUMED_SYNCED,
    RELEASING,
    UNKNOWN,
}

data class GfnKey(
    val virtualKey: Int,
    val scanCode: Int,
    val modifierBit: Int = 0,
) {
    val id: Int = (virtualKey shl 16) xor scanCode
    val isModifier: Boolean get() = modifierBit != 0
}

data class HeldKey(
    val key: GfnKey,
    val modifiersAtDown: Int,
)

sealed interface ReleaseCommand {
    data class KeyUp(val held: HeldKey) : ReleaseCommand
    data class MouseButtonUp(val button: Int) : ReleaseCommand
}

/**
 * 线程受限状态；调用方必须保证所有 mutation 都发生在同一个 ordered input queue。
 */
class InputStateTracker {
    val physicalHeldKeys: MutableMap<Int, HeldKey> = linkedMapOf()
    val physicalHeldMouseButtons: MutableSet<Int> = linkedSetOf()

    val remoteAssumedHeldKeys: MutableMap<Int, HeldKey> = linkedMapOf()
    val remoteAssumedHeldMouseButtons: MutableSet<Int> = linkedSetOf()

    val uncertainRemoteKeys: MutableMap<Int, HeldKey> = linkedMapOf()
    val uncertainRemoteMouseButtons: MutableSet<Int> = linkedSetOf()

    var remoteState: RemoteInputState = RemoteInputState.ASSUMED_SYNCED
        private set

    fun recordPhysicalKeyDown(held: HeldKey): Boolean =
        physicalHeldKeys.putIfAbsent(held.key.id, held) == null

    fun recordPhysicalKeyUp(key: GfnKey): HeldKey? = physicalHeldKeys.remove(key.id)

    fun recordPhysicalMouseDown(button: Int): Boolean = physicalHeldMouseButtons.add(button)

    fun recordPhysicalMouseUp(button: Int): Boolean = physicalHeldMouseButtons.remove(button)

    fun markKeyDownAccepted(held: HeldKey) {
        remoteAssumedHeldKeys[held.key.id] = held
        uncertainRemoteKeys.remove(held.key.id)
        refreshRemoteState()
    }

    fun markKeyUpAccepted(key: GfnKey) {
        remoteAssumedHeldKeys.remove(key.id)
        uncertainRemoteKeys.remove(key.id)
        refreshRemoteState()
    }

    fun markMouseDownAccepted(button: Int) {
        remoteAssumedHeldMouseButtons.add(button)
        uncertainRemoteMouseButtons.remove(button)
        refreshRemoteState()
    }

    fun markMouseUpAccepted(button: Int) {
        remoteAssumedHeldMouseButtons.remove(button)
        uncertainRemoteMouseButtons.remove(button)
        refreshRemoteState()
    }

    fun markKeyUncertain(held: HeldKey) {
        uncertainRemoteKeys[held.key.id] = held
        remoteState = RemoteInputState.UNKNOWN
    }

    fun markMouseUncertain(button: Int) {
        uncertainRemoteMouseButtons.add(button)
        remoteState = RemoteInputState.UNKNOWN
    }

    fun markAllRemoteUnknown() {
        uncertainRemoteKeys.putAll(remoteAssumedHeldKeys)
        uncertainRemoteMouseButtons.addAll(remoteAssumedHeldMouseButtons)
        remoteAssumedHeldKeys.clear()
        remoteAssumedHeldMouseButtons.clear()
        remoteState = RemoteInputState.UNKNOWN
    }

    /**
     * 确定化顺序：普通键 -> 鼠标按钮 -> modifier。
     * uncertain 也要尝试 UP，因为重复 UP 比漏 UP 更安全。
     */
    fun buildReleasePlan(): List<ReleaseCommand> {
        val keyUnion = linkedMapOf<Int, HeldKey>()
        keyUnion.putAll(uncertainRemoteKeys)
        keyUnion.putAll(remoteAssumedHeldKeys)
        val ordinary = keyUnion.values.filterNot { it.key.isModifier }.sortedBy { it.key.id }
        val modifiers = keyUnion.values.filter { it.key.isModifier }.sortedBy { it.key.id }
        val activeModifierMask = modifiers.fold(0) { mask, held -> mask or held.key.modifierBit }
        val buttons = (uncertainRemoteMouseButtons + remoteAssumedHeldMouseButtons).sorted()
        val plan = buildList {
            // releaseAll 时普通键 UP 应反映“当前仍按住”的 modifier，而不是该键 DOWN 时的旧快照。
            ordinary.forEach { add(ReleaseCommand.KeyUp(it.copy(modifiersAtDown = activeModifierMask))) }
            buttons.forEach { add(ReleaseCommand.MouseButtonUp(it)) }
            // Modifier 本身保持当前参考实现的 held snapshot 语义，且始终最后释放。
            modifiers.forEach { add(ReleaseCommand.KeyUp(it)) }
        }
        if (plan.isNotEmpty()) remoteState = RemoteInputState.RELEASING
        return plan
    }

    fun clearPhysicalState() {
        physicalHeldKeys.clear()
        physicalHeldMouseButtons.clear()
    }

    fun clearRemoteKey(key: GfnKey) {
        remoteAssumedHeldKeys.remove(key.id)
        uncertainRemoteKeys.remove(key.id)
        refreshRemoteState()
    }

    fun clearRemoteMouseButton(button: Int) {
        remoteAssumedHeldMouseButtons.remove(button)
        uncertainRemoteMouseButtons.remove(button)
        refreshRemoteState()
    }

    fun finishRelease(channelUsable: Boolean) {
        if (channelUsable && uncertainRemoteKeys.isEmpty() && uncertainRemoteMouseButtons.isEmpty()) {
            remoteState = RemoteInputState.ASSUMED_SYNCED
        } else if (!channelUsable || uncertainRemoteKeys.isNotEmpty() || uncertainRemoteMouseButtons.isNotEmpty()) {
            remoteState = RemoteInputState.UNKNOWN
        } else {
            refreshRemoteState()
        }
    }

    private fun refreshRemoteState() {
        remoteState = if (uncertainRemoteKeys.isEmpty() && uncertainRemoteMouseButtons.isEmpty()) {
            RemoteInputState.ASSUMED_SYNCED
        } else {
            RemoteInputState.UNKNOWN
        }
    }
}

class InputEpochGate(initialEpoch: Long = 1) {
    private val epoch = AtomicLong(initialEpoch)

    val currentEpoch: Long
        get() = epoch.get()

    fun advance(): Long = epoch.incrementAndGet()

    fun isCurrent(eventEpoch: Long): Boolean = eventEpoch == epoch.get()
}

object GfnInputHandshake {
    /**
     * Server handshake：
     * - firstWord == 526 (0x020e) -> version 在 bytes[2..3] LE
     * - bytes[0] == 0x0e -> version == firstWord
     * 其他消息不是 handshake。
     */
    fun parseProtocolVersion(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        val firstWord = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
        return when {
            firstWord == 526 -> if (bytes.size >= 4) {
                (bytes[2].toInt() and 0xff) or ((bytes[3].toInt() and 0xff) shl 8)
            } else 2
            (bytes[0].toInt() and 0xff) == 0x0e -> firstWord
            else -> null
        }
    }
}

class GfnInputPacketEncoder(
    protocolVersion: Int = 2,
    private val timestampMicros: () -> Long = { System.currentTimeMillis() * 1_000L },
) {
    var protocolVersion: Int = protocolVersion
        set(value) {
            require(value >= 2) { "GFN input protocol version 必须 >= 2" }
            field = value
        }

    fun heartbeat(): ByteArray = ByteArray(4).also { writeUInt32LE(it, 0, GfnInputType.HEARTBEAT.toLong()) }

    fun keyboard(down: Boolean, key: GfnKey, modifiers: Int): ByteArray {
        val timestamp = timestampMicros()
        val payloadOffset = if (protocolVersion >= 3) 10 else 0
        val out = ByteArray(payloadOffset + 18)
        writeSingleEventHeader(out, timestamp)
        writeUInt32LE(out, payloadOffset, if (down) GfnInputType.KEY_DOWN.toLong() else GfnInputType.KEY_UP.toLong())
        writeUInt16BE(out, payloadOffset + 4, key.virtualKey)
        writeUInt16BE(out, payloadOffset + 6, modifiers)
        writeUInt16BE(out, payloadOffset + 8, key.scanCode)
        writeUInt64BE(out, payloadOffset + 10, timestamp)
        return out
    }

    fun mouseMove(dx: Int, dy: Int): ByteArray {
        val timestamp = timestampMicros()
        val payloadOffset = if (protocolVersion >= 3) 12 else 0
        val out = ByteArray(payloadOffset + 22)
        if (protocolVersion >= 3) {
            out[0] = 0x23
            writeUInt64BE(out, 1, timestamp)
            out[9] = 0x21
            writeUInt16BE(out, 10, 22)
        }
        writeUInt32LE(out, payloadOffset, GfnInputType.MOUSE_REL.toLong())
        writeInt16BE(out, payloadOffset + 4, dx)
        writeInt16BE(out, payloadOffset + 6, dy)
        writeUInt64BE(out, payloadOffset + 14, timestamp)
        return out
    }

    fun mouseButton(down: Boolean, button: Int): ByteArray {
        require(button in 0..255)
        val timestamp = timestampMicros()
        val payloadOffset = if (protocolVersion >= 3) 10 else 0
        val out = ByteArray(payloadOffset + 18)
        writeSingleEventHeader(out, timestamp)
        writeUInt32LE(out, payloadOffset, if (down) GfnInputType.MOUSE_BUTTON_DOWN.toLong() else GfnInputType.MOUSE_BUTTON_UP.toLong())
        out[payloadOffset + 4] = button.toByte()
        writeUInt64BE(out, payloadOffset + 10, timestamp)
        return out
    }

    fun mouseWheel(delta: Int): ByteArray {
        val timestamp = timestampMicros()
        val payloadOffset = if (protocolVersion >= 3) 10 else 0
        val out = ByteArray(payloadOffset + 22)
        writeSingleEventHeader(out, timestamp)
        writeUInt32LE(out, payloadOffset, GfnInputType.MOUSE_WHEEL.toLong())
        writeInt16BE(out, payloadOffset + 6, delta)
        writeUInt64BE(out, payloadOffset + 14, timestamp)
        return out
    }

    private fun writeSingleEventHeader(out: ByteArray, timestamp: Long) {
        if (protocolVersion < 3) return
        out[0] = 0x23
        writeUInt64BE(out, 1, timestamp)
        out[9] = 0x22
    }

    private fun writeUInt16BE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 8) and 0xff).toByte()
        out[offset + 1] = (value and 0xff).toByte()
    }

    private fun writeInt16BE(out: ByteArray, offset: Int, value: Int) {
        val clamped = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) and 0xffff
        writeUInt16BE(out, offset, clamped)
    }

    private fun writeUInt32LE(out: ByteArray, offset: Int, value: Long) {
        repeat(4) { i -> out[offset + i] = ((value ushr (i * 8)) and 0xff).toByte() }
    }

    private fun writeUInt64BE(out: ByteArray, offset: Int, value: Long) {
        repeat(8) { i -> out[offset + i] = ((value ushr ((7 - i) * 8)) and 0xff).toByte() }
    }
}
