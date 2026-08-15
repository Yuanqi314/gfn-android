package dev.gfn.input

import java.util.concurrent.atomic.AtomicLong

/**
 * GFN 输入协议编码器。键鼠与 type-12 gamepad 字段均保持为可独立 fixture 验证的纯 Kotlin。
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
    const val GAMEPAD: Int = 12
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

/** XInput button flags used by GFN type-12 gamepad snapshots. */
object GfnGamepadButtons {
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_STICK = 0x0040
    const val RIGHT_STICK = 0x0080
    const val LEFT_BUMPER = 0x0100
    const val RIGHT_BUMPER = 0x0200
    const val GUIDE = 0x0400
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
}

/** Normalized single-controller XInput-style snapshot. */
data class GfnGamepadState(
    val controllerId: Int = 0,
    val buttons: Int = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val leftStickX: Int = 0,
    val leftStickY: Int = 0,
    val rightStickX: Int = 0,
    val rightStickY: Int = 0,
) {
    init {
        require(controllerId in 0..3)
        require(buttons in 0..0xffff)
        require(leftTrigger in 0..0xff)
        require(rightTrigger in 0..0xff)
        require(leftStickX in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
        require(leftStickY in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
        require(rightStickX in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
        require(rightStickY in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
    }

    companion object {
        fun neutral(controllerId: Int = 0): GfnGamepadState = GfnGamepadState(controllerId = controllerId)
    }
}

object GfnGamepadBitmap {
    /** Advertise one normalized XInput-style controller in the selected slot. */
    fun singleXInput(controllerId: Int): Int {
        require(controllerId in 0..3)
        return (1 shl controllerId) or (1 shl (controllerId + 8))
    }
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

    /**
     * 远端 modifier 真值只来自我们实际接收并维护的 modifier DOWN/UP。
     * 不直接信任 Android KeyEvent.metaState，避免 OEM/键盘把系统 Meta 状态
     * 附带到普通字母事件后变成远端 Win+字母快捷键。
     */
    fun currentPhysicalModifierMask(): Int = physicalHeldKeys.values.fold(0) { mask, held ->
        mask or held.key.modifierBit
    }

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

    /**
     * GFN type-12 gamepad snapshot. Production v5.3 uses the reliable input channel because the
     * current NVST answer advertises enablePartiallyReliableTransferGamepad=0.
     *
     * Raw body (38 bytes) matches both reference implementations:
     * [u32 LE type=12][u16 LE 26][u16 LE slot][u16 LE bitmap][u16 LE 20]
     * [u16 LE buttons][u8 LT][u8 RT][i16 LE LX/LY/RX/RY][u16 LE 0]
     * [u16 LE 0x55][u16 LE 0][u64 LE timestamp].
     *
     * Protocol v3 reliable framing is [0x23][u64 BE timestamp][0x21][u16 BE size=38][body].
     */
    fun gamepad(state: GfnGamepadState, gamepadBitmap: Int): ByteArray {
        require(gamepadBitmap in 0..0xffff)
        val timestamp = timestampMicros()
        val payloadOffset = if (protocolVersion >= 3) 12 else 0
        val out = ByteArray(payloadOffset + 38)
        if (protocolVersion >= 3) {
            out[0] = 0x23
            writeUInt64BE(out, 1, timestamp)
            out[9] = 0x21
            writeUInt16BE(out, 10, 38)
        }
        writeUInt32LE(out, payloadOffset, GfnInputType.GAMEPAD.toLong())
        writeUInt16LE(out, payloadOffset + 4, 26)
        writeUInt16LE(out, payloadOffset + 6, state.controllerId and 0x03)
        writeUInt16LE(out, payloadOffset + 8, gamepadBitmap)
        writeUInt16LE(out, payloadOffset + 10, 20)
        writeUInt16LE(out, payloadOffset + 12, state.buttons)
        out[payloadOffset + 14] = state.leftTrigger.toByte()
        out[payloadOffset + 15] = state.rightTrigger.toByte()
        writeInt16LE(out, payloadOffset + 16, state.leftStickX)
        writeInt16LE(out, payloadOffset + 18, state.leftStickY)
        writeInt16LE(out, payloadOffset + 20, state.rightStickX)
        writeInt16LE(out, payloadOffset + 22, state.rightStickY)
        writeUInt16LE(out, payloadOffset + 24, 0)
        writeUInt16LE(out, payloadOffset + 26, 0x55)
        writeUInt16LE(out, payloadOffset + 28, 0)
        writeUInt64LE(out, payloadOffset + 30, timestamp)
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

    private fun writeUInt16LE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun writeInt16LE(out: ByteArray, offset: Int, value: Int) {
        val clamped = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) and 0xffff
        writeUInt16LE(out, offset, clamped)
    }

    private fun writeInt16BE(out: ByteArray, offset: Int, value: Int) {
        val clamped = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) and 0xffff
        writeUInt16BE(out, offset, clamped)
    }

    private fun writeUInt32LE(out: ByteArray, offset: Int, value: Long) {
        repeat(4) { i -> out[offset + i] = ((value ushr (i * 8)) and 0xff).toByte() }
    }

    private fun writeUInt64LE(out: ByteArray, offset: Int, value: Long) {
        repeat(8) { i -> out[offset + i] = ((value ushr (i * 8)) and 0xff).toByte() }
    }

    private fun writeUInt64BE(out: ByteArray, offset: Int, value: Long) {
        repeat(8) { i -> out[offset + i] = ((value ushr ((7 - i) * 8)) and 0xff).toByte() }
    }
}
