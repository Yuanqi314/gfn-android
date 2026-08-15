# GFN Android v6.1.1 — Stage C RGB10A2

## 1. Evidence that justifies Stage C

`50.log` closes both prerequisites:

```text
Stage A:
actual SPS = Main10 / 4:2:0 / luma10 / chroma10

Stage B:
current M144 runtime EGLConfig = R8 G8 B8 A0
```

Therefore a custom >=10bpc final render target is justified. The exact earlier 10->8 conversion point remains unknown.

## 2. Stage C0 — read-only capability probe

Stage C0 must not alter the renderer. `GfnEgl10BitCapabilityProbe` runs from the existing zero-scale render-thread callback and uses the already-current EGLDisplay.

Probe attributes:

```text
EGL_SURFACE_TYPE      = EGL_WINDOW_BIT
EGL_RENDERABLE_TYPE   = EGL_OPENGL_ES2_BIT
EGL_RED_SIZE          = 10
EGL_GREEN_SIZE        = 10
EGL_BLUE_SIZE         = 10
EGL_ALPHA_SIZE        = 2
```

The probe performs a two-pass `eglChooseConfig` query: first obtain the exact candidate count, then inspect every returned candidate within a defensive 4096-config bound. A count beyond that bound is `Unresolved`, not `Unsupported`.

For returned candidates it reads:

```text
EGL_CONFIG_ID
EGL_RED_SIZE
EGL_GREEN_SIZE
EGL_BLUE_SIZE
EGL_ALPHA_SIZE
EGL_RENDERABLE_TYPE
EGL_SURFACE_TYPE
EGL_NATIVE_VISUAL_ID
EGL_COLOR_COMPONENT_TYPE_EXT (when advertised by the EGL extension string)
```

The color-component type matters because an R/G/B/A channel-size match alone must not silently classify a floating-point config as RGB10A2. `FLOAT_EXT` candidates are excluded from the fixed/non-float RGB10A2 selection.

Stage C0 produces one of three states:

```text
Supported
  exact R10/G10/B10/A2 + WINDOW + GLES2 candidate exists
  and it is not explicitly floating-point

Unsupported
  complete enumeration finished and no exact usable candidate exists

Unresolved
  EGL query failed, enumeration was incomplete, or the defensive candidate bound was exceeded
```

`Unsupported` and `Unresolved` are deliberately different. Missing evidence is never converted into a negative capability verdict.

No `eglCreateContext`, `eglCreateWindowSurface`, `eglMakeCurrent`, `eglDestroy*`, `holder.setFormat()` or renderer re-init occurs in C0.

## 3. Stage C0 TRUE-DEVICE PASS (`51.log`)

The tested device returned:

```text
phase=EGL10_CAPABILITY
status=Supported
supported=true
configId=65
red=10 green=10 blue=10 alpha=2
nativeVisualId=43
nativeVisualMatchesSurface=true
explicitFloat=false
```

The same run still used the existing RGB888 production renderer and reached FIRST_FRAME. Stage C0 is therefore closed as TRUE-DEVICE PASS.

`51.log` later exposed an independent reconnect black-screen bug: stale `FirstFrame/Connected` state canceled the DISCONNECTED grace before ICE/PC or media were actually healthy. C1 activation is temporarily paused until that reconnect fix is true-device verified; this preserves the C1 render-target A/B as a single controlled variable.

## 4. C1 contract is present but inactive

`GfnEgl10BitConfig` defines the future RGB10A2 render-target contract:

```text
EGL render target: R10 G10 B10 A2 + WINDOW + GLES2
Android format witness: PixelFormat.RGBA_1010102
```

The production view does not use these attributes yet. This is deliberate: true-device Stage C0 evidence is the activation gate.

The Android native-window format must not be guessed from the Java constant alone. Stage C0 therefore records `EGL_NATIVE_VISUAL_ID` as independent evidence and preserves `UNKNOWN` when it cannot be resolved.

## 5. C1 activation rule — keep the first experiment single-variable

The RGB10A2 capability prerequisite is now satisfied by `51.log`. C1 may activate only after the reconnect black-screen repair is also true-device verified; then it may activate the custom `SurfaceViewRenderer.init(..., configAttributes, GlRectDrawer())` overload with the R10G10B10A2 attributes.

The first C1 experiment should change **only the renderer EGLConfig** while leaving the existing decoder EGL context, decoder SurfaceTexture path, PeerConnection factory and Surface lifecycle unchanged.

An explicit:

```text
holder.setFormat(PixelFormat.RGBA_1010102)
```

is not treated as an unconditional prerequisite in the first C1 build. Android EGL window-surface creation already derives/programs the native-window buffer format from the chosen EGLConfig. `SurfaceHolder.setFormat()` remains a separately gated follow-up variable if true-device evidence shows a native-window format mismatch or EGL window-surface creation failure. If used later, it must be called on the SurfaceView window thread.

This refinement avoids changing both the EGLConfig and Java Surface format in the same first C1 A/B.

## 6. C1 PASS gate

Requested configuration is not sufficient. True-device runtime evidence must show:

```text
EGL_CONFIG
red=10
green=10
blue=10
alpha=2
tenBitRgbTarget=true
```

The log must also preserve the selected config ID, renderable/surface bits, `nativeVisualId`, and any available color-component-type evidence.

At the same time all frozen gates must remain true:

```text
SPS luma/chroma 10/10
Main10 negotiation
fallback=false
bound c2.qti.hevc.decoder
FIRST_VIDEO_RTP
FIRST_FRAME
stable rendering
HDR=false
```

If custom EGL activation fails or runtime still selects RGB888, C1 fails closed. It may retain the known RGB888 path for visibility during experimentation, but diagnostics must not call that a 10-bit render PASS.

## 7. Stage C1 is not full fidelity PASS

If C1 succeeds:

```text
10-bit encoded source    PASS
10bpc final target       PASS
source texture fidelity  UNKNOWN
```

Stage C2 must then inspect the producer/BufferQueue/GraphicBuffer/SurfaceTexture side. Do not infer source precision from `COLOR_FormatSurface`.

## 8. Direct MediaCodec -> Surface remains gated

Do not switch architecture in C0/C1. Direct output is considered only if the existing WebRTC texture path is proven to have already lost precision or cannot expose equivalent preservation evidence.

## 9. HDR boundary

HDR remains OFF. RGB10A2 here is an SDR10 precision experiment, not HDR10 activation.
