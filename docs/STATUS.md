# Current v6.1.1 Main10 / SDR10 — Stage C2 source texture/buffer precision

## Closed milestones

```text
v6.0.4  HEVC Main / SDR8 production                         TRUE-DEVICE PASS (45.log)
v6.1.0  Main10 / SDR10 capability + negotiation             TRUE-DEVICE PASS (46.log)
v6.1.1  DecodeInfo JNI null hotfix                          TRUE-DEVICE PASS (50.log)
v6.1.1  Stage A actual HEVC SPS bit depth                   TRUE-DEVICE PASS (50.log)
v6.1.1  Stage B exact M144 default EGL target               TRUE-DEVICE PASS / PROVEN RGB888 (50.log)
v6.1.1  Stage C0 exact fixed RGB10A2 window capability       TRUE-DEVICE PASS (51.log)
v6.1.1  reconnect black-screen repair                        TRUE-DEVICE MANUAL PASS (operator test, 2026-08-15)
v6.1.1  Stage C1 custom RGB10A2 final EGL target             TRUE-DEVICE PASS (53.log)
```

The reconnect closeout is an operator-reported true-device result, not a new line-addressable reconnect log. All negotiation behavior remains frozen. HDR remains OFF.

## Stage C1 true-device closeout (`53.log`)

The observed chain was repeated across two renderer lifecycles:

```text
EGL10_PREFLIGHT: exact fixed R10/G10/B10/A2, configId=65
EGL_TARGET_REQUEST: requested=RGB10A2 active=RGB10A2 fallback=NONE
BITSTREAM_SPS: profileIdc=2, bitDepthLuma=10, bitDepthChroma=10
FIRST_FRAME: bound c2.qti.hevc.decoder / Main10
EGL_CONFIG: R10/G10/B10/A2, tenBitRgbTarget=true
EGL_TARGET_ACTIVE: exactRgb10A2=true
stable ~60fps
```

`surfaceChanged(... format=4 ...)` remains a non-blocking witness mismatch. It does not override the runtime EGLConfig evidence and C2.0 does not add `SurfaceHolder.setFormat()`.

## Current Stage C2.0 scope

Only observe the actual Java `VideoFrame.Buffer` delivered to the existing renderer:

```text
GfnVideoSurfaceView.onFrame(frame)
        ↓ read-only
buffer.javaClass
buffer.getBufferType()
width / height / rotation / timestamp
        ↓ if TextureBuffer
TextureBuffer.Type
textureId
glTarget
unscaledWidth / unscaledHeight
        ↓
original frame -> super.onFrame(frame)
```

Expected log:

```text
GfnHevc10Bit phase=SOURCE_FRAME
```

The probe is one-shot per SDR10/RGB10A2 View. It does not call `toI420()`, retain/release, crop/scale, GL readback, or any private decoder API.

## Exact pinned M144 source boundary

Pinned M144 (`b1800a61...`) establishes:

```text
AndroidVideoDecoder(sharedContext != null)
→ SurfaceTextureHelper.create(...)
→ Surface(surfaceTextureHelper.getSurfaceTexture())
→ MediaCodec.configure(..., surface, ...)
→ releaseOutputBuffer(index, render=true)
→ SurfaceTextureHelper.updateTexImage()
→ TextureBufferImpl(..., TextureBuffer.Type.OES, oesTextureId, ...)
```

However, `AndroidVideoDecoder` is package-private and its `surfaceTextureHelper` / `surface` fields are private. The app's current decoder wrapper only owns the public `VideoDecoder` delegate. Therefore C2 does not claim direct access to the decoder's internal `SurfaceTexture`.

## C2.1 native-window witness remains separately gated

The repository currently contains no native/JNI module or CMake/NDK build path, and the present local environment exposes no Android NDK. Introducing an ANativeWindow JNI bridge in C2.0 would therefore add an independent build-system variable. C2.1 remains a separate next experiment after true-device SOURCE_FRAME classification.

## Fidelity boundary

Current proven facts:

```text
10-bit encoded source                  PROVEN
Main10 hardware decoder                PROVEN
runtime RGB10A2 final EGL target       PROVEN
source Java frame type                 PENDING C2.0 TRUE-DEVICE
producer/native-window precision       UNKNOWN
numeric OES precision                  UNKNOWN
full 10-bit render fidelity            NOT YET PASS
```

`toI420()` must not be used as a bit-depth witness because it converts the underlying representation to 8-bit I420 by contract.

## Frozen variables

```text
CloudMatch / Session / SDP / Answer / NVST    FROZEN
Main10 decoder binding                        FROZEN
C1 RGB10A2 final EGL target                   FROZEN
SurfaceHolder.setFormat                       OFF
reconnect state machine                       FROZEN
fullscreen lifecycle                          FROZEN
HDR                                            OFF
direct MediaCodec -> app-owned Surface        OFF
```
