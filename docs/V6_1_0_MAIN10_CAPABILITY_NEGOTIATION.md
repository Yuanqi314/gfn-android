# GFN Android v6.1.0 — HEVC Main10 / SDR10 Capability & Negotiation

## 1. Scope

v6.0.4 is closed as **HEVC Main / SDR8 Production PASS** from `45.log`. v6.1.0 starts a separate Main10 line without changing the proven Main path.

This version targets only:

```text
HEVC Main10 capability
+ SDR10 Session request
+ profile-id=2 WebRTC advertisement
+ original GFN Main10 Offer compatibility
+ RAW/FINAL Answer Main10 lineage
+ strict no-H264 fallback
```

Not claimed in v6.1.0:

```text
10-bit renderer fidelity
HDR10 Session activation
HDR metadata activation
HDR display/output path
```

10-bit decode/output/render proof is v6.1.1. HDR activation is v6.2.

## 2. Session intent

The user-facing HEVC family is split by `RequestedColorMode` rather than by inventing a second codec enum:

```text
H264 + CompatibilitySdr -> H264 / SDR8
HEVC + CompatibilitySdr -> HEVC Main / SDR8
HEVC + PreferSdr10      -> HEVC Main10 / SDR10
```

`PersistentStreamSettings` stores both codec and color mode. A new Session resolves them once into `ResolvedLaunchProfile`; the active Session never re-reads mutable settings.

H264 + PreferSdr10 is normalized back to CompatibilitySdr because this milestone does not define a 10-bit H264 path.

## 3. Android Main10 capability probe

Main and Main10 are independent probe targets:

```text
Main   -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
Main10 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
```

The probe must not infer Main10 from a successful Main capability.

For each exact profile, the normalized production candidate requires:

```text
hardwareAccelerated=true
High Tier
maxLevel >= 5.1
1920x1080@60 supported
VideoCapabilities != null
```

`VideoCapabilities?` remains nullable and fail-closed. Android HEVC level constants are converted through the explicit normalized level map before ordering or SDP comparison.

HDR-only profile constants are intentionally not accepted as substitutes for the plain Main10 capability in this milestone.

## 4. Profile-specific decoder binding

`GfnHevcAwareVideoDecoderFactory` keeps independent Main and Main10 production capabilities.

When available, local H265 advertisement becomes:

```text
Main:
profile-id=1
tier-flag=1
level-id=<real Main max>

Main10:
profile-id=2
tier-flag=1
level-id=<real Main10 max>
```

For H265 decoder creation, the requested SDP profile chooses the factory bound to the exact MediaCodec component that proved that profile.

Invariant:

```text
advertised profile capability
==
actual decoder component allowed for that profile
```

There is no hardcoded Qualcomm/Exynos/MediaTek component name.

If libwebrtc asks Java for a generic H265 decoder without returning `profile-id`, the factory does not silently default to Main when Main and Main10 were proven by different components. Generic creation is accepted only when exactly one HEVC production capability exists or both profiles bind to the same MediaCodec component; otherwise it fails closed.

## 5. Original GFN Offer

The GFN Offer already contains separate H265 candidates, observed previously as:

```text
Main10 candidate:
profile-id=2
tier-flag=1
level-id=153

Main candidate:
profile-id=1
tier-flag=1
level-id=153
```

v6.1.0 does not rewrite H265 codec fmtp. Payload types are parsed dynamically and are not hardcoded.

## 6. Compatibility matcher

The requested Session determines the exact target profile.

For Main10/SDR10:

```text
remote profile-id=2
+ remote tier compatible
+ tx-mode compatible (SRST default/explicit)
+ recognized remote level
+ local profile=Main10
+ local tier compatible
+ remote level <= local maxLevel
+ requested size/rate supported
+ requested bitrate supported
```

Main and Main10 candidate lists are never merged into one generic H265 success condition.

## 7. Answer lineage

libwebrtc may normalize H265 Answer parameters. Therefore classification is not based solely on the final fmtp text.

The lineage rule is profile-specific:

```text
original Offer H265 PT for target profile
        intersect
RAW/FINAL Answer H265 PT
        ↓
matched target-profile PT
```

Helpers exist for both profile 1 and profile 2. A Main PT cannot satisfy Main10 lineage and vice versa.

## 8. Strict Main10 fallback policy

v6.0.4 Main/SDR8 retains the proven same-Session H264 fallback.

For Main10/SDR10:

```text
Main10 unavailable / incompatible
or
createAnswer drops Main10
        ↓
Session failure
```

No H264 fallback is allowed in this mode. Otherwise a 10-bit test could silently become an SDR8 stream and be misreported as Main10 success.

## 9. CloudMatch SDR10 request

A fresh `PreferSdr10` Session changes only the bit-depth intent required for SDR10:

```text
requestedStreamingFeatures.bitDepth = 1
sdrHdrMode = 0
clientDisplayHdrCapabilities = null
chromaFormat = 1
```

HDR stays disabled. Existing RESUME behavior remains minimal and does not renegotiate streaming parameters.

## 10. NVST consistency

NVST uses the same immutable Session snapshot:

```text
CompatibilitySdr -> bitDepth=8
PreferSdr10      -> bitDepth=10
```

Diagnostic phase:

```text
GfnHevcCompat phase=NVST_CONFIG color=<...> bitDepth=<8|10> hdr=false
```

The purpose is to prove the CloudMatch/WebRTC/NVST layers are all following the same resolved Session intent.

## 11. HDR boundary

v6.1.0 must keep:

```text
PreferHdr10 rejected
HDR Session request OFF
HDR display capability activation OFF
HDR metadata activation OFF
```

MediaCodec output fields such as `hdr-static-info` are not interpreted as proof that an HDR Session is active.

## 12. What v6.1.0 can prove

A successful true-device run can prove:

```text
Main10 decoder capability exists
Main10 is explicitly advertised to WebRTC
GFN original Main10 Offer intersects locally
createAnswer retains Main10
final Answer retains Main10
fallback=false
CloudMatch requested SDR10
NVST requested bitDepth=10
HEVC media starts
```

It cannot, by itself, prove that libwebrtc's texture/render path preserved 10-bit samples all the way to the display.

## 13. v6.1.1 handoff

After v6.1.0 succeeds, v6.1.1 must establish actual 10-bit evidence at decoder output/render boundaries.

If the current `AndroidVideoDecoder -> SurfaceTexture/EGL -> SurfaceViewRenderer` path cannot expose sufficient bit-depth evidence or preserves only 8-bit output, the direct `MediaCodec -> Surface` path should be evaluated there, not preemptively mixed into v6.1.0.

## 14. Closeout — `46.log` TRUE-DEVICE PASS

The v6.1.0 gate is now closed by true-device evidence:

```text
PreferSdr10 frozen Session
CloudMatch bitDepth=1 / sdrHdrMode=0 / hdr=false
real Main10 / High / Level 6.2 hardware capability
explicit profile-id=2 local advertisement
original GFN Main10 Offer unchanged
compatibility targetProfile=2 true
RAW_ANSWER Main10 lineage
FINAL_ANSWER Main10 lineage
fallback=false
NVST bitDepth=10 / hdr=false
HEVC RTP
bound c2.qti.hevc.decoder
FIRST_FRAME
~60fps stable
```

Verdict:

```text
v6.1.0 Main10 / SDR10
Capability + Session request + Negotiation + Decode-to-frame
TRUE-DEVICE PASS
```

10-bit sample/output/render fidelity remains outside this closeout and is handled by v6.1.1.
