# Current v6.1.0 Main10 / SDR10 capability + negotiation candidate

## v6.0.4 closeout — TRUE-DEVICE PRODUCTION PASS

`45.log` closes the HEVC Main / SDR8 production milestone. The successful chain used the original GFN Tier1 Offer and the real Android decoder capability; the experimental Tier0 rewrite was not present.

```text
Android MediaCodec probe
Main / High Tier / Level 6.2
c2.qti.hevc.decoder
        ↓
explicit local H265 advertisement
profile-id=1 tier-flag=1 level-id=186
        ↓
original GFN Offer
profile-id=1 tier-flag=1 level-id=153
        ↓
OFFER_HEVC_COMPATIBLE=true
        ↓
RAW_ANSWER H265 Main
        ↓
FINAL_ANSWER H265 Main
        ↓
fallback=false
        ↓
HEVC RTP
        ↓
bound c2.qti.hevc.decoder / video/hevc
        ↓
FIRST_FRAME effective=Hevc
        ↓
~60fps stable render
```

Closeout verdict:

```text
HEVC Main / SDR8
Production PASS
```

v6.0.4 codec behavior is now frozen. Future changes must not reintroduce generic-H265 promotion or `tier-flag=1 -> 0` Offer rewriting.

## v6.1.0 current scope

v6.1.0 starts the first half of Main10 work:

```text
HEVC Main10 capability
+ SDR10 Session request
+ profile-id=2 WebRTC advertisement
+ original GFN Main10 Offer compatibility
+ RAW/FINAL Answer Main10 lineage
+ strict no-H264 fallback
```

It does **not** claim 10-bit output/render fidelity. That is the v6.1.1 gate.

### Frozen Session intent

The next-Session settings snapshot now distinguishes:

```text
H264 + CompatibilitySdr   -> SDR8
HEVC + CompatibilitySdr   -> HEVC Main / SDR8
HEVC + PreferSdr10        -> HEVC Main10 / SDR10
```

`HEVC + PreferSdr10` is resolved once into `ResolvedLaunchProfile` and reused by CREATE / persistence / CLAIM / WebRTC / reconnect. H264 cannot retain `PreferSdr10`; normalization returns it to `CompatibilitySdr`.

### Main10 capability probe

Main and Main10 are independently probed from Android `MediaCodecInfo.CodecCapabilities.profileLevels`.

```text
Main   -> HEVCProfileMain
Main10 -> HEVCProfileMain10
```

A Main capability never implies Main10. HDR-only profile constants are not used as substitutes for Main10.

For either profile, production advertisement requires:

```text
hardware decoder
+ High Tier
+ normalized level >= 5.1
+ 1920x1080@60 support
+ usable VideoCapabilities
```

`videoCapabilities == null` remains fail-closed.

### WebRTC advertisement and component binding

`GfnHevcAwareVideoDecoderFactory` can now expose two independent H265 entries when the device really supports them:

```text
Main:
profile-id=1;tier-flag=1;level-id=<real max>

Main10:
profile-id=2;tier-flag=1;level-id=<real max>
```

Each profile is bound to the exact MediaCodec component that proved the capability. No decoder name is hardcoded.

### Main10 negotiation gate

For `PreferSdr10`, the exact target is profile 2:

```text
Remote candidate
profile-id=2
+ required tier
+ SRST tx-mode
+ recognized level
        ↓
Local bound Main10 capability
same profile / tier
remote level <= local max
size/rate safe
bitrate safe
```

Main PT and Main10 PT are never interchangeable. Dynamic Answer lineage is resolved against the exact profile from the original Offer.

### Strict no-H264 fallback

Main/SDR8 retains the v6.0.4 proven same-Session H264 fallback.

Main10/SDR10 does not:

```text
requested=HEVC Main10 / SDR10
+ Main10 negotiation fails
        ↓
Session error
```

It must not silently become H264/SDR8, because that would turn a Main10 test into a false success.

## CloudMatch / NVST v6.1.0

For a fresh SDR10 Session:

```text
requestedStreamingFeatures.bitDepth = 1
sdrHdrMode = 0
clientDisplayHdrCapabilities = null
chromaFormat = 1
```

NVST is generated from the same frozen snapshot:

```text
SDR8  -> bitDepth=8
SDR10 -> bitDepth=10
HDR    -> OFF
```

Reconnect/RESUME remains minimal and does not renegotiate the Session color request.

## HDR boundary

HDR activation remains disabled in v6.1.0:

```text
PreferHdr10 = rejected
HDR Session request = OFF
HDR display capability activation = OFF
HDR metadata activation = OFF
```

Read-only HDR diagnostics may be added later, but HDR10 is a separate v6.2 milestone.

## v6.1.0 true-device gate

A true-device v6.1.0 negotiation PASS requires at least:

```text
ResolvedLaunchProfile codec=Hevc color=PreferSdr10
CloudMatch bitDepth=1 sdrHdrMode=0 hdr=false
HEVC Main10 production advertisement enabled=true
LOCAL_RECEIVER H265 profile-id=2
original GFN Main10 Offer unchanged
OFFER_HEVC_COMPATIBLE targetProfile=2 compatible=true
setCodecPreferences applied
RAW_ANSWER Main10 != empty
FINAL_ANSWER Main10 != empty
fallback=false
NVST bitDepth=10 hdr=false
HEVC RTP
bound H265 decoder created
```

`FIRST_FRAME` is useful evidence that media is flowing, but **does not by itself prove that libwebrtc preserved a 10-bit output path**.

## v6.1.1 next gate

Only after v6.1.0 negotiation is true-device proven do we close the second half:

```text
actual Main10 decoder
10-bit decode evidence
10-bit output evidence
10-bit Surface/render evidence
stable frames
```

If the existing libwebrtc texture/render path cannot prove 10-bit preservation, `direct MediaCodec -> Surface` becomes a v6.1.1 implementation candidate rather than being mixed into v6.1.0.

## Independent backlog

`EglRenderer: Dropping frame - No surface` remains a Surface/EGL lifecycle issue independent from codec negotiation. It does not block Main10 capability/SDP forensics, but may intersect the later 10-bit rendering work.
