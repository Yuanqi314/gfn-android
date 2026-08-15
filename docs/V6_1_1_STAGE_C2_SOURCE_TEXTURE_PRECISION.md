# GFN Android v6.1.1 — Stage C2 Source Texture / Buffer Precision Forensics

## 1. Evidence entering C2

`53.log` closes Stage C1: the incoming stream is actual HEVC Main10 10/10-bit, and the final renderer EGLConfig is runtime `R10/G10/B10/A2` with FIRST_FRAME and stable near-60fps rendering in two renderer lifecycles.

This does not prove that the decoder producer buffer / SurfaceTexture / external OES texture preserves >8-bit precision.

## 2. C2.0 single-variable objective

Answer only:

> What concrete `VideoFrame.Buffer` reaches the existing app renderer for the live Main10 stream?

The implementation observes one frame per SDR10/RGB10A2 View and records:

```text
phase=SOURCE_FRAME
bufferClass
bufferType
size
rotation
timestampNs
texture=true|false
textureType=OES|RGB|NONE
textureId
glTarget
unscaled size
toI420Called=false
```

The frame object and buffer ownership are not changed.

## 3. Why `toI420()` is prohibited

Pinned M144 `VideoFrame.Buffer.toI420()` explicitly converts the underlying representation to memory-backed I420 when needed. `TextureBufferImpl.toI420()` invokes `YuvConverter.convert(this)` on its conversion handler.

Therefore an 8-bit I420 result would prove only that the conversion target is I420; it cannot prove that the source OES texture was already 8-bit.

## 4. Exact M144 decoder-to-OES closure

Pinned WebRTC commit:

```text
b1800a61db8320af5c14456c13622d8b85b1ed39
```

`AndroidVideoDecoder` uses texture mode when a shared EGL context exists:

```text
SurfaceTextureHelper.create("decoder-texture-thread", sharedContext)
Surface(surfaceTextureHelper.getSurfaceTexture())
MediaCodec.configure(format, surface, null, 0)
```

Decoded output is rendered into that Surface:

```text
codec.releaseOutputBuffer(index, render=true)
```

`SurfaceTextureHelper` then calls `updateTexImage()` and creates:

```text
TextureBufferImpl(
    textureWidth,
    textureHeight,
    TextureBuffer.Type.OES,
    oesTextureId,
    ...
)
```

This is strong source-code evidence for the internal M144 texture path. C2.0 still needs true-device observation at the app sink because the frame crosses native WebRTC before reaching `GfnVideoSurfaceView`.

## 5. Access boundary — no private decoder reflection

The exact M144 `AndroidVideoDecoder` class is package-private. Its live `SurfaceTextureHelper` and `Surface` fields are private. `createSurfaceTextureHelper()` is protected only on that package-private implementation.

The current project wrapper owns only the `VideoDecoder` interface delegate. No safe public API exposes the live decoder SurfaceTexture from that wrapper.

C2.0 therefore does not use reflection, hidden API access, or a fake `getSurfaceTexture()` assumption.

## 6. C2.1 native-window metadata — deferred one variable

A stronger follow-up can use JNI and `ANativeWindow_fromSurface()` to record native-window format/dataspace, but the current repository has no native module and the current build environment has no NDK available. Adding that bridge now would co-vary source forensics with a new native build system.

C2.1 is deferred until C2.0 true-device classification is known.

## 7. C2.0 verdicts

### OES

```text
texture=true
textureType=OES
isOes=true
```

Verdict:

```text
actual app sink path uses OES TextureBuffer
C2.0 PASS
precision still UNKNOWN
```

### RGB texture

```text
textureType=RGB
```

Verdict: revise the assumed SurfaceTexture/OES chain before any precision experiment.

### Non-texture buffer

```text
texture=false
```

Verdict: revise the chain and classify the actual buffer type. Do not continue OES-specific experiments.

### Unresolved

```text
phase=SOURCE_FRAME_UNRESOLVED
```

Verdict: instrumentation/API issue only; not 8-bit and not 10-bit PASS.

## 8. Next after C2.0 OES PASS

Keep C1 frozen and isolate C2.1/C2.2:

```text
C2.1 final/producer native-window format + dataspace witness
        ↓
C2.2 exact decoder SurfaceTexture observability closure
        ↓
C2.3 controlled 10-bit ramp + high-precision OES sampling/readback
```

Only a controlled numeric witness can close full source+shader precision. Natural-image histograms, screenshots, visual banding, or “more than 256 RGB values” are not accepted as 10-bit proof.

## 9. Full fidelity gate remains unchanged

```text
actual HEVC SPS 10-bit                  PASS
bound Main10 decoder                    PASS
actual frame path TextureBuffer/OES     PENDING
producer/buffer >8-bit metadata         PENDING
runtime final RGB10A2                   PASS
controlled numeric >8-bit preservation  PENDING
FIRST_FRAME / stable ~60fps              PASS
fallback=false                           PASS
HDR=false                                PASS
```

Until the remaining gates close:

```text
Full Main10 / SDR10 10-bit fidelity = NOT YET PASS
```
