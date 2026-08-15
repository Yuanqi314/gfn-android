# Current v6.1.1 Main10 / SDR10 10-bit forensics

## v6.0.4 closeout — TRUE-DEVICE PRODUCTION PASS

`45.log` closes HEVC Main / SDR8 production. The successful path used the original GFN Tier1 Offer, explicit real Android Main/High capability, exact decoder-component binding, RAW/FINAL H265, `fallback=false`, `video/hevc`, FIRST_FRAME and sustained ~60fps. The experimental Tier0 Offer rewrite is not part of production.

```text
HEVC Main / SDR8
Production PASS
```

v6.0.4 codec behavior is frozen.

## v6.1.0 closeout — TRUE-DEVICE PASS

`46.log` closes the Main10 capability/session/negotiation milestone:

```text
ResolvedLaunchProfile codec=Hevc color=PreferSdr10
        ↓
CloudMatch bitDepth=1 / sdrHdrMode=0 / hdr=false
        ↓
real c2.qti.hevc.decoder Main10 / High / Level 6.2 capability
        ↓
explicit local H265 profile-id=2 advertisement
        ↓
original GFN Main10 Offer profile-id=2 tier-flag=1 level-id=153
        ↓
targetProfile=2 compatibility=true
        ↓
RAW_ANSWER Main10 lineage
        ↓
FINAL_ANSWER Main10 lineage
        ↓
fallback=false
        ↓
NVST bitDepth=10 / hdr=false
        ↓
HEVC RTP / bound H265 decoder / FIRST_FRAME / ~60fps
```

Verdict:

```text
v6.1.0 Main10 / SDR10
Capability + Session request + Negotiation + Decode-to-frame
TRUE-DEVICE PASS
```

This does not prove 10-bit sample preservation through decoder output / texture / EGL / display.

## v6.1.1 current scope — read-only fidelity forensics

All negotiation behavior proven by v6.1.0 is frozen:

```text
CloudMatch                FREEZE
ResolvedLaunchProfile     FREEZE
Main10 capability probe   FREEZE
SDP advertisement         FREEZE
Offer compatibility       FREEZE
setCodecPreferences       FREEZE
RAW/FINAL Answer policy   FREEZE
NVST bitDepth=10          FREEZE
decoder component binding FREEZE
```

v6.1.1 adds only evidence collection around the media/render path.

### Stage A — actual HEVC SPS bit depth

`GfnHevcBitstreamProbeVideoDecoder` decorates the already selected Java H265 decoder. It inspects the synchronous `EncodedImage.buffer`, then forwards the exact same `EncodedImage` object to the delegate decoder.

The parser supports:

```text
Annex-B 00 00 01
Annex-B 00 00 00 01
length-prefixed NAL (1/2/3/4-byte lengths)
single NAL fallback
```

It searches SPS NAL type 33, removes emulation-prevention bytes, and parses the HEVC SPS prefix required for:

```text
general_profile_idc
general_tier_flag
general_level_idc
chroma_format_idc
coded/display width + height
bit_depth_luma_minus8
bit_depth_chroma_minus8
```

Expected Main10 evidence:

```text
GfnHevc10Bit phase=BITSTREAM_SPS
profileIdc=2
tier=1
bitDepthLuma=10
bitDepthChroma=10
tenBit=true
```

The scan is bounded. `BITSTREAM_SPS_UNRESOLVED` means the observer did not find/parse SPS within its budget; it is not evidence that the stream is 8-bit.

### Stage B — exact pinned WebRTC EGL target

The project remains pinned to `io.github.webrtc-sdk:android:144.7559.09`, mapped to WebRTC source commit `b1800a61db8320af5c14456c13622d8b85b1ed39` for source closure.

At that exact source, the two-argument `SurfaceViewRenderer.init(sharedContext, rendererEvents)` delegates to:

```text
EglBase.CONFIG_PLAIN
```

and `CONFIG_PLAIN` is built with:

```text
EGL_RED_SIZE   = 8
EGL_GREEN_SIZE = 8
EGL_BLUE_SIZE  = 8
```

v6.1.1 does not alter those attributes. `GfnEglConfigProbe` performs a one-shot read-only EGL14 query on the existing render thread and logs the current config ID plus R/G/B/A channel sizes.

Expected diagnostics:

```text
GfnHevc10Bit phase=EGL_REQUEST source=WebRTC_M144_CONFIG_PLAIN red=8 green=8 blue=8 ...
GfnHevc10Bit phase=EGL_CONFIG success=true configId=... red=... green=... blue=... alpha=... tenBitRgbTarget=...
```

The static request is known to be RGB888. The actual runtime selected config remains a true-device fact and is not assumed before the log is observed.

## v6.1.1 decision tree

```text
SPS = 8-bit
-> Main10 negotiation PASS, actual stream is not proven 10-bit
-> inspect Session/server behavior; do not rewrite renderer

SPS = 10-bit + EGL = 8/8/8
-> incoming elementary stream is 10-bit
-> default final EGL target is 8bpc
-> next candidate: custom 10-bit EGL/Surface target

SPS = 10-bit + EGL >= 10bpc
-> do not rewrite renderer yet
-> continue source texture / buffer preservation proof

SPS unresolved
-> inspect packaging/frame assembly/parser coverage
-> no bit-depth verdict
```

## Explicit non-goals in current v6.1.1 build

```text
RGB10A2 renderer activation      OFF
P010 output request              OFF
custom HardwareBuffer path       OFF
direct MediaCodec -> Surface     OFF
HDR Session request              OFF
HDR metadata activation          OFF
HDR display/output activation    OFF
```

`hdr-static-info=<buffer>` remains diagnostic data only and is not HDR PASS evidence.

## PASS boundary

Current Stage A/B implementation can prove actual encoded SPS bit depth and the selected EGL config. It must **not** be labeled full v6.1.1 10-bit render PASS until the downstream texture/buffer/output path also has evidence strong enough to exclude known 10→8 conversion.

## Independent backlog

`EglRenderer: Dropping frame - No surface` remains a Surface lifecycle/fullscreen/recreation/sink-binding issue. It is independent from Main10 negotiation and from the Stage A SPS verdict, although it may intersect future renderer work.
