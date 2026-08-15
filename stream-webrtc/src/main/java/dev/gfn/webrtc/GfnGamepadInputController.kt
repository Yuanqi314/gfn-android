package dev.gfn.webrtc

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.gfn.input.GfnGamepadBitmap
import dev.gfn.input.GfnGamepadButtons
import dev.gfn.input.GfnGamepadState
import dev.gfn.input.GfnInputPacketEncoder
import dev.gfn.input.InputReleaseReason
import dev.gfn.stream.GamepadDiagnostics
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v5.3 single-controller Android gamepad bridge.
 *
 * Physical Android controller input is normalized to one XInput-style slot (slot 0) and sent as
 * GFN type-12 snapshots over the reliable input_channel_v1. The current NVST answer explicitly
 * advertises enablePartiallyReliableTransferGamepad=0, so v5.3 does not use the PR gamepad wrapper.
 *
 * All mutable state and DataChannel sends are serialized on one executor. Android callbacks only
 * capture immutable event values and enqueue mutations.
 */
class GfnGamepadInputController(
    context: Context,
    private val connectionGeneration: Long,
    private val packetSink: PacketSink,
    private val onDiagnostics: (GamepadDiagnostics) -> Unit,
) : InputManager.InputDeviceListener {
    interface PacketSink {
        fun sendBinary(packet: ByteArray): Boolean
        fun isOpen(): Boolean
        fun bufferedAmount(): Long
    }

    private companion object {
        const val CONTROLLER_ID = 0
        const val SNAPSHOT_INTERVAL_MILLIS = 8L
        const val KEEPALIVE_INTERVAL_MILLIS = 100L
        const val MAX_REPLACEABLE_BUFFERED_BYTES = 64L * 1024L
        const val DISCONNECT_DRAIN_TIMEOUT_MILLIS = 120L
        const val DISCONNECT_DRAIN_POLL_MILLIS = 10L
        const val DEADZONE = 0.15f
    }

    private val inputManager = context.applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val inputManagerHandler = Handler(Looper.getMainLooper())
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "gfn-gamepad-ordered").apply { isDaemon = true }
    }
    private val encoder = GfnInputPacketEncoder()

    @Volatile private var lifecycleResumed = true
    @Volatile private var windowFocused = false
    @Volatile private var overlayOpen = false
    @Volatile private var streamConnected = false
    @Volatile private var dataChannelOpen = false
    @Volatile private var protocolReady = false
    @Volatile private var inputEnabled = true
    @Volatile private var protocolVersion: Int? = null
    @Volatile private var inputDeviceListenerRegistered = true

    private var activeDeviceId: Int? = null
    private var activeDeviceName: String? = null
    private var keyButtons = 0
    private var hatButtons = 0
    private var digitalLeftTrigger = 0
    private var digitalRightTrigger = 0
    private var leftTrigger = 0
    private var rightTrigger = 0
    private var leftStickX = 0
    private var leftStickY = 0
    private var rightStickX = 0
    private var rightStickY = 0
    private var dirty = false
    private var lastSendNanos = 0L
    private var generated = 0L
    private var submitted = 0L
    private var accepted = 0L
    private var rejected = 0L
    private var dropped = 0L
    private var sentChangeLogs = 0
    private var lastEvent: String? = null
    private var lastReleaseReason: InputReleaseReason? = null
    private var lastDiagnosticNanos = 0L

    init {
        inputManager.registerInputDeviceListener(this, inputManagerHandler)
        enqueue {
            selectInitialDeviceIfNeeded()
            emitDiagnostics(force = true)
        }
        executor.scheduleAtFixedRate(::tick, SNAPSHOT_INTERVAL_MILLIS, SNAPSHOT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun onGamepadKey(down: Boolean, event: KeyEvent): Boolean {
        if (!isGamepadEvent(event.device, event.source)) return false
        val keyCode = event.keyCode
        val mappedButton = xInputButtonForKeyCode(keyCode)
        val trigger = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> 1
            KeyEvent.KEYCODE_BUTTON_R2 -> 2
            else -> 0
        }
        if (mappedButton == null && trigger == 0) return false
        val deviceId = event.deviceId
        enqueue {
            if (!acceptDevice(deviceId, event.device)) return@enqueue
            if (!gamepadActive()) {
                dropped += 1
                lastEvent = "KEY ${if (down) "DOWN" else "UP"} code=$keyCode INPUT_INACTIVE"
                emitDiagnostics(force = true)
                return@enqueue
            }
            var changed = false
            if (mappedButton != null) {
                val next = if (down) keyButtons or mappedButton else keyButtons and mappedButton.inv()
                if (next != keyButtons) {
                    keyButtons = next and 0xffff
                    changed = true
                }
            }
            if (trigger == 1) {
                val next = if (down) 255 else 0
                if (next != digitalLeftTrigger) {
                    digitalLeftTrigger = next
                    changed = true
                }
            } else if (trigger == 2) {
                val next = if (down) 255 else 0
                if (next != digitalRightTrigger) {
                    digitalRightTrigger = next
                    changed = true
                }
            }
            if (changed) {
                dirty = true
                lastEvent = "KEY ${if (down) "DOWN" else "UP"} code=$keyCode"
                emitDiagnostics(force = true)
            }
        }
        return true
    }

    fun onGamepadMotion(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE || !isGamepadEvent(event.device, event.source)) return false
        val device = event.device ?: return false
        val deviceId = event.deviceId

        val left = AndroidGamepadMath.radialDeadzone(
            normalizeCenteredAxis(event, device, MotionEvent.AXIS_X),
            normalizeCenteredAxis(event, device, MotionEvent.AXIS_Y),
            DEADZONE,
        )
        val right = AndroidGamepadMath.radialDeadzone(
            normalizeCenteredAxis(event, device, firstSupportedAxis(device, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)),
            normalizeCenteredAxis(event, device, firstSupportedAxis(device, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)),
            DEADZONE,
        )
        val hatX = normalizeCenteredAxis(event, device, MotionEvent.AXIS_HAT_X)
        val hatY = normalizeCenteredAxis(event, device, MotionEvent.AXIS_HAT_Y)
        val analogLt = normalizedTriggerAxis(event, device, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        val analogRt = normalizedTriggerAxis(event, device, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)

        val newLx = AndroidGamepadMath.axisToInt16(left.first)
        val newLy = AndroidGamepadMath.axisToInt16(-left.second)
        val newRx = AndroidGamepadMath.axisToInt16(right.first)
        val newRy = AndroidGamepadMath.axisToInt16(-right.second)
        val newLt = analogLt?.let(AndroidGamepadMath::triggerToUInt8)
        val newRt = analogRt?.let(AndroidGamepadMath::triggerToUInt8)
        val newHat = hatButtons(hatX, hatY)

        enqueue {
            if (!acceptDevice(deviceId, device)) return@enqueue
            if (!gamepadActive()) {
                dropped += 1
                lastEvent = "MOTION INPUT_INACTIVE"
                emitDiagnostics(force = true)
                return@enqueue
            }
            var changed = false
            fun updateInt(current: Int, next: Int, apply: (Int) -> Unit) {
                if (current != next) {
                    apply(next)
                    changed = true
                }
            }
            updateInt(leftStickX, newLx) { leftStickX = it }
            updateInt(leftStickY, newLy) { leftStickY = it }
            updateInt(rightStickX, newRx) { rightStickX = it }
            updateInt(rightStickY, newRy) { rightStickY = it }
            updateInt(hatButtons, newHat) { hatButtons = it }
            if (newLt != null) updateInt(leftTrigger, newLt) { leftTrigger = it }
            if (newRt != null) updateInt(rightTrigger, newRt) { rightTrigger = it }
            if (changed) {
                dirty = true
                lastEvent = "MOTION"
                emitDiagnostics()
            }
        }
        return true
    }

    fun onActivityResumed() {
        lifecycleResumed = true
        enqueue {
            selectInitialDeviceIfNeeded()
            dirty = true
            emitDiagnostics(force = true)
        }
    }

    fun onActivityPaused() {
        lifecycleResumed = false
        releaseLocalState(InputReleaseReason.ActivityPause)
    }

    fun onActivityDestroy() {
        lifecycleResumed = false
        releaseLocalState(InputReleaseReason.ActivityDestroy)
    }

    fun onWindowFocusChanged(focused: Boolean) {
        windowFocused = focused
        if (!focused) releaseLocalState(InputReleaseReason.WindowFocusLost)
        else enqueue {
            dirty = true
            emitDiagnostics(force = true)
        }
    }

    fun onOverlayChanged(open: Boolean) {
        overlayOpen = open
        if (open) releaseLocalState(InputReleaseReason.OverlayOpen)
        else enqueue {
            dirty = true
            emitDiagnostics(force = true)
        }
    }

    fun onStreamConnected(connected: Boolean) {
        streamConnected = connected
        if (!connected) releaseLocalState(InputReleaseReason.WebRtcDisconnect)
        else enqueue {
            dirty = true
            emitDiagnostics(force = true)
        }
    }

    fun onDataChannelState(open: Boolean) {
        dataChannelOpen = open
        if (!open) {
            protocolReady = false
            protocolVersion = null
            enqueue { emitDiagnostics(force = true) }
        } else {
            enqueue { emitDiagnostics(force = true) }
        }
    }

    fun onProtocolReady(version: Int) {
        if (version < 2) return
        enqueue {
            if (!dataChannelOpen || !packetSink.isOpen()) return@enqueue
            protocolVersion = version
            encoder.protocolVersion = version
            protocolReady = true
            dirty = true
            sendCurrentIfPossible(force = true, stateChanged = true)
            emitDiagnostics(force = true)
        }
    }

    fun releaseForFullscreenExit() = releaseLocalState(InputReleaseReason.FullscreenExit)

    fun prepareForDisconnect(reason: InputReleaseReason, onDrained: () -> Unit) {
        inputEnabled = false
        if (!enqueue {
                lastReleaseReason = reason
                sendNeutral(disconnected = true, force = true)
                clearPhysicalState()
                emitDiagnostics(force = true)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISCONNECT_DRAIN_TIMEOUT_MILLIS)
                awaitLocalTransportDrain(deadline) {
                    unregisterInputDeviceListener()
                    onDrained()
                }
            }) {
            unregisterInputDeviceListener()
            onDrained()
        }
    }

    fun shutdownWithoutTransport() {
        inputEnabled = false
        unregisterInputDeviceListener()
        if (!enqueue {
                clearPhysicalState()
                emitDiagnostics(force = true)
                executor.shutdown()
            }) {
            executor.shutdown()
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        enqueue {
            if (activeDeviceId == null) {
                inputManager.getInputDevice(deviceId)?.takeIf(::isSupportedGamepad)?.let { adoptDevice(it, "DEVICE_ADDED") }
            }
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        enqueue {
            if (activeDeviceId != deviceId) return@enqueue
            lastEvent = "DEVICE_REMOVED id=$deviceId"
            activeDeviceId = null
            activeDeviceName = null
            clearPhysicalState()
            sendNeutral(disconnected = true, force = true)
            selectInitialDeviceIfNeeded()
            emitDiagnostics(force = true)
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        enqueue {
            val device = inputManager.getInputDevice(deviceId)
            if (activeDeviceId == deviceId) {
                if (device == null || !isSupportedGamepad(device)) {
                    onInputDeviceRemoved(deviceId)
                } else {
                    activeDeviceName = device.name
                    emitDiagnostics(force = true)
                }
            } else if (activeDeviceId == null && device != null && isSupportedGamepad(device)) {
                adoptDevice(device, "DEVICE_CHANGED")
            }
        }
    }

    private fun tick() {
        if (executor.isShutdown) return
        sendCurrentIfPossible(force = false, stateChanged = dirty)
        emitDiagnostics()
    }

    private fun sendCurrentIfPossible(force: Boolean, stateChanged: Boolean) {
        if (!gamepadActive() || activeDeviceId == null) return
        val now = System.nanoTime()
        val keepaliveDue = now - lastSendNanos >= TimeUnit.MILLISECONDS.toNanos(KEEPALIVE_INTERVAL_MILLIS)
        if (!force && !stateChanged && !keepaliveDue) return
        val state = currentState()
        val bitmap = GfnGamepadBitmap.singleXInput(CONTROLLER_ID)
        if (sendSnapshot(state, bitmap, force = force, stateChanged = stateChanged)) {
            dirty = false
            lastSendNanos = now
        }
    }

    private fun sendNeutral(disconnected: Boolean, force: Boolean) {
        if (!dataChannelOpen || !protocolReady || !packetSink.isOpen()) return
        val bitmap = if (disconnected || activeDeviceId == null) 0 else GfnGamepadBitmap.singleXInput(CONTROLLER_ID)
        sendSnapshot(GfnGamepadState.neutral(CONTROLLER_ID), bitmap, force = force, stateChanged = true)
        lastSendNanos = System.nanoTime()
    }

    private fun sendSnapshot(
        state: GfnGamepadState,
        bitmap: Int,
        force: Boolean,
        stateChanged: Boolean,
    ): Boolean {
        generated += 1
        if (!packetSink.isOpen()) {
            dropped += 1
            return false
        }
        val buffered = packetSink.bufferedAmount().coerceAtLeast(0L)
        if (!force && buffered > MAX_REPLACEABLE_BUFFERED_BYTES) {
            dropped += 1
            return false
        }
        submitted += 1
        val packet = encoder.gamepad(state, bitmap)
        val ok = packetSink.sendBinary(packet)
        if (ok) accepted += 1 else rejected += 1
        if (stateChanged && sentChangeLogs < 20) {
            sentChangeLogs += 1
            Log.i(
                "GfnGamepad",
                "generation=$connectionGeneration send#$sentChangeLogs protocol=${protocolVersion ?: encoder.protocolVersion} " +
                    "bytes=${packet.size} bitmap=0x${bitmap.toString(16).padStart(4, '0')} " +
                    "buttons=0x${state.buttons.toString(16).padStart(4, '0')} lt=${state.leftTrigger} rt=${state.rightTrigger} " +
                    "lx=${state.leftStickX} ly=${state.leftStickY} rx=${state.rightStickX} ry=${state.rightStickY} accepted=$ok",
            )
        }
        return ok
    }

    private fun releaseLocalState(reason: InputReleaseReason) {
        enqueue {
            lastReleaseReason = reason
            sendNeutral(disconnected = false, force = true)
            clearPhysicalState()
            dirty = true
            lastEvent = "RELEASE ${reason.name}"
            emitDiagnostics(force = true)
        }
    }

    private fun clearPhysicalState() {
        keyButtons = 0
        hatButtons = 0
        digitalLeftTrigger = 0
        digitalRightTrigger = 0
        leftTrigger = 0
        rightTrigger = 0
        leftStickX = 0
        leftStickY = 0
        rightStickX = 0
        rightStickY = 0
    }

    private fun currentState(): GfnGamepadState = GfnGamepadState(
        controllerId = CONTROLLER_ID,
        buttons = (keyButtons or hatButtons) and 0xffff,
        leftTrigger = maxOf(leftTrigger, digitalLeftTrigger),
        rightTrigger = maxOf(rightTrigger, digitalRightTrigger),
        leftStickX = leftStickX,
        leftStickY = leftStickY,
        rightStickX = rightStickX,
        rightStickY = rightStickY,
    )

    private fun gamepadActive(): Boolean =
        inputEnabled && lifecycleResumed && windowFocused && streamConnected && dataChannelOpen && protocolReady && !overlayOpen

    private fun acceptDevice(deviceId: Int, eventDevice: InputDevice?): Boolean {
        val active = activeDeviceId
        if (active == deviceId) return true
        if (active != null) return false
        val device = eventDevice ?: inputManager.getInputDevice(deviceId) ?: return false
        if (!isSupportedGamepad(device)) return false
        adoptDevice(device, "FIRST_EVENT")
        return true
    }

    private fun selectInitialDeviceIfNeeded() {
        if (activeDeviceId != null) return
        inputManager.inputDeviceIds
            .sorted()
            .asSequence()
            .mapNotNull(inputManager::getInputDevice)
            .firstOrNull(::isSupportedGamepad)
            ?.let { adoptDevice(it, "INITIAL_SCAN") }
    }

    private fun adoptDevice(device: InputDevice, reason: String) {
        activeDeviceId = device.id
        activeDeviceName = device.name
        clearPhysicalState()
        dirty = true
        lastEvent = "$reason id=${device.id} name=${device.name}"
        Log.i("GfnGamepad", "generation=$connectionGeneration $lastEvent")
        if (gamepadActive()) sendCurrentIfPossible(force = true, stateChanged = true)
        emitDiagnostics(force = true)
    }

    private fun isSupportedGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        return hasSource(sources, InputDevice.SOURCE_GAMEPAD) || hasSource(sources, InputDevice.SOURCE_JOYSTICK)
    }

    private fun isGamepadEvent(device: InputDevice?, source: Int): Boolean {
        val sources = device?.sources ?: source
        return hasSource(sources, InputDevice.SOURCE_GAMEPAD) || hasSource(sources, InputDevice.SOURCE_JOYSTICK)
    }

    private fun hasSource(sources: Int, source: Int): Boolean = sources and source == source

    private fun firstSupportedAxis(device: InputDevice, first: Int, second: Int): Int = when {
        device.getMotionRange(first) != null -> first
        device.getMotionRange(second) != null -> second
        else -> first
    }

    private fun normalizeCenteredAxis(event: MotionEvent, device: InputDevice, axis: Int): Float {
        val value = event.getAxisValue(axis)
        val range = device.getMotionRange(axis) ?: return value.coerceIn(-1f, 1f)
        val span = range.max - range.min
        if (span <= 0f) return 0f
        val center = (range.max + range.min) / 2f
        val half = span / 2f
        return ((value - center) / half).coerceIn(-1f, 1f)
    }

    private fun normalizedTriggerAxis(
        event: MotionEvent,
        device: InputDevice,
        primaryAxis: Int,
        fallbackAxis: Int,
    ): Float? {
        val axis = when {
            device.getMotionRange(primaryAxis) != null -> primaryAxis
            device.getMotionRange(fallbackAxis) != null -> fallbackAxis
            else -> return null
        }
        val range = device.getMotionRange(axis) ?: return null
        val span = range.max - range.min
        if (span <= 0f) return 0f
        return ((event.getAxisValue(axis) - range.min) / span).coerceIn(0f, 1f)
    }

    private fun hatButtons(x: Float, y: Float): Int {
        var buttons = 0
        if (x <= -0.5f) buttons = buttons or GfnGamepadButtons.DPAD_LEFT
        if (x >= 0.5f) buttons = buttons or GfnGamepadButtons.DPAD_RIGHT
        if (y <= -0.5f) buttons = buttons or GfnGamepadButtons.DPAD_UP
        if (y >= 0.5f) buttons = buttons or GfnGamepadButtons.DPAD_DOWN
        return buttons
    }

    private fun xInputButtonForKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> GfnGamepadButtons.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GfnGamepadButtons.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GfnGamepadButtons.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GfnGamepadButtons.DPAD_RIGHT
        KeyEvent.KEYCODE_BUTTON_START -> GfnGamepadButtons.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> GfnGamepadButtons.BACK
        KeyEvent.KEYCODE_BUTTON_THUMBL -> GfnGamepadButtons.LEFT_STICK
        KeyEvent.KEYCODE_BUTTON_THUMBR -> GfnGamepadButtons.RIGHT_STICK
        KeyEvent.KEYCODE_BUTTON_L1 -> GfnGamepadButtons.LEFT_BUMPER
        KeyEvent.KEYCODE_BUTTON_R1 -> GfnGamepadButtons.RIGHT_BUMPER
        KeyEvent.KEYCODE_BUTTON_MODE -> GfnGamepadButtons.GUIDE
        KeyEvent.KEYCODE_BUTTON_A -> GfnGamepadButtons.A
        KeyEvent.KEYCODE_BUTTON_B -> GfnGamepadButtons.B
        KeyEvent.KEYCODE_BUTTON_X -> GfnGamepadButtons.X
        KeyEvent.KEYCODE_BUTTON_Y -> GfnGamepadButtons.Y
        else -> null
    }

    private fun awaitLocalTransportDrain(deadlineNanos: Long, onDrained: () -> Unit) {
        val open = packetSink.isOpen()
        val buffered = if (open) packetSink.bufferedAmount().coerceAtLeast(0L) else 0L
        if (!open || buffered == 0L || System.nanoTime() >= deadlineNanos) {
            executor.shutdown()
            onDrained()
            return
        }
        try {
            executor.schedule(
                { awaitLocalTransportDrain(deadlineNanos, onDrained) },
                DISCONNECT_DRAIN_POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            onDrained()
        }
    }

    private fun unregisterInputDeviceListener() {
        if (!inputDeviceListenerRegistered) return
        inputDeviceListenerRegistered = false
        runCatching { inputManager.unregisterInputDeviceListener(this) }
    }

    private fun enqueue(action: () -> Unit): Boolean {
        if (executor.isShutdown) return false
        return try {
            executor.execute(action)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun emitDiagnostics(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastDiagnosticNanos < 100_000_000L) return
        lastDiagnosticNanos = now
        val state = currentState()
        onDiagnostics(
            GamepadDiagnostics(
                connected = activeDeviceId != null,
                active = gamepadActive(),
                dataChannelOpen = dataChannelOpen,
                protocolReady = protocolReady,
                protocolVersion = protocolVersion,
                deviceId = activeDeviceId,
                deviceName = activeDeviceName,
                buttons = state.buttons,
                leftTrigger = state.leftTrigger,
                rightTrigger = state.rightTrigger,
                leftStickX = state.leftStickX,
                leftStickY = state.leftStickY,
                rightStickX = state.rightStickX,
                rightStickY = state.rightStickY,
                generatedPackets = generated,
                submittedPackets = submitted,
                acceptedPackets = accepted,
                rejectedPackets = rejected,
                droppedPackets = dropped,
                lastEvent = lastEvent,
                lastReleaseReason = lastReleaseReason?.name,
            ),
        )
    }
}

/** Pure normalization helpers shared by Android events and the v5.3 fixture. */
object AndroidGamepadMath {
    fun radialDeadzone(x: Float, y: Float, deadzone: Float = 0.15f): Pair<Float, Float> {
        val clampedDeadzone = deadzone.coerceIn(0f, 0.99f)
        val magnitude = sqrt(x * x + y * y)
        if (magnitude < clampedDeadzone || magnitude == 0f) return 0f to 0f
        val normalizedX = x / magnitude
        val normalizedY = y / magnitude
        val scaledMagnitude = ((magnitude - clampedDeadzone) / (1f - clampedDeadzone)).coerceIn(0f, 1f)
        return normalizedX * scaledMagnitude to normalizedY * scaledMagnitude
    }

    fun axisToInt16(value: Float): Int {
        val clamped = value.coerceIn(-1f, 1f)
        return if (clamped < 0f) {
            (clamped * 32768f).roundToInt().coerceAtLeast(Short.MIN_VALUE.toInt())
        } else {
            (clamped * 32767f).roundToInt().coerceAtMost(Short.MAX_VALUE.toInt())
        }
    }

    fun triggerToUInt8(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
}
