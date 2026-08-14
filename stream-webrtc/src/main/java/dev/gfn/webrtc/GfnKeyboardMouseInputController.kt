package dev.gfn.webrtc

import dev.gfn.input.GfnInputPacketEncoder
import dev.gfn.input.GfnKey
import dev.gfn.input.HeldKey
import dev.gfn.input.InputEpochGate
import dev.gfn.input.InputReleaseReason
import dev.gfn.input.InputStateTracker
import dev.gfn.input.ReleaseCommand
import dev.gfn.stream.InputDiagnostics
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * v5.1 键鼠状态机。所有 mutation + packet send 都在同一个 ordered executor 上。
 * Android UI 回调只负责 admission；不会直接碰 DataChannel。
 */
class GfnKeyboardMouseInputController(
    private val connectionGeneration: Long,
    private val packetSink: PacketSink,
    private val onDiagnostics: (InputDiagnostics) -> Unit,
) {
    interface PacketSink {
        fun sendBinary(packet: ByteArray): Boolean
        fun sendKeyboard(tx: GfnInputForensics.KeyboardTx, packet: ByteArray): Boolean
        fun isOpen(): Boolean
        fun bufferedAmount(): Long
    }

    private companion object {
        const val DISCONNECT_DRAIN_TIMEOUT_MILLIS = 120L
        const val DISCONNECT_DRAIN_POLL_MILLIS = 10L
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "gfn-input-ordered").apply { isDaemon = true }
    }
    private val gate = InputEpochGate()
    private val externallyVisibleEpoch = AtomicLong(gate.currentEpoch)
    private val tracker = InputStateTracker()
    private val encoder = GfnInputPacketEncoder()

    @Volatile private var lifecycleResumed = true
    @Volatile private var windowFocused = false
    @Volatile private var pointerCaptured = false
    @Volatile private var overlayOpen = false
    @Volatile private var streamConnected = false
    @Volatile private var dataChannelOpen = false
    @Volatile private var protocolReady = false
    @Volatile private var inputEnabled = true
    @Volatile private var protocolVersion: Int? = null

    private var pendingDx = 0.0
    private var pendingDy = 0.0
    private var pendingWheel = 0.0
    private var generated = 0L
    private var submitted = 0L
    private var accepted = 0L
    private var rejected = 0L
    private var dropped = 0L
    private var staleDropped = 0L
    private var releaseCount = 0L
    private var lastEvent: String? = null
    private var lastReleaseReason: InputReleaseReason? = null
    private var lastRawKeyCode: Int? = null
    private var lastRawMetaState: Int? = null
    private var lastAndroidReportedModifierMask: Int? = null
    private var lastTrackedModifierMask: Int? = null
    private var modifierMismatchCount = 0L
    private var lastHeartbeatNanos = System.nanoTime()
    private var lastDiagnosticNanos = 0L

    init {
        executor.scheduleAtFixedRate(::tick, 8, 8, TimeUnit.MILLISECONDS)
        emitDiagnostics(force = true)
    }

    fun onKey(down: Boolean, trace: GfnInputForensics.KeyTrace): Boolean {
        val eventEpoch = externallyVisibleEpoch.get()
        val androidReportedModifiers = AndroidKeyboardMapper.modifiers(trace.metaState)
        val key = AndroidKeyboardMapper.map(trace.keyCode)
        if (key == null) {
            // 只做取证：保持既有语义，未映射键仍返回 false 交给 Android 默认 dispatch。
            enqueue {
                GfnInputForensics.logInputKey(
                    trace = trace,
                    connectionGeneration = connectionGeneration,
                    inputEpoch = eventEpoch,
                    androidModifiers = androidReportedModifiers,
                    trackedModifiers = tracker.currentPhysicalModifierMask(),
                    mappedVirtualKey = null,
                    mappedScanCode = null,
                    protocolVersion = protocolVersion,
                    keyboardActive = keyboardActive(),
                    consumed = false,
                    disposition = "UNMAPPED",
                )
            }
            return false
        }
        enqueue {
            val heldModifierMaskBeforeEvent = tracker.currentPhysicalModifierMask()
            val trackedModifiersForEvent = if (down && key.isModifier) {
                heldModifierMaskBeforeEvent or key.modifierBit
            } else {
                heldModifierMaskBeforeEvent
            }
            lastRawKeyCode = trace.keyCode
            lastRawMetaState = trace.metaState
            lastAndroidReportedModifierMask = androidReportedModifiers
            lastTrackedModifierMask = trackedModifiersForEvent

            if (!gate.isCurrent(eventEpoch)) {
                staleDropped += 1
                GfnInputForensics.logInputKey(
                    trace, connectionGeneration, eventEpoch, androidReportedModifiers, trackedModifiersForEvent,
                    key.virtualKey, key.scanCode, protocolVersion, keyboardActive(), true, "STALE_EPOCH",
                )
                emitDiagnostics()
                return@enqueue
            }
            if (!keyboardActive()) {
                dropped += 1
                GfnInputForensics.logInputKey(
                    trace, connectionGeneration, eventEpoch, androidReportedModifiers, trackedModifiersForEvent,
                    key.virtualKey, key.scanCode, protocolVersion, false, true, "INPUT_INACTIVE",
                )
                return@enqueue
            }

            if (androidReportedModifiers != trackedModifiersForEvent) {
                modifierMismatchCount += 1
                Log.w(
                    "GfnInput",
                    "modifier mismatch keyCode=${trace.keyCode} down=$down rawMeta=0x${trace.metaState.toString(16)} " +
                        "androidMask=0x${androidReportedModifiers.toString(16)} trackedMask=0x${trackedModifiersForEvent.toString(16)}",
                )
            }
            GfnInputForensics.logInputKey(
                trace, connectionGeneration, eventEpoch, androidReportedModifiers, trackedModifiersForEvent,
                key.virtualKey, key.scanCode, protocolVersion, true, true, "ENCODE",
            )

            // 远端只使用本状态机实际持有的 modifier。Android metaState 只做诊断。
            if (down) handleKeyDown(trace, eventEpoch, key, trackedModifiersForEvent)
            else handleKeyUp(trace, eventEpoch, key, trackedModifiersForEvent)
        }
        return true
    }

    fun onMouseMove(dx: Float, dy: Float) {
        val eventEpoch = externallyVisibleEpoch.get()
        enqueue {
            if (!gate.isCurrent(eventEpoch)) {
                staleDropped += 1
                return@enqueue
            }
            if (!mouseActive()) {
                dropped += 1
                return@enqueue
            }
            pendingDx += dx
            pendingDy += dy
        }
    }

    fun onMouseButton(down: Boolean, button: Int): Boolean {
        if (button !in 1..3) return false
        val eventEpoch = externallyVisibleEpoch.get()
        enqueue {
            if (!gate.isCurrent(eventEpoch)) {
                staleDropped += 1
                emitDiagnostics()
                return@enqueue
            }
            if (!mouseActive()) {
                dropped += 1
                return@enqueue
            }
            if (down) {
                if (!tracker.recordPhysicalMouseDown(button)) return@enqueue
                lastEvent = "Mouse $button DOWN"
                val ok = send(encoder.mouseButton(true, button))
                if (ok) tracker.markMouseDownAccepted(button) else tracker.markMouseUncertain(button)
            } else {
                tracker.recordPhysicalMouseUp(button)
                lastEvent = "Mouse $button UP"
                val ok = send(encoder.mouseButton(false, button))
                if (ok) tracker.markMouseUpAccepted(button) else tracker.markMouseUncertain(button)
            }
            emitDiagnostics(force = true)
        }
        return true
    }

    fun onMouseWheel(verticalAxis: Float) {
        val eventEpoch = externallyVisibleEpoch.get()
        enqueue {
            if (!gate.isCurrent(eventEpoch)) {
                staleDropped += 1
                return@enqueue
            }
            if (!mouseActive()) {
                dropped += 1
                return@enqueue
            }
            // Android AXIS_VSCROLL is already in the direction expected by the current GFN session.
            // Real-device v5.1 evidence showed the previous Apple-derived sign inversion was backwards.
            pendingWheel += verticalAxis * 3.0
        }
    }

    fun onActivityResumed() {
        lifecycleResumed = true
        enqueue { emitDiagnostics(force = true) }
    }

    fun onActivityPaused() {
        lifecycleResumed = false
        releaseAll(InputReleaseReason.ActivityPause)
    }

    fun onActivityDestroy() {
        lifecycleResumed = false
        releaseAll(InputReleaseReason.ActivityDestroy)
    }

    fun onWindowFocusChanged(focused: Boolean) {
        windowFocused = focused
        if (!focused) releaseAll(InputReleaseReason.WindowFocusLost)
        else enqueue { emitDiagnostics(force = true) }
    }

    fun onPointerCaptureChanged(captured: Boolean) {
        pointerCaptured = captured
        if (!captured) suspendMouse(InputReleaseReason.PointerCaptureLost)
        else enqueue { emitDiagnostics(force = true) }
    }

    fun onOverlayChanged(open: Boolean) {
        overlayOpen = open
        if (open) releaseAll(InputReleaseReason.OverlayOpen)
        else enqueue { emitDiagnostics(force = true) }
    }

    fun onStreamConnected(connected: Boolean) {
        streamConnected = connected
        if (!connected) releaseAll(InputReleaseReason.WebRtcDisconnect)
        else enqueue { emitDiagnostics(force = true) }
    }

    fun onDataChannelState(open: Boolean) {
        val wasOpen = dataChannelOpen
        val wasProtocolReady = protocolReady
        dataChannelOpen = open
        if (!open) {
            protocolReady = false
            protocolVersion = null
            if (wasOpen || wasProtocolReady) {
                releaseAllInternal(InputReleaseReason.DataChannelClose, channelAlreadyUnavailable = true)
            } else {
                enqueue { emitDiagnostics(force = true) }
            }
        } else {
            enqueue { emitDiagnostics(force = true) }
        }
    }

    fun onProtocolReady(version: Int) {
        if (version < 2) return
        // INPUT_ACTIVE gate 必须晚于 uncertain-state neutralization。
        // handshake callback 只排队；真正 protocolReady 在 ordered queue 的最后一步打开。
        enqueue {
            if (!dataChannelOpen || !packetSink.isOpen()) return@enqueue
            protocolVersion = version
            encoder.protocolVersion = version
            neutralizeUncertainRemoteStateBeforeReady()
            protocolReady = true
            GfnInputForensics.logProtocolReady(connectionGeneration, gate.currentEpoch, version)
            emitDiagnostics(force = true)
        }
    }

    fun onInputEnabled(enabled: Boolean) {
        inputEnabled = enabled
        if (!enabled) releaseAll(InputReleaseReason.InputDisabled)
        else enqueue { emitDiagnostics(force = true) }
    }

    fun releaseForFullscreenExit() = releaseAll(InputReleaseReason.FullscreenExit)

    /**
     * 统一全量释放入口。所有会让键盘输入失去可靠所有权的生命周期事件都必须走这里。
     * Pointer Capture 丢失是例外：只影响 MouseActive，因此只释放鼠标状态。
     */
    fun releaseAll(reason: InputReleaseReason) = releaseAllInternal(reason, channelAlreadyUnavailable = false)

    fun prepareForDisconnect(reason: InputReleaseReason, onDrained: () -> Unit) {
        inputEnabled = false
        val newEpoch = advanceEpoch()
        if (!enqueue {
                releaseAllOnQueue(reason, expectedEpoch = newEpoch, channelAlreadyUnavailable = false)
                // ordered queue barrier：到这里 release packet 已至少提交给 DataChannel.send()。
                // 再做有界本地 transport drain；这不等于远端游戏 ACK。
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISCONNECT_DRAIN_TIMEOUT_MILLIS)
                awaitLocalTransportDrain(deadline, onDrained)
            }) {
            onDrained()
        }
    }

    fun shutdownWithoutTransport() {
        inputEnabled = false
        advanceEpoch()
        if (!enqueue {
                tracker.markAllRemoteUnknown()
                tracker.clearPhysicalState()
                pendingDx = 0.0
                pendingDy = 0.0
                pendingWheel = 0.0
                emitDiagnostics(force = true)
                executor.shutdown()
            }) {
            executor.shutdown()
        }
    }

    private fun handleKeyDown(
        trace: GfnInputForensics.KeyTrace,
        eventEpoch: Long,
        key: GfnKey,
        modifiers: Int,
    ) {
        val held = HeldKey(key, modifiers)
        if (!tracker.recordPhysicalKeyDown(held)) return
        lastEvent = keyLabel(key) + " DOWN"
        val packet = encoder.keyboard(true, key, modifiers)
        val ok = sendKeyboard(trace, eventEpoch, true, key, modifiers, packet)
        if (ok) tracker.markKeyDownAccepted(held) else tracker.markKeyUncertain(held)
        emitDiagnostics(force = true)
    }

    private fun handleKeyUp(
        trace: GfnInputForensics.KeyTrace,
        eventEpoch: Long,
        key: GfnKey,
        modifiers: Int,
    ) {
        val previous = tracker.recordPhysicalKeyUp(key)
        val held = previous ?: HeldKey(key, modifiers)
        val encodedModifiers = previous?.modifiersAtDown ?: modifiers
        lastEvent = keyLabel(key) + " UP"
        val packet = encoder.keyboard(false, key, encodedModifiers)
        val ok = sendKeyboard(trace, eventEpoch, false, key, encodedModifiers, packet)
        if (ok) tracker.markKeyUpAccepted(key) else tracker.markKeyUncertain(held)
        emitDiagnostics(force = true)
    }

    private fun releaseAllInternal(reason: InputReleaseReason, channelAlreadyUnavailable: Boolean) {
        val newEpoch = advanceEpoch()
        enqueue {
            releaseAllOnQueue(reason, newEpoch, channelAlreadyUnavailable)
        }
    }

    private fun suspendMouse(reason: InputReleaseReason) {
        // Pointer Capture 只影响 MouseActive。不要推进全局 epoch，否则会误丢仍应生效的键盘事件。
        enqueue {
            lastReleaseReason = reason
            releaseCount += 1
            pendingDx = 0.0
            pendingDy = 0.0
            pendingWheel = 0.0
            val channelUsable = dataChannelOpen && protocolReady && packetSink.isOpen()
            val buttons = (tracker.uncertainRemoteMouseButtons + tracker.remoteAssumedHeldMouseButtons).sorted()
            if (channelUsable) {
                buttons.forEach { button ->
                    if (send(encoder.mouseButton(false, button))) tracker.clearRemoteMouseButton(button)
                    else tracker.markMouseUncertain(button)
                }
            } else {
                tracker.uncertainRemoteMouseButtons.addAll(buttons)
            }
            tracker.physicalHeldMouseButtons.clear()
            tracker.finishRelease(channelUsable)
            emitDiagnostics(force = true)
        }
    }

    private fun releaseAllOnQueue(
        reason: InputReleaseReason,
        expectedEpoch: Long,
        channelAlreadyUnavailable: Boolean,
    ) {
        if (!gate.isCurrent(expectedEpoch)) return
        lastReleaseReason = reason
        releaseCount += 1
        pendingDx = 0.0
        pendingDy = 0.0
        pendingWheel = 0.0

        val channelUsable = !channelAlreadyUnavailable && dataChannelOpen && protocolReady && packetSink.isOpen()
        val plan = tracker.buildReleasePlan()
        if (channelUsable) {
            plan.forEach { command ->
                when (command) {
                    is ReleaseCommand.KeyUp -> {
                        if (send(encoder.keyboard(false, command.held.key, command.held.modifiersAtDown))) {
                            tracker.clearRemoteKey(command.held.key)
                        } else {
                            tracker.markKeyUncertain(command.held)
                        }
                    }
                    is ReleaseCommand.MouseButtonUp -> {
                        if (send(encoder.mouseButton(false, command.button))) {
                            tracker.clearRemoteMouseButton(command.button)
                        } else {
                            tracker.markMouseUncertain(command.button)
                        }
                    }
                }
            }
        } else {
            tracker.markAllRemoteUnknown()
        }
        tracker.clearPhysicalState()
        tracker.finishRelease(channelUsable)
        emitDiagnostics(force = true)
    }

    private fun neutralizeUncertainRemoteStateBeforeReady() {
        if (!dataChannelOpen || !packetSink.isOpen()) return
        val plan = tracker.buildReleasePlan()
        plan.forEach { command ->
            when (command) {
                is ReleaseCommand.KeyUp -> {
                    if (send(encoder.keyboard(false, command.held.key, command.held.modifiersAtDown))) {
                        tracker.clearRemoteKey(command.held.key)
                    }
                }
                is ReleaseCommand.MouseButtonUp -> {
                    if (send(encoder.mouseButton(false, command.button))) {
                        tracker.clearRemoteMouseButton(command.button)
                    }
                }
            }
        }
        tracker.finishRelease(channelUsable = true)
    }

    private fun awaitLocalTransportDrain(deadlineNanos: Long, onDrained: () -> Unit) {
        val open = packetSink.isOpen()
        val buffered = if (open) packetSink.bufferedAmount().coerceAtLeast(0L) else 0L
        if (!open || buffered == 0L || System.nanoTime() >= deadlineNanos) {
            onDrained()
            executor.shutdown()
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

    private fun enqueue(action: () -> Unit): Boolean {
        if (executor.isShutdown) return false
        return try {
            executor.execute(action)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun tick() {
        if (executor.isShutdown) return
        val now = System.nanoTime()
        if (now - lastHeartbeatNanos >= 2_000_000_000L && dataChannelOpen && protocolReady && packetSink.isOpen()) {
            send(encoder.heartbeat())
            lastHeartbeatNanos = now
        }
        if (mouseActive()) {
            val dx = pendingDx.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val dy = pendingDy.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            if (dx != 0 || dy != 0) {
                pendingDx -= dx
                pendingDy -= dy
                lastEvent = "Mouse Δ $dx,$dy"
                send(encoder.mouseMove(dx, dy))
            }
            val wheel = pendingWheel.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            if (wheel != 0) {
                pendingWheel -= wheel
                lastEvent = "Wheel $wheel"
                send(encoder.mouseWheel(wheel))
            }
        }
        emitDiagnostics()
    }

    private fun send(packet: ByteArray): Boolean {
        generated += 1
        if (!packetSink.isOpen()) {
            dropped += 1
            return false
        }
        submitted += 1
        val ok = packetSink.sendBinary(packet)
        if (ok) accepted += 1 else rejected += 1
        return ok
    }

    private fun sendKeyboard(
        trace: GfnInputForensics.KeyTrace,
        eventEpoch: Long,
        down: Boolean,
        key: GfnKey,
        modifiers: Int,
        packet: ByteArray,
    ): Boolean {
        generated += 1
        val version = protocolVersion ?: encoder.protocolVersion
        val tx = GfnInputForensics.KeyboardTx(
            trace = trace,
            connectionGeneration = connectionGeneration,
            inputEpoch = eventEpoch,
            down = down,
            protocolVersion = version,
            payloadOffset = if (version >= 3) 10 else 0,
            virtualKey = key.virtualKey,
            modifiers = modifiers,
            scanCode = key.scanCode,
        )
        if (!packetSink.isOpen()) {
            dropped += 1
            // PacketSink 仍接收该事件以记录实际“未发送”边界；实现不得调用 DataChannel.send。
            packetSink.sendKeyboard(tx, packet)
            return false
        }
        submitted += 1
        val ok = packetSink.sendKeyboard(tx, packet)
        if (ok) accepted += 1 else rejected += 1
        return ok
    }

    private fun advanceEpoch(): Long {
        val next = gate.advance()
        externallyVisibleEpoch.set(next)
        return next
    }

    private fun keyboardActive(): Boolean =
        inputEnabled && lifecycleResumed && streamConnected && windowFocused && dataChannelOpen && protocolReady && !overlayOpen

    private fun mouseActive(): Boolean = keyboardActive() && pointerCaptured

    private fun keyLabel(key: GfnKey): String = "VK 0x${key.virtualKey.toString(16).uppercase()}"

    private fun emitDiagnostics(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastDiagnosticNanos < 100_000_000L) return
        lastDiagnosticNanos = now
        onDiagnostics(
            InputDiagnostics(
                dataChannelOpen = dataChannelOpen,
                protocolReady = protocolReady,
                protocolVersion = protocolVersion,
                windowFocused = windowFocused,
                pointerCaptured = pointerCaptured,
                overlayOpen = overlayOpen,
                keyboardActive = keyboardActive(),
                mouseActive = mouseActive(),
                inputEpoch = gate.currentEpoch,
                remoteState = tracker.remoteState.name,
                physicalHeldKeys = tracker.physicalHeldKeys.size,
                remoteHeldKeys = tracker.remoteAssumedHeldKeys.size + tracker.uncertainRemoteKeys.size,
                physicalHeldMouseButtons = tracker.physicalHeldMouseButtons.size,
                remoteHeldMouseButtons = tracker.remoteAssumedHeldMouseButtons.size + tracker.uncertainRemoteMouseButtons.size,
                generatedPackets = generated,
                submittedPackets = submitted,
                acceptedPackets = accepted,
                rejectedPackets = rejected,
                droppedPackets = dropped,
                staleEventsDropped = staleDropped,
                transportBufferedBytes = if (packetSink.isOpen()) packetSink.bufferedAmount().coerceAtLeast(0L) else 0L,
                lastRawKeyCode = lastRawKeyCode,
                lastRawMetaState = lastRawMetaState,
                lastAndroidReportedModifierMask = lastAndroidReportedModifierMask,
                lastTrackedModifierMask = lastTrackedModifierMask,
                modifierMismatchCount = modifierMismatchCount,
                releaseCount = releaseCount,
                lastEvent = lastEvent,
                lastReleaseReason = lastReleaseReason?.name,
            ),
        )
    }
}
