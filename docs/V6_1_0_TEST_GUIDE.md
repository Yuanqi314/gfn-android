# GFN Android v6.1.0 — Main10 / SDR10 True-Device Test Guide

## Goal

This test decides only whether **HEVC Main10 / SDR10 capability + negotiation** is correct. It does not declare 10-bit render fidelity or HDR10 success.

## Settings

Use a fresh Session with:

```text
Resolution: 1920x1080
FPS: 60
Codec: HEVC
HEVC profile/color: SDR10 / Main10
HDR: OFF
```

Do not reuse a v6.0.4 Session created with SDR8. The active profile is frozen for the Session lifetime.

## Required log chain

### 1. Frozen launch profile

Expect the resolved/create diagnostics to show:

```text
codec=Hevc
color=PreferSdr10
cloudMatchBitDepth=1
sdrHdrMode=0
hdr=false
```

A log showing CompatibilitySdr means the test is not a Main10/SDR10 run.

### 2. Main10 decoder probe

Look for a `HEVC_DECODER_CANDIDATE` with:

```text
profile=2
hardware=true
tier=1
maxLevel>=153
supports1080p60=true
```

The component name is device-specific and must not be assumed.

### 3. Main10 production advertisement

Expect:

```text
phase=HEVC_MAIN10_ADVERTISEMENT
enabled=true
profile=2
tier=1
level>=153
```

If it is disabled, capture the probe candidates/errors. That is a capability result; do not re-enable generic H265 or fake Main10.

### 4. Local WebRTC capability

Expect H265 with explicit profile 2 parameters in local decoder/receiver diagnostics:

```text
profile-id=2
tier-flag=1
level-id=<real local max>
```

Main profile 1 may also be advertised independently.

### 5. Original GFN Offer

The target remote H265 candidate must remain unchanged:

```text
profile-id=2
tier-flag=1
level-id=153
```

There must be no Tier0 rewrite phase.

### 6. Compatibility

Expect:

```text
phase=OFFER_HEVC_COMPATIBLE
targetProfile=2
compatible=true
matched=[<dynamic PT>]
streamSafe=true
```

Do not assume PT107 on another Session; use the dynamic log value.

### 7. Codec preference

Expect a Main10-compatible H265 capability to be placed before H264 by the pre-answer planner.

```text
PREFERENCE_APPLY applied=true
```

### 8. RAW_ANSWER

Expect:

```text
hevcMain10 != []
ANSWER_HEVC_MAIN10_LINEAGE stage=RAW_ANSWER matched != []
DECISION effective=Hevc fallback=false
```

If Main10 is missing, the Session should fail rather than silently accept H264.

### 9. FINAL_ANSWER

Expect:

```text
hevcMain10 != []
ANSWER_HEVC_MAIN10_LINEAGE stage=FINAL_ANSWER matched != []
DECISION effective=Hevc fallback=false
```

### 10. NVST

Expect:

```text
phase=NVST_CONFIG
color=PreferSdr10
bitDepth=10
hdr=false
```

This is required to prove the same Session snapshot reached NVST.

### 11. Media

Record:

```text
FIRST_VIDEO_RTP effective=Hevc
actual H265 decoder component
video/hevc
FIRST_FRAME effective=Hevc
```

These are necessary media milestones, but **FIRST_FRAME is not sufficient 10-bit output proof**.

## PASS for v6.1.0

All of these must hold:

```text
PreferSdr10 frozen profile
CloudMatch bitDepth=1 / HDR off
real Main10 capability
explicit profile-id=2 local advertisement
original GFN Main10 Offer unchanged
compatibility=true
RAW_ANSWER Main10 lineage
FINAL_ANSWER Main10 lineage
fallback=false
NVST bitDepth=10 / hdr=false
HEVC RTP / decoder starts
```

Then mark:

```text
v6.1.0 Main10 capability + negotiation = TRUE-DEVICE PASS
```

Do **not** mark:

```text
10-bit render PASS
HDR10 PASS
```

Those require later evidence.

## Failure classification

```text
No Main10 production advertisement
-> Android capability/probe path

Advertisement exists but Offer compatibility=false
-> profile/tier/tx-mode/level/workload matcher

Compatibility=true but RAW_ANSWER lacks Main10
-> libwebrtc capability/intersection layer

RAW_ANSWER Main10 exists but FINAL_ANSWER/decision fails
-> local Answer lineage/filter policy

Final Main10 succeeds but no RTP/decoder
-> downstream media/transport/decoder layer

FIRST_FRAME succeeds but bit depth is unknown
-> not a v6.1.0 failure; proceed to v6.1.1 10-bit output forensics
```
