# GFN Android v6.1.1 — Stage C2.0 Source Frame True-device Test Guide

## 1. Test profile

Keep the already proven C1 profile unchanged:

```text
codec=HEVC
color=PreferSdr10
1920x1080@60
HDR=false
```

Do not intentionally change network, fullscreen lifecycle, renderer config or SurfaceHolder format during the first C2.0 run.

## 2. Regression gates

Require the frozen chain:

```text
RESOLVED codec=Hevc color=PreferSdr10
CloudMatch bitDepth=1 / hdr=false
HEVC_MAIN10_ADVERTISEMENT profile=2
OFFER_HEVC_COMPATIBLE targetProfile=2 compatible=true
RAW/FINAL Answer Main10
fallback=false
NVST bitDepth=10 hdr=false
BITSTREAM_SPS bitDepthLuma=10 bitDepthChroma=10
FIRST_VIDEO_RTP
FIRST_FRAME
```

C1 must still be runtime active:

```text
EGL_TARGET_REQUEST requested=RGB10A2 active=RGB10A2 fallback=NONE
EGL_CONFIG red=10 green=10 blue=10 alpha=2
EGL_TARGET_ACTIVE active=true exactRgb10A2=true
```

## 3. C2.0 decisive log

Search:

```text
GfnHevc10Bit phase=SOURCE_FRAME
```

Record the complete line.

Expected if the actual downstream path matches pinned M144 texture mode:

```text
texture=true
textureType=OES
isOes=true
glTarget=36197
toI420Called=false
```

Also record:

```text
bufferClass
bufferType
size
textureId
unscaled size
rotation
```

Texture ids are runtime-local and must never be hardcoded.

## 4. C2.0 PASS rule

Only this classification closes C2.0 OES path:

```text
actual true-device SOURCE_FRAME
+ TextureBuffer
+ type=OES
```

This proves the app sink is receiving an OES texture buffer. It does **not** prove the texture carries 10-bit numerical precision.

## 5. Negative / alternate results

### RGB

```text
textureType=RGB
```

Stop OES assumptions. Rebuild the source-path model from the observed frame.

### Non-texture

```text
texture=false
```

Stop OES-specific work. Inspect the concrete buffer class/type first.

### Unresolved

```text
phase=SOURCE_FRAME_UNRESOLVED
```

Treat as instrumentation failure only. Do not infer 8-bit or 10-bit.

## 6. Forbidden interpretation

Do not call `toI420()` to test source precision. M144 defines it as a conversion fallback to I420, so the test itself would introduce an 8-bit representation.

Do not use screenshots, visual banding, natural-image histograms or unique-RGB counts as fidelity proof.

## 7. Performance / safety

The probe is one-shot per SDR10 View. Verify normal behavior remains:

```text
FIRST_FRAME
stable near 60fps
no persistent No surface
no EGL exception
no NPE / SIGABRT
```

## 8. Next decision

If C2.0 is OES PASS:

```text
freeze C2.0
→ separate C2.1 native-window / producer metadata witness
```

Do not add `holder.setFormat()`, direct MediaCodec Surface, HDR, or a new shader in the same run.
