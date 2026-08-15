# Current v6.1.1 Main10 / SDR10 — Stage C0 RGB10A2 capability probe

## Closed milestones

```text
v6.0.4  HEVC Main / SDR8 production                         TRUE-DEVICE PASS (45.log)
v6.1.0  Main10 / SDR10 capability + negotiation             TRUE-DEVICE PASS (46.log)
v6.1.1  DecodeInfo JNI null hotfix                          TRUE-DEVICE PASS (50.log)
v6.1.1  Stage A actual HEVC SPS bit depth                   TRUE-DEVICE PASS (50.log)
v6.1.1  Stage B exact M144 default EGL target               TRUE-DEVICE PASS / PROVEN RGB888 (50.log)
```

All negotiation behavior above remains frozen. HDR remains OFF.

## `50.log` Stage A/B closeout

Actual elementary stream (`50.log:669`):

```text
profileIdc=2
Tier=High
levelIdc=150
chromaFormatIdc=1
coded=1920x1088
display=1920x1080
bitDepthLuma=10
bitDepthChroma=10
tenBit=true
```

This directly proves that the observed GFN HEVC elementary stream is Main10, 4:2:0 and 10-bit for both luma and chroma. It is not inferred from SDP.

Pinned M144 request (`50.log:359`):

```text
source=WebRTC_M144_CONFIG_PLAIN
red=8 green=8 blue=8
```

Runtime selected EGLConfig (`50.log:682`, `1096`, `1456`):

```text
configId=5
red=8 green=8 blue=8 alpha=0
tenBitRgbTarget=false
```

Therefore the current default render target is proven RGB888 on the tested device. This proves a final 8bpc target; it does **not** prove that MediaCodec producer buffers themselves are 8-bit.

## DecodeInfo JNI hotfix closeout

Pinned WebRTC M144 native code passes a null Java `DecodeInfo` reference to `VideoDecoder.decode()`. The v6.1.1 decorator now accepts `VideoDecoder.DecodeInfo?` and forwards it unchanged.

`50.log` contains no recurrence of:

```text
NullPointerException
jvm.cc CHECK
SIGABRT
Fatal signal
```

The same session reaches SPS parsing, FIRST_FRAME and stable rendering, so the hotfix is TRUE-DEVICE PASS.

## Current Stage C0 scope

The next unknown is whether the tested EGL display exposes an exact RGB10A2 window config that can be paired with Android `PixelFormat.RGBA_1010102`.

Stage C0 adds only a read-only capability query on the **existing RGB888 renderer thread**:

```text
current EGLDisplay
        ↓
eglChooseConfig
WINDOW_BIT + GLES2 + R10 G10 B10 A2
        ↓
eglGetConfigAttrib
configId / R/G/B/A / renderableType / surfaceType / nativeVisualId / colorComponentType
```

The probe performs an exact two-pass `eglChooseConfig` enumeration, bounded defensively at 4096 candidates. No context or surface is created by the probe. The existing renderer remains initialized through M144 `CONFIG_PLAIN`.

Expected log:

```text
GfnHevc10Bit phase=EGL10_CAPABILITY
status=Supported|Unsupported|Unresolved
probeRequest=R10G10B10A2
candidateSurface=RGBA_1010102
supported=true|false
...
```

Stage C0 activation evidence requires an exact candidate:

```text
status=Supported
red=10
green=10
blue=10
alpha=2
WINDOW_BIT present
GLES2 bit present
explicitFloat=false
```

`Unsupported` means complete enumeration found no match; `Unresolved` means the evidence query failed or was incomplete. `nativeVisualId` and `colorComponentType` are reported separately; neither is silently invented when unavailable.

## Dormant Stage C1 contract

The source now contains a dormant `GfnEgl10BitConfig` contract:

```text
EGL R10 G10 B10 A2
EGL_WINDOW_BIT
EGL_OPENGL_ES2_BIT
Android PixelFormat.RGBA_1010102
```

It is **not** connected to `GfnVideoSurfaceView.init()` and `SurfaceHolder.setFormat()` is **not** called in Stage C0.

Stage C1 can only be activated after true-device C0 returns `supported=true`.

When activated later, Stage C1 must change only the final render-target EGLConfig first:

```text
existing decoder context/path          FROZEN
existing SurfaceTexture source path    FROZEN
existing Surface lifecycle             FROZEN
GfnVideoSurfaceView EGL config         -> R10G10B10A2
SurfaceHolder explicit format          still gated / no first-build co-variation
```

Android EGL window-surface creation derives/programs the native-window format from the chosen EGLConfig, so explicit `holder.setFormat(RGBA_1010102)` is retained as a second, separately evidenced variable rather than changed simultaneously. The runtime query must prove `10/10/10/2`; requested attributes alone are not a PASS.

## Remaining fidelity boundary

Even a future Stage C1 RGB10A2 target PASS will not prove full 10-bit fidelity by itself:

```text
Actual SPS 10-bit        PROVEN
Final >=10bpc target     Stage C1 gate
Source texture/buffer    still requires Stage C2 evidence
```

Full v6.1.1 PASS still requires enough evidence that the MediaCodec -> BufferQueue/SurfaceTexture -> external texture path did not convert to 8-bit before the final target.

## Frozen boundaries

```text
CloudMatch bitDepth=1                         FROZEN
ResolvedLaunchProfile PreferSdr10             FROZEN
Main10 capability/advertisement               FROZEN
Original GFN Offer                            FROZEN
RAW/FINAL Answer lineage                      FROZEN
strict no-H264 fallback                       FROZEN
NVST bitDepth=10                              FROZEN
exact decoder component binding               FROZEN
HDR                                            OFF / v6.2
```

## Independent backlog

`EglRenderer: Dropping frame - No surface` remains correlated with fullscreen / Activity / Surface recreation in `50.log`. Stage C0 does not modify that lifecycle so the RGB10A2 experiment remains single-variable.
