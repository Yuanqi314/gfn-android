# GFN Android v6.1.1 — Stage C0 RGB10A2 Capability Test Guide

## 1. Test profile

Use the same frozen true-device profile that produced `50.log`:

```text
codec = HEVC
color = PreferSdr10 / SDR10
1920x1080@60
HDR = OFF
```

Do not change fullscreen/surface lifecycle behavior for this run.

## 2. Regression chain

The log must still contain:

```text
RESOLVED codec=Hevc color=PreferSdr10
CREATE cloudMatchBitDepth=1 sdrHdrMode=0 hdr=false
HEVC_MAIN10_ADVERTISEMENT enabled=true profile=2
OFFER_HEVC_COMPATIBLE targetProfile=2 compatible=true
RAW_ANSWER hevcMain10 != []
FINAL_ANSWER hevcMain10 != []
fallback=false
NVST_CONFIG bitDepth=10 hdr=false
FIRST_VIDEO_RTP effective=Hevc targetProfile=2
FIRST_FRAME effective=Hevc targetProfile=2
```

## 3. Stage A regression

Expect:

```text
GfnHevc10Bit phase=BITSTREAM_SPS
profileIdc=2
chromaFormatIdc=1
bitDepthLuma=10
bitDepthChroma=10
tenBit=true
```

If this changes, stop and investigate the Session/server stream before any renderer work.

## 4. Stage B regression

Because C1 is still inactive, expect the current renderer to remain RGB888:

```text
phase=EGL_REQUEST
source=WebRTC_M144_CONFIG_PLAIN
red=8 green=8 blue=8
```

and:

```text
phase=EGL_CONFIG
red=8 green=8 blue=8
alpha=0
tenBitRgbTarget=false
```

If runtime EGL becomes 10bpc in C0, verify the binary/source first; Stage C0 itself does not activate a custom renderer.

## 5. Stage C0 result

Search for:

```text
GfnHevc10Bit phase=EGL10_CAPABILITY
```

The log now includes:

```text
status=Supported|Unsupported|Unresolved
supported=true|false
probeRequest=R10G10B10A2
candidateSurface=RGBA_1010102
surfacePixelFormat=43
candidateCount=...
inspectedCount=...
```

### Positive gate

C1 becomes eligible only when the same record contains:

```text
status=Supported
supported=true
red=10
green=10
blue=10
alpha=2
explicitFloat=false
```

Also preserve:

```text
configId
renderableType
surfaceType
nativeVisualId
nativeVisualMatchesSurface
colorComponentType
```

`nativeVisualMatchesSurface=false` or `UNKNOWN` is a separate native-window compatibility observation. Do not automatically convert it into either PASS or FAIL.

### `status=Unsupported`

The probe completed its enumeration and found no exact usable fixed/non-float R10G10B10A2 WINDOW+GLES2 candidate. Do not activate C1.

### `status=Unresolved`

Evidence collection failed or was incomplete. Do not interpret this as hardware/driver lack of RGB10A2 support. Preserve the `reason` and fix the probe/environment first.

## 6. C1 remains inactive in this build

For the Stage C0 APK, source/runtime must still show:

```text
SurfaceViewRenderer.init(sharedContext, rendererEvents)  // existing M144 CONFIG_PLAIN path
holder.setFormat(RGBA_1010102)                           // NOT CALLED
custom RGB10A2 renderer attributes                       // NOT ACTIVE
```

If C0 returns `Supported`, the next controlled build should first activate only the custom EGL R10G10B10A2 renderer configuration. Keep explicit `SurfaceHolder.setFormat(RGBA_1010102)` as a separately gated experiment unless native-window evidence requires it.

## 7. Crash guard

The `DecodeInfo` hotfix remains mandatory. There must be no recurrence of:

```text
GfnHevcBitstreamProbeVideoDecoder.decode parameter info
NullPointerException
jvm.cc CHECK
SIGABRT
```

## 8. Surface lifecycle

`Dropping frame - No surface` remains a separate backlog. Do not mix lifecycle changes into the C0/C1 bit-depth experiment unless it prevents the capability/runtime evidence from being collected.
