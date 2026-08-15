# GFN Android v6.1.1 — Main10 / SDR10 10-bit Forensics

## 1. Current evidence state

v6.1.0 negotiation is frozen and TRUE-DEVICE PASS. `50.log` additionally closes v6.1.1 Stage A/B:

```text
Stage A actual HEVC SPS
Main10 / High / 4:2:0
bitDepthLuma=10
bitDepthChroma=10
TRUE-DEVICE PASS

Stage B pinned M144 renderer
CONFIG_PLAIN static request = RGB888
runtime selected config = R8 G8 B8 A0
TRUE-DEVICE PROVEN
```

Therefore the next question is no longer whether the incoming stream is 10-bit or whether the default final target is 8bpc. Both are proven.

The remaining question is:

> Can the existing WebRTC texture/render path be paired with a real 10bpc final target, and if so, does the source texture/buffer preserve 10-bit precision before that target?

## 2. Frozen chain

```text
CloudMatch bitDepth=1 / hdr=false
ResolvedLaunchProfile PreferSdr10
Main10 capability probe
profile-id=2 advertisement
original GFN Main10 Offer
profile-specific compatibility
RAW/FINAL Main10 Answer lineage
strict no-H264 fallback
NVST bitDepth=10 / hdr=false
exact decoder-component binding
```

No v6.1.1 render diagnostic may mutate this chain.

## 3. Stage A — actual bitstream

`GfnHevcBitstreamProbe` decorates the selected H265 decoder. It synchronously inspects a duplicate/slice view of `EncodedImage.buffer` and forwards the same `EncodedImage` plus the same nullable M144 `DecodeInfo` to the exact delegate.

### Ownership invariant

```text
EncodedImage object              unchanged
ByteBuffer position              unchanged
ByteBuffer limit                 unchanged
encoded bytes                    unchanged
retain/release ownership         unchanged
DecodeInfo nullability/identity  unchanged
```

Supported framing:

```text
Annex-B start code 3/4 byte
length-prefixed NAL 1/2/3/4-byte length
single NAL fallback
```

SPS NAL type 33 parsing reaches:

```text
profile_tier_level
chroma_format_idc
coded/display dimensions
bit_depth_luma_minus8
bit_depth_chroma_minus8
```

`50.log:669` proves:

```text
profileIdc=2
tier=1
levelIdc=150
chromaFormatIdc=1
coded=1920x1088
display=1920x1080
bitDepthLuma=10
bitDepthChroma=10
tenBit=true
```

Stage A is closed PASS.

## 4. Stage B — default M144 EGL closure

Pinned M144:

```text
SurfaceViewRenderer.init(sharedContext, rendererEvents)
-> EglBase.CONFIG_PLAIN
-> RED=8 GREEN=8 BLUE=8
```

`GfnEglConfigProbe` queries the current render-thread EGLConfig without changing it.

`50.log` proves the actual selected configuration three independent times:

```text
configId=5
red=8
green=8
blue=8
alpha=0
tenBitRgbTarget=false
```

Stage B is closed: current default final target is RGB888 on the tested device.

This still does **not** prove the MediaCodec producer or SurfaceTexture source is 8-bit. `COLOR_FormatSurface` only establishes Surface output mode.

## 5. Stage C0 — RGB10A2 capability

Stage C0 is intentionally non-destructive. From the current renderer thread it calls only:

```text
eglChooseConfig
eglGetConfigAttrib
```

Target capability query:

```text
WINDOW_BIT
GLES2
R10 G10 B10 A2
```

Candidate evidence includes:

```text
configId
R/G/B/A
renderableType
surfaceType
nativeVisualId
colorComponentType (when EGL exposes it)
```

C0 uses an exact two-pass candidate enumeration and distinguishes `Supported`, `Unsupported` and `Unresolved`. An explicitly floating-point config is not silently classified as RGB10A2. The dormant Android format witness is `PixelFormat.RGBA_1010102`.

No custom renderer is activated in C0. The existing runtime query must therefore still show RGB888 while the new capability log answers whether an exact RGB10A2 window config exists.

## 6. Stage C1 true-device closeout

`53.log` proves the custom renderer actually selected and used `R10/G10/B10/A2` at runtime, with Main10 10-bit SPS, FIRST_FRAME and stable near-60fps rendering. The result repeated across two renderer lifecycles.

`SurfaceHolder.setFormat(RGBA_1010102)` remains OFF. The observed SurfaceHolder callback format mismatch is retained as a separate metadata witness and does not replace the runtime EGLConfig result.

Stage C1 is therefore TRUE-DEVICE PASS.

## 7. Stage C2 remains required

Even after a 10bpc final target is proven:

```text
10-bit encoded input     PASS
10bpc target             PASS
source texture precision UNKNOWN
```

Stage C2 must establish equivalent evidence for the producer/BufferQueue/GraphicBuffer/SurfaceTexture side. Absence of Java visibility is `UNKNOWN`, not 8-bit.

## 8. Escalation policy

```text
C0 no exact RGB10A2 config
-> do not activate C1

C0 exact RGB10A2 config
-> activate C1 only in next controlled build

C1 runtime still RGB888 / window failure
-> fail closed, record exact reason

C1 runtime RGB10A2
-> Stage C2 source-buffer forensics

source path proven 8-bit or impossible to preserve/prove
-> direct MediaCodec -> app-owned Surface evaluation
```

## 9. HDR and lifecycle boundaries

HDR remains OFF through v6.1.1. RGB10A2 is an SDR10 precision experiment, not HDR10 activation.

`Dropping frame - No surface` remains the separate fullscreen/Activity/Surface lifecycle backlog and is not changed during C0/C1.
