# Current v6.1.1 Main10 / SDR10 — Stage C0 RGB10A2 capability probe

## Closed milestones

```text
v6.0.4  HEVC Main / SDR8 production                         TRUE-DEVICE PASS (45.log)
v6.1.0  Main10 / SDR10 capability + negotiation             TRUE-DEVICE PASS (46.log)
v6.1.1  DecodeInfo JNI null hotfix                          TRUE-DEVICE PASS (50.log)
v6.1.1  Stage A actual HEVC SPS bit depth                   TRUE-DEVICE PASS (50.log)
v6.1.1  Stage B exact M144 default EGL target               TRUE-DEVICE PASS / PROVEN RGB888 (50.log)
v6.1.1  Stage C0 exact fixed RGB10A2 window capability       TRUE-DEVICE PASS (51.log)
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

## Stage C0 true-device closeout (`51.log`)

`51.log:637` proves an exact fixed/non-float RGB10A2 window config exists on the tested device:

```text
status=Supported
supported=true
configId=65
red=10 green=10 blue=10 alpha=2
renderableType=69
surfaceType=5541
nativeVisualId=43
nativeVisualMatchesSurface=true
colorComponentType=13114
explicitFloat=false
```

The production renderer in the same run remains RGB888 (`51.log:636`), reaches FIRST_FRAME (`51.log:629`) and initially renders at ~60 fps. This closes C0 without activating C1.

## Reconnect black-screen blocker found in `51.log`

The later black screen is not caused by the C0 capability probe. At `51.log:804-813`, ICE/PC become DISCONNECTED while logical `StreamState` remains `FirstFrame`; the old controller immediately logs `transient recovery without reclaim` and cancels its 7-second grace because its health predicate inspected only `StreamState`. The same defect repeats at `51.log:1472-1482`.

After transport reports CONNECTED again, decoder input eventually falls to `0 fps` (`51.log:925`, `947`, `969`) and EglRenderer falls to `1.2 fps` (`51.log:1027`). The black screen is therefore a transport/media-liveness recovery defect, not evidence that RGB10A2 probing damaged EGL.

The source fix now requires:

```text
Connected/FirstFrame logical state
+ ICE CONNECTED/COMPLETED
+ PC CONNECTED
+ sustained fresh VideoSink frames
+ one fresh existing-renderer-path witness
= transient recovery accepted
```

If the 7-second grace expires without verified media, the controller rebuilds the transport through the existing **same Session ID + frozen ResolvedLaunchProfile** path. C1 remains paused until this reconnect fix receives true-device verification. See `V6_1_1_RECONNECT_BLACK_SCREEN_FIX.md`.

## Dormant Stage C1 contract

The source now contains a dormant `GfnEgl10BitConfig` contract:

```text
EGL R10 G10 B10 A2
EGL_WINDOW_BIT
EGL_OPENGL_ES2_BIT
Android PixelFormat.RGBA_1010102
```

It is **not** connected to `GfnVideoSurfaceView.init()` and `SurfaceHolder.setFormat()` is **not** called in Stage C0.

Stage C0 has now returned `supported=true` on the tested device, but C1 is intentionally paused until the `51.log` reconnect black-screen fix is true-device verified.

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
