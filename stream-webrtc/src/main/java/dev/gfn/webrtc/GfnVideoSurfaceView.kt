package dev.gfn.webrtc

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * H.264 SDR 视频输出 + v5.1 硬件键盘/鼠标输入面。
 * 只负责 Android event capture；GFN mapping/state/transport 由 GfnKeyboardMouseInputController 处理。
 */
class GfnVideoSurfaceView(context: Context) : SurfaceViewRenderer(context) {
    interface InputListener {
        fun onKey(down: Boolean, trace: GfnInputForensics.KeyTrace): Boolean
        fun onMouseMove(dx: Float, dy: Float)
        fun onMouseButton(down: Boolean, button: Int): Boolean
        fun onMouseWheel(verticalAxis: Float)
        fun onWindowFocusChanged(focused: Boolean)
        fun onPointerCaptureChanged(captured: Boolean)
    }

    var onFirstFrame: (() -> Unit)? = null
    var onResolutionChanged: ((width: Int, height: Int) -> Unit)? = null
    var inputListener: InputListener? = null
        set(value) {
            field = value
            value?.onWindowFocusChanged(inputCaptureEnabled && hasWindowFocus())
            value?.onPointerCaptureChanged(inputCaptureEnabled && hasPointerCapture())
        }

    private var released = false
    private var inputCaptureEnabled = false

    init {
        init(
            GfnWebRtcRuntime.eglContext(),
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    onFirstFrame?.invoke()
                }

                override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                    val width = if (rotation % 180 == 0) videoWidth else videoHeight
                    val height = if (rotation % 180 == 0) videoHeight else videoWidth
                    onResolutionChanged?.invoke(width, height)
                }
            },
        )
        setEnableHardwareScaler(true)
        setMirror(false)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setOnClickListener { requestKeyboardMouseCapture() }
    }

    fun setKeyboardMouseInputEnabled(enabled: Boolean) {
        inputCaptureEnabled = enabled
        if (!enabled) {
            releaseKeyboardMouseCapture()
            inputListener?.onWindowFocusChanged(false)
        } else {
            inputListener?.onWindowFocusChanged(hasWindowFocus())
        }
    }

    fun requestKeyboardMouseCapture() {
        if (released || !inputCaptureEnabled) return
        requestFocus()
        if (!hasPointerCapture()) {
            requestPointerCapture()
        }
    }

    fun releaseKeyboardMouseCapture() {
        if (hasPointerCapture()) releasePointerCapture()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        inputListener?.onWindowFocusChanged(inputCaptureEnabled && hasWindowFocus)
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        inputListener?.onPointerCaptureChanged(inputCaptureEnabled && hasCapture)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputCaptureEnabled) return super.onKeyDown(keyCode, event)
        val trace = GfnInputForensics.traceForSurface(event)
        if (event.repeatCount > 0) {
            // 保持 v5.1 既有语义：held state 只由第一次 DOWN 维护，不发送重复远端 DOWN。
            GfnInputForensics.markSurfaceHandled(trace, true)
            return true
        }
        val handled = inputListener?.onKey(true, trace) == true
        GfnInputForensics.markSurfaceHandled(trace, handled)
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputCaptureEnabled) return super.onKeyUp(keyCode, event)
        val trace = GfnInputForensics.traceForSurface(event)
        val handled = inputListener?.onKey(false, trace) == true
        GfnInputForensics.markSurfaceHandled(trace, handled)
        return handled || super.onKeyUp(keyCode, event)
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (!inputCaptureEnabled) return super.onCapturedPointerEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Relative axes can be batched. Summing historical samples avoids losing high-polling-rate mouse deltas.
                var dx = 0f
                var dy = 0f
                for (historyIndex in 0 until event.historySize) {
                    dx += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, historyIndex)
                    dy += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, historyIndex)
                }
                dx += event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                dy += event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (dx != 0f || dy != 0f) inputListener?.onMouseMove(dx, dy)
                return true
            }

            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> 1
                    MotionEvent.BUTTON_TERTIARY -> 2
                    MotionEvent.BUTTON_SECONDARY -> 3
                    else -> 0
                }
                if (button != 0) {
                    inputListener?.onMouseButton(event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS, button)
                    return true
                }
            }

            MotionEvent.ACTION_SCROLL -> {
                var vertical = 0f
                for (historyIndex in 0 until event.historySize) {
                    vertical += event.getHistoricalAxisValue(MotionEvent.AXIS_VSCROLL, historyIndex)
                }
                vertical += event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vertical != 0f) inputListener?.onMouseWheel(vertical)
                return true
            }
        }
        return super.onCapturedPointerEvent(event)
    }

    fun releaseRenderer() {
        if (released) return
        released = true
        inputListener?.onPointerCaptureChanged(false)
        inputListener = null
        onFirstFrame = null
        onResolutionChanged = null
        releaseKeyboardMouseCapture()
        release()
    }
}
