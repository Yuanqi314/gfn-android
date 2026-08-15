# GFN Android v6.1.1 — Main10 / SDR10 10-bit Forensics

## 1. Purpose

v6.1.0 is already TRUE-DEVICE PASS for Main10 capability, SDR10 Session request, profile-id=2 negotiation, bound H265 decoder creation and decode-to-frame. v6.1.1 does not reopen any of those variables.

The unanswered question is narrower:

> Are the actual HEVC samples 10-bit, and where—if anywhere—does the current Android WebRTC render path reduce them to 8-bit?

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

No v6.1.1 diagnostic is allowed to mutate the above chain.

## 3. Stage A — `GfnHevcBitstreamProbe`

The probe decorates the selected H265 `VideoDecoder`. `decode(frame, info)` performs bounded synchronous inspection of `frame.buffer` and then invokes `delegate.decode(frame, info)` with the same object.

### Byte ownership invariant

```text
caller EncodedImage object      unchanged
caller ByteBuffer position      unchanged
caller ByteBuffer limit         unchanged
encoded bytes                   unchanged
retain/release ownership        unchanged
```

The parser uses a duplicate/slice view only.

### Packaging recognition

Detection order:

```text
1. Annex-B start codes (3 or 4 bytes)
2. complete length-prefixed framing, length width 4/3/2/1
3. single NAL fallback
4. Unknown
```

A framing candidate is accepted only when the whole buffer is structurally consumed; a coincidental prefix must not be treated as valid length framing.

### SPS fields

For NAL type 33:

```text
NAL payload
-> EBSP to RBSP
-> sps_video_parameter_set_id
-> sps_max_sub_layers_minus1
-> profile_tier_level
-> sps_seq_parameter_set_id
-> chroma_format_idc
-> width / height / conformance window
-> bit_depth_luma_minus8
-> bit_depth_chroma_minus8
```

Reported bit depth:

```text
bitDepthLuma   = bit_depth_luma_minus8 + 8
bitDepthChroma = bit_depth_chroma_minus8 + 8
```

Main10 evidence requires both to equal 10. `profileIdc=2` alone remains insufficient.

## 4. Stage B — pinned M144 EGL closure

The current view still uses the existing two-argument WebRTC initializer. No custom config attributes are supplied.

Exact pinned source closure:

```text
SurfaceViewRenderer.init(sharedContext, rendererEvents)
-> init(..., EglBase.CONFIG_PLAIN, new GlRectDrawer())

EglBase.CONFIG_PLAIN
-> ConfigBuilder.createConfigAttributes()
-> RED=8, GREEN=8, BLUE=8
```

`GfnEglConfigProbe` then reads, but does not change:

```text
current EGLDisplay
current EGLContext
current draw EGLSurface
EGL_CONFIG_ID
EGL_RED_SIZE
EGL_GREEN_SIZE
EGL_BLUE_SIZE
EGL_ALPHA_SIZE
EGL_RENDERABLE_TYPE
EGL_SURFACE_TYPE
```

The query is dispatched through a zero-scale one-shot `SurfaceViewRenderer.addFrameListener`; in the pinned WebRTC renderer, frame-listener callbacks run on the render thread. A zero-size callback avoids pixel readback and exists only to gain render-thread/current-EGL timing.

## 5. Current verdict semantics

`BITSTREAM_SPS tenBit=true` proves the actual observed SPS requests 10-bit luma/chroma coding.

`EGL_CONFIG red=8 green=8 blue=8` proves the current final EGL config is 8bpc at the renderer target.

Neither statement alone proves where an earlier texture/buffer conversion occurred. Full v6.1.1 PASS still requires downstream preservation evidence or a render path whose precision is independently established.

## 6. Escalation policy

Do not introduce a renderer rewrite without evidence.

```text
SPS10 + EGL8
-> custom 10-bit EGL/Surface candidate

SPS10 + EGL10+
-> inspect source texture/GraphicBuffer path before changing renderer

SPS8
-> server/session investigation, renderer unchanged

SPS unresolved
-> framing/assembly/parser investigation
```

Direct `MediaCodec -> Surface` is a later fallback only if the WebRTC texture path is proven unable to preserve/expose the required precision.

## 7. HDR boundary

v6.1.1 remains SDR10 only:

```text
PreferHdr10 OFF
HDR Session OFF
HDR metadata activation OFF
HDR display activation OFF
```

HDR10 remains v6.2.
