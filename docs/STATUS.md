# Current v6.1.1 Main10 / SDR10 — Stage C1 custom RGB10A2 target

## Closed milestones

```text
v6.0.4  HEVC Main / SDR8 production                         TRUE-DEVICE PASS (45.log)
v6.1.0  Main10 / SDR10 capability + negotiation             TRUE-DEVICE PASS (46.log)
v6.1.1  DecodeInfo JNI null hotfix                          TRUE-DEVICE PASS (50.log)
v6.1.1  Stage A actual HEVC SPS bit depth                   TRUE-DEVICE PASS (50.log)
v6.1.1  Stage B exact M144 default EGL target               TRUE-DEVICE PASS / PROVEN RGB888 (50.log)
v6.1.1  Stage C0 exact fixed RGB10A2 window capability       TRUE-DEVICE PASS (51.log)
v6.1.1  reconnect black-screen repair                        TRUE-DEVICE MANUAL PASS (user test, 2026-08-15)
```

The reconnect closeout above is an explicit operator true-device result from this conversation; no new reconnect log was attached with that statement, so it is not presented as line-addressable log evidence. All negotiation behavior remains frozen. HDR remains OFF.

## Stage A/B/C0 evidence boundary

`50.log` directly proves the incoming elementary stream is HEVC Main10, 4:2:0, luma/chroma 10/10-bit. The same log proves the pinned M144 default final EGL target is `R8 G8 B8 A0`.

`51.log` then proves the tested device exposes an exact fixed/non-float RGB10A2 window config:

```text
status=Supported
configId=65
red=10 green=10 blue=10 alpha=2
nativeVisualId=43
nativeVisualMatchesSurface=true
explicitFloat=false
```

This justifies Stage C1. It does not prove source texture precision.

## Stage C1 active design

Only an immutable Session snapshot with:

```text
codec=Hevc
colorMode=PreferSdr10
```

requests the custom target. SDR8/H264 still uses the original M144 two-argument renderer init.

For an SDR10/Main10 view, Stage C1 first resolves the existing shared WebRTC EGL root, then performs a selection-only default-display preflight. Activation requires:

```text
status=Supported
exact R10/G10/B10/A2 + WINDOW + GLES2
not explicitly floating-point
nativeVisualMatchesRequestedSurfaceFormat=true
```

The selected `EGL_CONFIG_ID` is then included in the custom renderer attributes so M144 does not merely choose an arbitrary config satisfying minimum channel sizes. Renderer creation uses the pinned overload:

```text
SurfaceViewRenderer.init(
    sharedContext,
    rendererEvents,
    customConfigAttributes,
    GlRectDrawer
)
```

C1 first-build single-variable boundary:

```text
final renderer EGLConfig                 RGB888 -> exact RGB10A2
SurfaceHolder.setFormat                  NOT CALLED
decoder/shared root EGL                  FROZEN
MediaCodec / SurfaceTexture source path  FROZEN
Main10 negotiation                       FROZEN
reconnect state machine                  FROZEN
HDR                                       OFF
```

If preflight is unsupported/unresolved or native-visual matching is not proven, the view explicitly uses the existing RGB888 renderer and logs a fallback reason. That is visibility fallback only and must never be labeled 10-bit target PASS.

## Stage C1 true-device PASS gate

The next log must show all of:

```text
GfnHevc10Bit phase=EGL10_PREFLIGHT
status=Supported supported=true
red=10 green=10 blue=10 alpha=2
nativeVisualMatchesSurface=true
explicitFloat=false

GfnHevc10Bit phase=EGL_TARGET_REQUEST
requested=RGB10A2
active=RGB10A2
selectedConfigId=<dynamic>
holderSetFormat=false

GfnHevc10Bit phase=SURFACE_FORMAT
actualCallbackFormat=<actual>

GfnHevc10Bit phase=EGL_CONFIG
red=10 green=10 blue=10 alpha=2
tenBitRgbTarget=true

GfnHevc10Bit phase=EGL_TARGET_ACTIVE
requestedRgb10A2=true
active=true
exactRgb10A2=true

BITSTREAM_SPS bitDepthLuma=10 bitDepthChroma=10
FIRST_VIDEO_RTP
FIRST_FRAME
stable rendering near requested 60fps
fallback=false
HDR=false
```

Requested attributes alone are not a PASS. Runtime selected config is the gate.

## Remaining fidelity boundary

Even a Stage C1 PASS means only:

```text
Actual SPS 10-bit       PASS
Final RGB10A2 target    PASS
Source texture/buffer   UNKNOWN
```

Stage C2 must investigate MediaCodec producer -> BufferQueue/GraphicBuffer -> SurfaceTexture/external texture precision before full 10-bit decode/output/render fidelity can be claimed. Direct MediaCodec -> app-owned Surface remains a later escalation only.

## Independent backlog

Fullscreen/Activity `No surface` windows remain a separate lifecycle issue. C1 does not modify that lifecycle.
