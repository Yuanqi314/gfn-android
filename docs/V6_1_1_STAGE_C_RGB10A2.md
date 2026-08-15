# GFN Android v6.1.1 — Stage C RGB10A2

## 1. Evidence chain

```text
50.log: actual HEVC SPS = Main10 / 4:2:0 / luma10 / chroma10
50.log: current M144 final EGL = R8 G8 B8 A0
51.log: exact fixed RGB10A2 window capability exists
user manual true-device reconnect test: disconnect/reconnect recovered normally
```

The reconnect result above is operator-reported in this conversation and is not treated as a substitute for line-addressable log evidence. It is sufficient to remove the known reconnect blocker before changing the render-target variable.

## 2. Stage C0 closeout

The tested device reported:

```text
configId=65
R10 G10 B10 A2
WINDOW + GLES2
nativeVisualId=43
nativeVisualMatchesSurface=true
explicitFloat=false
```

Stage C0 is TRUE-DEVICE PASS.

## 3. Stage C1 activation scope

Stage C1 is active only when the frozen Session profile is:

```text
VideoCodecPreference.Hevc
RequestedColorMode.PreferSdr10
```

The decision is derived from `GfnStreamingController.frozenProfile`, not live Settings. Fullscreen recreation and reconnect therefore retain the same render-target intent.

SDR8/H264 views remain on the exact M144 default two-argument `SurfaceViewRenderer.init(sharedContext, rendererEvents)` path.

## 4. Pre-init exact-config gate

The shared WebRTC EGL root is initialized first. Stage C1 then performs a default-display selection-only query using the same exact C0 classifier. It must find:

```text
exact R10/G10/B10/A2
EGL_WINDOW_BIT
EGL_OPENGL_ES2_BIT
not explicitly FLOAT_EXT
nativeVisual == PixelFormat.RGBA_1010102
```

If this gate fails or is unresolved, custom activation is refused and the view falls back to M144 RGB888 with an explicit diagnostic.

The selected dynamic `EGL_CONFIG_ID` is inserted into the renderer attribute list. This is intentional: EGL channel-size attributes are selection constraints, and C1 must not assume WebRTC's one-config `eglChooseConfig` call will return the exact candidate that C0 inspected.

## 5. Custom M144 renderer path

C1 uses the existing upstream overload:

```text
SurfaceViewRenderer.init(sharedContext, rendererEvents, configAttributes, GlRectDrawer)
```

The config contains:

```text
EGL_CONFIG_ID       = preflight selected id
EGL_SURFACE_TYPE    = WINDOW
EGL_RENDERABLE_TYPE = GLES2
EGL_RED_SIZE        = 10
EGL_GREEN_SIZE      = 10
EGL_BLUE_SIZE       = 10
EGL_ALPHA_SIZE      = 2
```

No custom shader/drawer is introduced; `GlRectDrawer` remains the pinned M144 drawer class.

## 6. Surface format remains a separate variable

C1 first build does **not** call:

```text
SurfaceHolder.setFormat(PixelFormat.RGBA_1010102)
```

`EGL_NATIVE_VISUAL_ID` already matched the Android `RGBA_1010102` constant on the tested device. The actual `SurfaceHolder.Callback.surfaceChanged()` format is now logged as `phase=SURFACE_FORMAT`. If runtime proves a mismatch or `eglCreateWindowSurface` fails, explicit `holder.setFormat()` becomes a separately isolated C1b experiment.

## 7. Diagnostics

New/continued records:

```text
phase=EGL10_PREFLIGHT       pre-init exact config gate
phase=EGL_TARGET_REQUEST    requested vs actually configured target
phase=SURFACE_FORMAT        native Surface callback format
phase=EGL_CONFIG            actual current runtime EGLConfig
phase=EGL_TARGET_ACTIVE     exact RGB10A2 runtime verdict
phase=EGL10_CAPABILITY      post-render capability witness
```

For C1 PASS, `EGL_TARGET_REQUEST active=RGB10A2` is necessary but insufficient. The decisive record is runtime `EGL_CONFIG=10/10/10/2` plus `EGL_TARGET_ACTIVE active=true exactRgb10A2=true`.

## 8. Failure classification

```text
preflight Unsupported/Unresolved
→ custom target not activated; explicit RGB888 fallback; C1 FAIL/NOT TESTED

preflight Supported but nativeVisual mismatch/unknown
→ custom target not activated; C1 remains gated

custom target requested/activated but no FIRST_FRAME or EGL thread/window-surface error
→ C1 renderer compatibility failure; do not change decoder/source path simultaneously

runtime EGL still RGB888 or non-exact
→ C1 FAIL; requested attributes are not accepted as proof

runtime exact RGB10A2 + stable frame rendering
→ C1 TRUE-DEVICE PASS; proceed to C2 source-buffer/texture fidelity
```

## 9. Stage C1 is not full 10-bit fidelity PASS

Even after C1 succeeds:

```text
10-bit encoded source     PASS
10bpc final EGL target    PASS
source texture precision  UNKNOWN
```

Stage C2 must investigate the MediaCodec producer / BufferQueue / GraphicBuffer / SurfaceTexture path. `COLOR_FormatSurface` alone cannot prove P010 or 10-bit preservation.

## 10. Frozen boundaries

```text
CloudMatch / SDP / Answer / NVST / decoder binding  FROZEN
reconnect implementation                             FROZEN
SurfaceHolder explicit pixel format                  OFF in C1 first build
HDR                                                  OFF / v6.2
direct MediaCodec -> Surface                         NOT ENTERED
```

## 11. Stage C1 true-device closeout (`53.log`)

`53.log` closes C1. Two independent renderer lifecycles both reached:

```text
EGL10_PREFLIGHT exact fixed R10G10B10A2
EGL_TARGET_REQUEST active=RGB10A2 fallback=NONE
actual Main10 SPS 10/10-bit
FIRST_FRAME
runtime EGL_CONFIG R10G10B10A2
tenBitRgbTarget=true
EGL_TARGET_ACTIVE exactRgb10A2=true
stable near 60fps
```

The observed `surfaceChanged format=4` remains a witness mismatch against `EGL_NATIVE_VISUAL_ID=43`; it does not erase the runtime EGL target evidence. Explicit `SurfaceHolder.setFormat()` remains OFF so C1 stays a single-variable experiment.

C1 final verdict:

```text
Custom RGB10A2 final EGL target = TRUE-DEVICE PASS
Full source texture precision   = NOT YET PROVEN
```

Stage C2 now owns the remaining producer/BufferQueue/SurfaceTexture/OES precision question.
