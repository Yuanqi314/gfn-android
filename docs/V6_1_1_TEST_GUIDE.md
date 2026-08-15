# GFN Android v6.1.1 — Stage C1 RGB10A2 True-device Test Guide

## 1. Test profile

Use the frozen Main10 profile:

```text
codec=HEVC
color=PreferSdr10
1920x1080@60
HDR=false
```

Do not change fullscreen lifecycle or network conditions during the first C1 run.

## 2. Frozen negotiation regression

Require the existing chain:

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
```

## 3. Actual bitstream regression

Require:

```text
phase=BITSTREAM_SPS
profileIdc=2
chromaFormatIdc=1
bitDepthLuma=10
bitDepthChroma=10
tenBit=true
```

If SPS changes, stop; do not blame C1.

## 4. C1 preflight

Search:

```text
GfnHevc10Bit phase=EGL10_PREFLIGHT
```

Expected on the currently tested device:

```text
status=Supported
supported=true
configId=65                  // config ids are device/driver-specific; do not hardcode in logic
red=10 green=10 blue=10 alpha=2
nativeVisualId=43
nativeVisualMatchesSurface=true
explicitFloat=false
```

The source dynamically pins whatever exact config id preflight selects.

## 5. Target request

Require:

```text
phase=EGL_TARGET_REQUEST
requested=RGB10A2
active=RGB10A2
selectedConfigId=<same preflight id>
holderSetFormat=false
fallback=NONE
```

If `active=WEBRTC_M144_RGB888`, C1 did not activate. Preserve the fallback reason.

## 6. Native Surface witness

Search:

```text
phase=SURFACE_FORMAT
```

Record:

```text
actualCallbackFormat
size
expectedRgb10A2
```

On the current device the expected Android constant is `43`. A different value is evidence for a separate Surface-format investigation; do not silently add `holder.setFormat()` in the same run.

## 7. Runtime EGL — decisive C1 gate

Require after a real rendered frame:

```text
phase=EGL_CONFIG
success=true
red=10
green=10
blue=10
alpha=2
tenBitRgbTarget=true
```

And:

```text
phase=EGL_TARGET_ACTIVE
requestedRgb10A2=true
active=true
exactRgb10A2=true
```

A request/preflight record is not a PASS without this runtime result.

## 8. Frame stability

Require:

```text
FIRST_FRAME
bound c2.qti.hevc.decoder unchanged
input/output/render approximately requested 60fps
no persistent No surface state
no EGL thread/window-surface exception
no SIGABRT / NPE
```

Short Surface recreation drops remain a separate known lifecycle backlog if they recover.

## 9. SDR8/H264 negative control

For an SDR8/H264 Session, source behavior must remain:

```text
requested=WEBRTC_M144_RGB888
active=WEBRTC_M144_RGB888
phase=EGL_REQUEST source=WebRTC_M144_CONFIG_PLAIN
runtime EGL remains the normal M144 target
```

RGB10A2 must not be activated from live Settings or generic HEVC alone.

## 10. Verdict

Only when the Main10 run satisfies all gates above may we record:

```text
v6.1.1-C Custom RGB10A2 final EGL target
TRUE-DEVICE PASS
```

This still does **not** equal full 10-bit render fidelity. Stage C2 source texture/buffer evidence remains required. HDR stays OFF.
