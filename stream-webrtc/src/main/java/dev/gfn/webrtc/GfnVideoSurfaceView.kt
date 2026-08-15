package dev.gfn.webrtc

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame

/**
 * WebRTC 视频输出 + Android 输入捕获面。
 *
 * v6.1.1 Stage C1 only switches the final SurfaceViewRenderer EGLConfig for an explicitly frozen
 * HEVC/Main10 + PreferSdr10 session. SDR8/H264 keeps the original two-argument M144 init path.
 * Decoder/shared-root EGL, SurfaceTexture source path and SurfaceHolder format remain unchanged.
 */
class GfnVideoSurfaceView(
    context: Context,
    private val requestRgb10A2: Boolean = false,
) : SurfaceViewRenderer(context) {
    interface InputListener {
        fun onKey(down: Boolean, trace: GfnInputForensics.KeyTrace): Boolean
        fun onMouseMove(dx: Float, dy: Float)
        fun onMouseButton(down: Boolean, button: Int): Boolean
        fun onMouseWheel(verticalAxis: Float)
        fun onGamepadKey(down: Boolean, event: KeyEvent): Boolean
        fun onGamepadMotion(event: MotionEvent): Boolean
        fun onWindowFocusChanged(focused: Boolean)
        fun onPointerCaptureChanged(captured: Boolean)
    }

    var onFirstFrame: (() -> Unit)? = null
    var onResolutionChanged: ((width: Int, height: Int) -> Unit)? = null
    var onFrameActivity: (() -> Unit)? = null
    var inputListener: InputListener? = null
        set(value) {
            field = value
            value?.onWindowFocusChanged(inputCaptureEnabled && hasWindowFocus())
            value?.onPointerCaptureChanged(inputCaptureEnabled && hasPointerCapture())
        }

    @Volatile
    private var released = false
    private var inputCaptureEnabled = false
    private val forensicViewId = System.identityHashCode(this)
    private var activeRgb10A2 = false

    init {
        // Resolve the existing shared root first. Stage C1 preflight then queries the already
        // initialized default EGLDisplay without creating/rebinding a context or surface.
        val sharedContext = GfnWebRtcRuntime.eglContext()
        val preflight = if (requestRgb10A2) GfnEgl10BitCapabilityProbe.queryDefaultDisplayEgl14() else null
        val selected = preflight?.selected
        val canActivateRgb10A2 = requestRgb10A2 &&
            preflight?.status == GfnEgl10BitCapabilityStatus.Supported &&
            selected != null &&
            selected.nativeVisualMatchesRequestedSurfaceFormat == true &&
            !selected.isExplicitlyFloatColor

        val rendererEvents = object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                onFirstFrame?.invoke()
            }

            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                val width = if (rotation % 180 == 0) videoWidth else videoHeight
                val height = if (rotation % 180 == 0) videoHeight else videoWidth
                onResolutionChanged?.invoke(width, height)
            }
        }

        if (canActivateRgb10A2) {
            activeRgb10A2 = true
            init(
                sharedContext,
                rendererEvents,
                GfnEgl10BitConfig.rendererAttributes(selected!!.configId),
                GlRectDrawer(),
            )
        } else {
            // Preserve the exact M144 default behavior for SDR8/H264 and explicit C1 fallback.
            init(sharedContext, rendererEvents)
            GfnHevc10BitDiagnostics.logPinnedWebRtcEglRequest()
        }

        preflight?.let {
            Gfn10BitRenderDiagnostics.logEgl10BitCapability(
                viewId = forensicViewId,
                result = it,
                phase = "EGL10_PREFLIGHT",
            )
        }
        Gfn10BitRenderDiagnostics.logRendererTargetRequest(
            viewId = forensicViewId,
            requestedRgb10A2 = requestRgb10A2,
            activeRgb10A2 = activeRgb10A2,
            selectedConfigId = selected?.configId,
            fallbackReason = if (requestRgb10A2 && !activeRgb10A2) {
                preflight?.error ?: "RGB10A2 candidate missing exact native-visual match"
            } else {
                null
            },
        )

        addFrameListener(
            { _ ->
                val runtime = GfnEglConfigProbe.queryCurrentEgl14()
                GfnHevc10BitDiagnostics.logRuntimeEglConfig(
                    viewId = forensicViewId,
                    result = runtime,
                )
                Gfn10BitRenderDiagnostics.logRuntimeTargetVerdict(
                    viewId = forensicViewId,
                    requestedRgb10A2 = requestRgb10A2,
                    result = runtime,
                )
                Gfn10BitRenderDiagnostics.logEgl10BitCapability(
                    viewId = forensicViewId,
                    result = GfnEgl10BitCapabilityProbe.queryCurrentDisplayEgl14(),
                )
            },
            0f,
        )
        setEnableHardwareScaler(true)
        setMirror(false)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setOnClickListener { requestKeyboardMouseCapture() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        super.surfaceChanged(holder, format, width, height)
        Gfn10BitRenderDiagnostics.logSurfaceFormat(
            viewId = forensicViewId,
            requestedRgb10A2 = requestRgb10A2,
            activeRgb10A2 = activeRgb10A2,
            actualFormat = format,
            width = width,
            height = height,
        )
    }

    override fun onFrame(frame: VideoFrame) {
        onFrameActivity?.invoke()
        super.onFrame(frame)
    }

    /**
     * Arms one generation-local, zero-scale witness on the existing M144 renderer. The callback is
     * one-shot in EglRenderer and is reached only after the render thread has a usable EGL surface
     * and progresses through its frame-render path. No bitmap is allocated at scale 0.
     */
    fun armRenderedFrameWitness(onRendered: () -> Unit) {
        if (released) return
        addFrameListener(
            { _ ->
                if (!released) onRendered()
            },
            0f,
        )
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
        if (isGamepadEvent(event.device, event.source)) {
            val handled = inputListener?.onGamepadKey(true, event) == true
            return handled || super.onKeyDown(keyCode, event)
        }
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
        if (isGamepadEvent(event.device, event.source)) {
            val handled = inputListener?.onGamepadKey(false, event) == true
            return handled || super.onKeyUp(keyCode, event)
        }
        val trace = GfnInputForensics.traceForSurface(event)
        val handled = inputListener?.onKey(false, trace) == true
        GfnInputForensics.markSurfaceHandled(trace, handled)
        return handled || super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (inputCaptureEnabled && isGamepadEvent(event.device, event.source)) {
            val handled = inputListener?.onGamepadMotion(event) == true
            if (handled) return true
        }
        return super.onGenericMotionEvent(event)
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

    private fun isGamepadEvent(device: InputDevice?, source: Int): Boolean {
        val sources = device?.sources ?: source
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    fun releaseRenderer() {
        if (released) return
        released = true
        inputListener?.onPointerCaptureChanged(false)
        inputListener = null
        onFirstFrame = null
        onResolutionChanged = null
        onFrameActivity = null
        releaseKeyboardMouseCapture()
        release()
    }
}
