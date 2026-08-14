package dev.gfn.webrtc

import android.util.Log
import android.view.KeyEvent
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * v5.1.3 Input Forensics only.
 *
 * 只记录 Android KeyEvent -> mapper -> encoder -> DataChannel.send() 证据链，
 * 不参与任何键位映射、modifier、packet framing 或发送决策。
 */
object GfnInputForensics {
    data class KeyTrace(
        val eventSeq: Long,
        val activityInstanceId: Long?,
        val action: Int,
        val keyCode: Int,
        val scanCode: Int,
        val metaState: Int,
        val repeatCount: Int,
        val flags: Int,
        val deviceId: Int,
        val source: Int,
        val eventTime: Long,
        val downTime: Long,
    ) {
        val actionName: String
            get() = when (action) {
                KeyEvent.ACTION_DOWN -> "DOWN"
                KeyEvent.ACTION_UP -> "UP"
                KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
                else -> action.toString()
            }
    }

    data class KeyboardTx(
        val trace: KeyTrace,
        val connectionGeneration: Long,
        val inputEpoch: Long,
        val down: Boolean,
        val protocolVersion: Int,
        val payloadOffset: Int,
        val virtualKey: Int,
        val modifiers: Int,
        val scanCode: Int,
    )

    private data class DispatchFrame(
        val trace: KeyTrace,
        var surfaceHandled: Boolean? = null,
    )

    private val nextEventSeq = AtomicLong(0)
    private val dispatchFrame = ThreadLocal<DispatchFrame?>()

    @Volatile
    var enabled: Boolean = false
        private set

    fun configure(enabled: Boolean) {
        this.enabled = enabled
        if (enabled) Log.i("GfnInputForensics", "enabled=true")
    }

    fun beginActivityDispatch(event: KeyEvent, activityInstanceId: Long, focusedView: String): KeyTrace? {
        if (!enabled) return null
        val trace = snapshot(
            event = event,
            eventSeq = nextEventSeq.incrementAndGet(),
            activityInstanceId = activityInstanceId,
        )
        dispatchFrame.set(DispatchFrame(trace))
        Log.i(
            "GfnKeyDispatch",
            "PRE seq=${trace.eventSeq} activity=${trace.activityInstanceId} action=${trace.actionName} " +
                "keyCode=${trace.keyCode} keyName=${KeyEvent.keyCodeToString(trace.keyCode)} " +
                "scanCode=${trace.scanCode} metaState=${hex32(trace.metaState)} repeat=${trace.repeatCount} " +
                "flags=${hex32(trace.flags)} deviceId=${trace.deviceId} source=${hex32(trace.source)} " +
                "eventTime=${trace.eventTime} downTime=${trace.downTime} focusedView=$focusedView",
        )
        return trace
    }

    fun endActivityDispatch(trace: KeyTrace?, superHandled: Boolean) {
        if (!enabled || trace == null) return
        val frame = dispatchFrame.get()
        val appHandled = frame?.takeIf { it.trace.eventSeq == trace.eventSeq }?.surfaceHandled == true
        Log.i(
            "GfnKeyDispatch",
            "POST seq=${trace.eventSeq} activity=${trace.activityInstanceId} appHandled=$appHandled superHandled=$superHandled",
        )
        dispatchFrame.remove()
    }

    fun traceForSurface(event: KeyEvent): KeyTrace {
        if (!enabled) {
            return snapshot(event = event, eventSeq = 0, activityInstanceId = null)
        }
        val frame = dispatchFrame.get()
        if (frame != null && sameEvent(frame.trace, event)) return frame.trace
        return snapshot(
            event = event,
            eventSeq = nextEventSeq.incrementAndGet(),
            activityInstanceId = null,
        ).also {
            if (enabled) {
                Log.w(
                    "GfnKeyDispatch",
                    "ORPHAN seq=${it.eventSeq} action=${it.actionName} keyCode=${it.keyCode} " +
                        "scanCode=${it.scanCode} deviceId=${it.deviceId} eventTime=${it.eventTime}",
                )
            }
        }
    }

    fun markSurfaceHandled(trace: KeyTrace, handled: Boolean) {
        if (!enabled) return
        dispatchFrame.get()?.takeIf { it.trace.eventSeq == trace.eventSeq }?.surfaceHandled = handled
    }

    fun logInputKey(
        trace: KeyTrace,
        connectionGeneration: Long,
        inputEpoch: Long,
        androidModifiers: Int,
        trackedModifiers: Int,
        mappedVirtualKey: Int?,
        mappedScanCode: Int?,
        protocolVersion: Int?,
        keyboardActive: Boolean,
        consumed: Boolean,
        disposition: String,
    ) {
        if (!enabled) return
        Log.i(
            "GfnInputKey",
            "seq=${trace.eventSeq} activity=${trace.activityInstanceId ?: -1} connectionGen=$connectionGeneration " +
                "epoch=$inputEpoch event=${trace.actionName} keyCode=${trace.keyCode} androidScan=${trace.scanCode} " +
                "repeat=${trace.repeatCount} deviceId=${trace.deviceId} source=${hex32(trace.source)} " +
                "rawMeta=${hex32(trace.metaState)} androidMods=${hex16(androidModifiers)} trackedMods=${hex16(trackedModifiers)} " +
                "mappedVK=${mappedVirtualKey?.let(::hex16) ?: "UNMAPPED"} " +
                "mappedScan=${mappedScanCode?.let(::hex16) ?: "UNMAPPED"} protocol=${protocolVersion ?: -1} " +
                "keyboardActive=$keyboardActive consumed=$consumed disposition=$disposition",
        )
    }

    fun logDataChannel(
        connectionGeneration: Long,
        state: String,
        protocolReady: Boolean,
        note: String,
    ) {
        if (!enabled) return
        Log.i(
            "GfnInputChannel",
            "connectionGen=$connectionGeneration label=input_channel_v1 ordered=true negotiated=false " +
                "state=$state protocolReady=$protocolReady note=$note",
        )
    }

    fun logHandshake(
        connectionGeneration: Long,
        raw: ByteArray,
        negotiatedVersion: Int?,
    ) {
        if (!enabled) return
        val firstWord = if (raw.size >= 2) {
            (raw[0].toInt() and 0xff) or ((raw[1].toInt() and 0xff) shl 8)
        } else -1
        val parseRule = when {
            firstWord == 526 -> "FIRST_WORD_526"
            raw.isNotEmpty() && (raw[0].toInt() and 0xff) == 0x0e -> "BYTE0_0E"
            else -> "NONE"
        }
        Log.i(
            "GfnInputHandshake",
            "connectionGen=$connectionGeneration channel=input_channel_v1 raw=${raw.toHex()} " +
                "firstWord=${if (firstWord >= 0) hex16(firstWord) else "N/A"} parseRule=$parseRule " +
                "negotiatedVersion=${negotiatedVersion ?: "UNPARSED"} protocolReady=pending",
        )
    }

    fun logProtocolReady(connectionGeneration: Long, inputEpoch: Long, protocolVersion: Int) {
        if (!enabled) return
        Log.i(
            "GfnInputHandshake",
            "connectionGen=$connectionGeneration epoch=$inputEpoch channel=input_channel_v1 " +
                "negotiatedVersion=$protocolVersion protocolReady=true",
        )
    }

    /**
     * [buffer] 必须就是随后传给 DataChannel.Buffer 的同一个 ByteBuffer。
     * 此函数只通过 read-only duplicate 读取，不改变 position/limit。
     */
    fun logKeyboardTxBeforeSend(
        tx: KeyboardTx,
        buffer: ByteBuffer,
        channelState: String,
        bufferedAmount: Long,
        binary: Boolean,
    ): String? {
        if (!enabled) return null
        val position = buffer.position()
        val limit = buffer.limit()
        val remaining = buffer.remaining()
        val bytes = buffer.asReadOnlyBuffer().let { duplicate ->
            ByteArray(duplicate.remaining()).also(duplicate::get)
        }
        val type = if (tx.down) "KEY_DOWN" else "KEY_UP"
        val prefix =
            "seq=${tx.trace.eventSeq} activity=${tx.trace.activityInstanceId ?: -1} connectionGen=${tx.connectionGeneration} " +
                "epoch=${tx.inputEpoch} type=$type protocol=${tx.protocolVersion} payloadOffset=${tx.payloadOffset} " +
                "length=${bytes.size} vk=${hex16(tx.virtualKey)} mods=${hex16(tx.modifiers)} scan=${hex16(tx.scanCode)} " +
                "binary=$binary channel=input_channel_v1 channelState=$channelState position=$position limit=$limit " +
                "remaining=$remaining bufferedAmountBefore=$bufferedAmount bytes=${bytes.toHex()}"
        return prefix
    }

    fun logKeyboardTxAfterSend(prefix: String?, sendAccepted: Boolean, bufferedAmountAfter: Long) {
        if (!enabled || prefix == null) return
        Log.i(
            "GfnInputTx",
            "$prefix sendAccepted=$sendAccepted bufferedAmountAfter=$bufferedAmountAfter",
        )
    }

    private fun snapshot(event: KeyEvent, eventSeq: Long, activityInstanceId: Long?): KeyTrace = KeyTrace(
        eventSeq = eventSeq,
        activityInstanceId = activityInstanceId,
        action = event.action,
        keyCode = event.keyCode,
        scanCode = event.scanCode,
        metaState = event.metaState,
        repeatCount = event.repeatCount,
        flags = event.flags,
        deviceId = event.deviceId,
        source = event.source,
        eventTime = event.eventTime,
        downTime = event.downTime,
    )

    private fun sameEvent(trace: KeyTrace, event: KeyEvent): Boolean =
        trace.action == event.action &&
            trace.keyCode == event.keyCode &&
            trace.deviceId == event.deviceId &&
            trace.eventTime == event.eventTime &&
            trace.downTime == event.downTime

    private fun hex16(value: Int): String = String.format(Locale.US, "0x%04X", value and 0xffff)
    private fun hex32(value: Int): String = String.format(Locale.US, "0x%08X", value)

    private fun ByteArray.toHex(): String = joinToString(" ") { byte ->
        String.format(Locale.US, "%02X", byte.toInt() and 0xff)
    }
}
