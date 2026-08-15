#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/gamepad-check"
rm -rf "$BUILD"
mkdir -p "$BUILD/protocol" "$BUILD/controller"

PROTOCOL_SRC="$ROOT/stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt"
GAMEPAD_SRC="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnGamepadInputController.kt"
ENGINE_SRC="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt"
SURFACE_SRC="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoSurfaceView.kt"
SIGNALING_SRC="$ROOT/stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt"

cat > "$BUILD/protocol/GamepadPacketProbe.kt" <<'KT'
import dev.gfn.input.*

private fun u16le(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
private fun u16be(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
private fun u32le(b: ByteArray, o: Int): Long = (0..3).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xff) shl (8 * i)) }
private fun u64le(b: ByteArray, o: Int): ULong = (0..7).fold(0uL) { a, i -> a or ((b[o + i].toULong() and 0xffu) shl (8 * i)) }
private fun u64be(b: ByteArray, o: Int): ULong = (0..7).fold(0uL) { a, i -> (a shl 8) or (b[o + i].toULong() and 0xffu) }

fun main() {
    val ts = 0x0102030405060708L
    val state = GfnGamepadState(
        controllerId = 3,
        buttons = 0xA55A,
        leftTrigger = 0x12,
        rightTrigger = 0xFE,
        leftStickX = -2,
        leftStickY = 0x1234,
        rightStickX = Short.MIN_VALUE.toInt(),
        rightStickY = Short.MAX_VALUE.toInt(),
    )

    val v2 = GfnInputPacketEncoder(2) { ts }.gamepad(state, 0x0305)
    check(v2.size == 38)
    check(u32le(v2, 0) == 12L)
    check(u16le(v2, 4) == 26)
    check(u16le(v2, 6) == 3)
    check(u16le(v2, 8) == 0x0305)
    check(u16le(v2, 10) == 20)
    check(u16le(v2, 12) == 0xA55A)
    check((v2[14].toInt() and 0xff) == 0x12)
    check((v2[15].toInt() and 0xff) == 0xFE)
    check(u16le(v2, 16) == 0xFFFE)
    check(u16le(v2, 18) == 0x1234)
    check(u16le(v2, 20) == 0x8000)
    check(u16le(v2, 22) == 0x7FFF)
    check(u16le(v2, 24) == 0)
    check(u16le(v2, 26) == 0x55)
    check(u16le(v2, 28) == 0)
    check(u64le(v2, 30) == ts.toULong())

    val v3 = GfnInputPacketEncoder(3) { ts }.gamepad(state, 0x0101)
    check(v3.size == 50)
    check((v3[0].toInt() and 0xff) == 0x23)
    check(u64be(v3, 1) == ts.toULong())
    check((v3[9].toInt() and 0xff) == 0x21)
    check(u16be(v3, 10) == 38)
    check(u32le(v3, 12) == 12L)
    check(u16le(v3, 20) == 0x0101)
    check(u64le(v3, 42) == ts.toULong())
    check(GfnGamepadBitmap.singleXInput(0) == 0x0101)

    println("V530_GAMEPAD_PACKET_FIXTURE=PASS")
    println("V2_BYTES=${v2.size} V3_RELIABLE_BYTES=${v3.size} BITMAP=0x${GfnGamepadBitmap.singleXInput(0).toString(16).padStart(4, '0')}")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$PROTOCOL_SRC" "$BUILD/protocol/GamepadPacketProbe.kt" \
  -include-runtime -d "$BUILD/protocol/probe.jar" > "$BUILD/protocol/compile.log" 2>&1
java -jar "$BUILD/protocol/probe.jar"

cat > "$BUILD/controller/OsStubs.kt" <<'KT'
package android.os
class Looper { companion object { fun getMainLooper(): Looper = Looper() } }
class Handler(val looper: Looper)
KT

cat > "$BUILD/controller/DiagnosticsStub.kt" <<'KT'
package dev.gfn.stream
data class GamepadDiagnostics(
    val connected: Boolean = false,
    val active: Boolean = false,
    val dataChannelOpen: Boolean = false,
    val protocolReady: Boolean = false,
    val protocolVersion: Int? = null,
    val deviceId: Int? = null,
    val deviceName: String? = null,
    val buttons: Int = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val leftStickX: Int = 0,
    val leftStickY: Int = 0,
    val rightStickX: Int = 0,
    val rightStickY: Int = 0,
    val generatedPackets: Long = 0,
    val submittedPackets: Long = 0,
    val acceptedPackets: Long = 0,
    val rejectedPackets: Long = 0,
    val droppedPackets: Long = 0,
    val lastEvent: String? = null,
    val lastReleaseReason: String? = null,
)
KT

cat > "$BUILD/controller/InputManagerStub.kt" <<'KT'
package android.hardware.input
import android.os.Handler
import android.view.InputDevice
class InputManager(private val devices: MutableMap<Int, InputDevice> = linkedMapOf()) {
    interface InputDeviceListener {
        fun onInputDeviceAdded(deviceId: Int)
        fun onInputDeviceRemoved(deviceId: Int)
        fun onInputDeviceChanged(deviceId: Int)
    }
    private var listener: InputDeviceListener? = null
    val inputDeviceIds: IntArray get() = devices.keys.toIntArray()
    fun getInputDevice(id: Int): InputDevice? = devices[id]
    fun registerInputDeviceListener(l: InputDeviceListener, handler: Handler?) { listener = l }
    fun unregisterInputDeviceListener(l: InputDeviceListener) { if (listener === l) listener = null }
    fun remove(id: Int) { devices.remove(id); listener?.onInputDeviceRemoved(id) }
}
KT

cat > "$BUILD/controller/AndroidStubs.kt" <<'KT'
package android.content
import android.hardware.input.InputManager
open class Context(private val manager: InputManager) {
    open val applicationContext: Context get() = this
    open fun getSystemService(name: String): Any = manager
    companion object { const val INPUT_SERVICE: String = "input" }
}
KT

cat > "$BUILD/controller/LogStub.kt" <<'KT'
package android.util
object Log { fun i(tag: String, msg: String): Int = 0 }
KT

cat > "$BUILD/controller/ViewStubs.kt" <<'KT'
package android.view
class InputDevice(
    val id: Int,
    val name: String,
    val sources: Int,
    private val ranges: Map<Int, MotionRange> = emptyMap(),
) {
    class MotionRange(val min: Float, val max: Float)
    fun getMotionRange(axis: Int): MotionRange? = ranges[axis]
    companion object {
        const val SOURCE_GAMEPAD = 0x00000401
        const val SOURCE_JOYSTICK = 0x01000010
    }
}
class KeyEvent(
    val deviceId: Int,
    val device: InputDevice?,
    val source: Int,
    val keyCode: Int,
) {
    companion object {
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_BUTTON_A = 96
        const val KEYCODE_BUTTON_B = 97
        const val KEYCODE_BUTTON_C = 98
        const val KEYCODE_BUTTON_X = 99
        const val KEYCODE_BUTTON_Y = 100
        const val KEYCODE_BUTTON_Z = 101
        const val KEYCODE_BUTTON_L1 = 102
        const val KEYCODE_BUTTON_R1 = 103
        const val KEYCODE_BUTTON_L2 = 104
        const val KEYCODE_BUTTON_R2 = 105
        const val KEYCODE_BUTTON_THUMBL = 106
        const val KEYCODE_BUTTON_THUMBR = 107
        const val KEYCODE_BUTTON_START = 108
        const val KEYCODE_BUTTON_SELECT = 109
        const val KEYCODE_BUTTON_MODE = 110
    }
}
class MotionEvent(
    val deviceId: Int,
    val device: InputDevice?,
    val source: Int,
    val actionMasked: Int,
    private val axes: Map<Int, Float>,
) {
    fun getAxisValue(axis: Int): Float = axes[axis] ?: 0f
    companion object {
        const val ACTION_MOVE = 2
        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_Z = 11
        const val AXIS_RX = 12
        const val AXIS_RY = 13
        const val AXIS_RZ = 14
        const val AXIS_HAT_X = 15
        const val AXIS_HAT_Y = 16
        const val AXIS_LTRIGGER = 17
        const val AXIS_RTRIGGER = 18
        const val AXIS_THROTTLE = 19
        const val AXIS_RUDDER = 20
        const val AXIS_WHEEL = 21
        const val AXIS_GAS = 22
        const val AXIS_BRAKE = 23
    }
}
KT

cat > "$BUILD/controller/Probe.kt" <<'KT'
import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.gfn.input.GfnGamepadButtons
import dev.gfn.webrtc.GfnGamepadInputController

private fun u16le(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
private fun u32le(b: ByteArray, o: Int): Long = (0..3).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xff) shl (8 * i)) }

fun main() {
    val ranges = mapOf(
        MotionEvent.AXIS_X to InputDevice.MotionRange(-1f, 1f),
        MotionEvent.AXIS_Y to InputDevice.MotionRange(-1f, 1f),
        MotionEvent.AXIS_Z to InputDevice.MotionRange(-1f, 1f),
        MotionEvent.AXIS_RZ to InputDevice.MotionRange(-1f, 1f),
        MotionEvent.AXIS_LTRIGGER to InputDevice.MotionRange(0f, 1f),
        MotionEvent.AXIS_RTRIGGER to InputDevice.MotionRange(0f, 1f),
        MotionEvent.AXIS_HAT_X to InputDevice.MotionRange(-1f, 1f),
        MotionEvent.AXIS_HAT_Y to InputDevice.MotionRange(-1f, 1f),
    )
    val device = InputDevice(7, "Fixture Xbox", InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK, ranges)
    val manager = InputManager(linkedMapOf(7 to device))
    val packets = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
    val sink = object : GfnGamepadInputController.PacketSink {
        override fun sendBinary(packet: ByteArray): Boolean { packets += packet; return true }
        override fun isOpen() = true
        override fun bufferedAmount() = 0L
    }
    val controller = GfnGamepadInputController(Context(manager), 1, sink) {}
    controller.onDataChannelState(true)
    controller.onStreamConnected(true)
    controller.onWindowFocusChanged(true)
    controller.onProtocolReady(3)
    Thread.sleep(60)

    check(packets.isNotEmpty())
    var packet = packets.last()
    check(packet.size == 50)
    check(u32le(packet, 12) == 12L)
    check(u16le(packet, 20) == 0x0101)

    controller.onGamepadKey(true, KeyEvent(7, device, device.sources, KeyEvent.KEYCODE_BUTTON_A))
    Thread.sleep(40)
    packet = packets.last()
    check(u16le(packet, 24) and GfnGamepadButtons.A != 0)

    controller.onGamepadMotion(
        MotionEvent(
            7,
            device,
            device.sources,
            MotionEvent.ACTION_MOVE,
            mapOf(
                MotionEvent.AXIS_X to 1f,
                MotionEvent.AXIS_Y to -1f,
                MotionEvent.AXIS_Z to 0f,
                MotionEvent.AXIS_RZ to 1f,
                MotionEvent.AXIS_LTRIGGER to 1f,
                MotionEvent.AXIS_RTRIGGER to 0.5f,
                MotionEvent.AXIS_HAT_X to -1f,
                MotionEvent.AXIS_HAT_Y to 0f,
            ),
        ),
    )
    Thread.sleep(40)
    packet = packets.last()
    check((packet[26].toInt() and 0xff) == 255)
    check((packet[27].toInt() and 0xff) in 127..128)
    check(u16le(packet, 28) in 1..0x7fff)
    check(u16le(packet, 30) in 1..0x7fff) // Android Y=-1 -> XInput positive/up.
    check(u16le(packet, 32) == 0)
    check(u16le(packet, 34) == 0x8000) // Android right Y=+1 -> XInput negative/down.
    check(u16le(packet, 24) and GfnGamepadButtons.DPAD_LEFT != 0)

    // Overlay freezes remote gamepad state. Inputs received while inactive must not be replayed
    // when the overlay closes.
    controller.onOverlayChanged(true)
    Thread.sleep(30)
    controller.onGamepadKey(true, KeyEvent(7, device, device.sources, KeyEvent.KEYCODE_BUTTON_B))
    Thread.sleep(20)
    controller.onOverlayChanged(false)
    Thread.sleep(40)
    packet = packets.last()
    check(u16le(packet, 24) and GfnGamepadButtons.B == 0)

    manager.remove(7)
    Thread.sleep(40)
    packet = packets.last()
    check(u16le(packet, 20) == 0)
    check(u16le(packet, 24) == 0)
    controller.shutdownWithoutTransport()

    println("V530_GAMEPAD_CONTROLLER_FIXTURE=PASS")
    println("PACKETS=${packets.size} LAST_BITMAP=0x${u16le(packet, 20).toString(16).padStart(4, '0')}")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$BUILD/controller/OsStubs.kt" \
  "$BUILD/controller/DiagnosticsStub.kt" \
  "$BUILD/controller/InputManagerStub.kt" \
  "$BUILD/controller/AndroidStubs.kt" \
  "$BUILD/controller/LogStub.kt" \
  "$BUILD/controller/ViewStubs.kt" \
  "$PROTOCOL_SRC" "$GAMEPAD_SRC" "$BUILD/controller/Probe.kt" \
  -include-runtime -d "$BUILD/controller/probe.jar" > "$BUILD/controller/compile.log" 2>&1
java -jar "$BUILD/controller/probe.jar"

mkdir -p "$BUILD/surface"
cat > "$BUILD/surface/Android.kt" <<'KT'
package android.content
open class Context
KT
cat > "$BUILD/surface/View.kt" <<'KT'
package android.view
class InputDevice(val sources: Int = 0) {
    companion object { const val SOURCE_GAMEPAD = 0x401; const val SOURCE_JOYSTICK = 0x01000010 }
}
open class KeyEvent(val device: InputDevice? = null, val source: Int = 0, val repeatCount: Int = 0)
open class MotionEvent(
    val device: InputDevice? = null,
    val source: Int = 0,
    val actionMasked: Int = 0,
    val actionButton: Int = 0,
    val historySize: Int = 0,
) {
    fun getAxisValue(axis: Int): Float = 0f
    fun getHistoricalAxisValue(axis: Int, historyIndex: Int): Float = 0f
    companion object {
        const val ACTION_MOVE = 2
        const val ACTION_BUTTON_PRESS = 11
        const val ACTION_BUTTON_RELEASE = 12
        const val ACTION_SCROLL = 8
        const val AXIS_RELATIVE_X = 27
        const val AXIS_RELATIVE_Y = 28
        const val AXIS_VSCROLL = 9
        const val BUTTON_PRIMARY = 1
        const val BUTTON_SECONDARY = 2
        const val BUTTON_TERTIARY = 4
    }
}
KT
cat > "$BUILD/surface/WebRtc.kt" <<'KT'
package org.webrtc
import android.content.Context
object RendererCommon {
    enum class ScalingType { SCALE_ASPECT_FIT }
    interface RendererEvents {
        fun onFirstFrameRendered()
        fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int)
    }
}
open class SurfaceViewRenderer(val context: Context) {
    var isFocusable = false
    var isFocusableInTouchMode = false
    var isClickable = false
    fun init(ctx: Any?, events: RendererCommon.RendererEvents) = Unit
    fun setEnableHardwareScaler(value: Boolean) = Unit
    fun setMirror(value: Boolean) = Unit
    fun setScalingType(value: RendererCommon.ScalingType) = Unit
    fun setOnClickListener(listener: (Any?) -> Unit) = Unit
    fun requestFocus(): Boolean = true
    fun hasPointerCapture(): Boolean = false
    fun requestPointerCapture() = Unit
    fun releasePointerCapture() = Unit
    fun hasWindowFocus(): Boolean = true
    open fun onWindowFocusChanged(hasWindowFocus: Boolean) = Unit
    open fun onPointerCaptureChange(hasCapture: Boolean) = Unit
    open fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean = false
    open fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean = false
    open fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean = false
    open fun onCapturedPointerEvent(event: android.view.MotionEvent): Boolean = false
    open fun release() = Unit
}
KT
cat > "$BUILD/surface/Local.kt" <<'KT'
package dev.gfn.webrtc
import android.view.KeyEvent
object GfnWebRtcRuntime { fun eglContext(): Any? = null }
object GfnInputForensics {
    data class KeyTrace(val keyCode: Int = 0)
    fun traceForSurface(event: KeyEvent) = KeyTrace()
    fun markSurfaceHandled(trace: KeyTrace, handled: Boolean) = Unit
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$BUILD/surface/Android.kt" "$BUILD/surface/View.kt" "$BUILD/surface/WebRtc.kt" "$BUILD/surface/Local.kt" \
  "$SURFACE_SRC" -d "$BUILD/surface/check.jar" > "$BUILD/surface/compile.log" 2>&1
test -s "$BUILD/surface/check.jar"
echo 'V530_VIDEO_SURFACE_GAMEPAD_ROUTE_COMPILE=PASS'

# Static production guards: packet shape, Android bridge, reliable-channel policy and reconnect wiring.
grep -Fq 'const val GAMEPAD: Int = 12' "$PROTOCOL_SRC"
grep -Fq 'fun gamepad(state: GfnGamepadState, gamepadBitmap: Int): ByteArray' "$PROTOCOL_SRC"
grep -Fq 'out[9] = 0x21' "$PROTOCOL_SRC"
grep -Fq 'writeUInt16BE(out, 10, 38)' "$PROTOCOL_SRC"
grep -Fq 'return (1 shl controllerId) or (1 shl (controllerId + 8))' "$PROTOCOL_SRC"
grep -Fq 'class GfnGamepadInputController' "$GAMEPAD_SRC"
grep -Fq 'const val DEADZONE = 0.15f' "$GAMEPAD_SRC"
grep -Fq 'const val KEEPALIVE_INTERVAL_MILLIS = 100L' "$GAMEPAD_SRC"
grep -Fq 'GfnGamepadBitmap.singleXInput(CONTROLLER_ID)' "$GAMEPAD_SRC"
grep -Fq 'GfnGamepadInputController(' "$ENGINE_SRC"
grep -Fq 'onGamepadKey' "$SURFACE_SRC"
grep -Fq 'onGamepadMotion' "$SURFACE_SRC"
grep -Fq 'gamepad?.onProtocolReady(version)' "$ENGINE_SRC"
grep -Fq 'gamepad?.prepareForDisconnect(reason, drained)' "$ENGINE_SRC"
grep -Fq 'a=ri.enablePartiallyReliableTransferGamepad:0' "$SIGNALING_SRC"

# v5.3 intentionally does not advertise haptics/type13 yet.
if grep -Eq 'HAPTICS_ENABLED|hapticsEnabled|INPUT_HAPTICS' "$PROTOCOL_SRC" "$GAMEPAD_SRC"; then
    echo 'ERROR: v5.3 must not silently enable haptics/type13' >&2
    exit 1
fi

# Keyboard behavior must remain stable even though shared protocol/surface files gained gamepad code.
"$ROOT/verify-keyboard-stable.sh"

echo 'V530_GAMEPAD_STATIC_GUARDS=PASS'
