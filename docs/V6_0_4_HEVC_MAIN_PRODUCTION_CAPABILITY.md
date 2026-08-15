# GFN Android v6.0.4 — HEVC Main Production Capability

## 0. Closeout — `45.log` TRUE-DEVICE PRODUCTION PASS

`45.log` satisfies the complete v6.0.4 production gate with the original GFN Tier1 Offer. The device advertised a real Main / High-Tier capability, WebRTC retained H265 without any Tier0 rewrite, the same bound hardware component decoded `video/hevc`, FIRST_FRAME arrived, and rendering remained approximately 60fps.

```text
HEVC Main / SDR8
Production PASS
```

Observed closeout chain:

```text
HEVC_DECODER_CANDIDATE profile=1 tier=1 maxLevel=186 hardware=true supports1080p60=true
HEVC_PRODUCTION_ADVERTISEMENT enabled=true profile=1 tier=1 level=186
original Offer profile-id=1 tier-flag=1 level-id=153
OFFER_HEVC_COMPATIBLE compatible=true
RAW_ANSWER H265 Main
FINAL_ANSWER H265 Main
fallback=false
c2.qti.hevc.decoder / video/hevc
FIRST_FRAME effective=Hevc
~60fps stable
```

No `OFFER_TIER_AB`/Tier0 rewrite was present. v6.0.4 codec behavior is frozen at closeout; Main10 work proceeds separately in v6.1.0.

## 1. Baseline

v6.0.3 true-device evidence (`44.log`) established the experimental HEVC Main / SDR8 chain:

```text
effective=Hevc
→ c2.qti.hevc.decoder
→ coded 1920x1088 / rendered 1920x1080
→ FIRST_FRAME effective=Hevc
→ sustained approximately 60fps decode/render
```

That result used the diagnostic `tier-flag=1 -> 0` Offer rewrite. v6.0.4 removes that rewrite and returns to the original GFN Main / High-Tier Offer.

## 2. Production invariant

```text
GFN original H265 Main / High Tier / explicit level
        ↓ no codec SDP rewrite
Android MediaCodec capability probe
        ↓
normalized Main / High / max level
        ↓
exact decoder component binding
        ↓
explicit WebRTC H265 capability advertisement
        ↓
profile + tier + tx-mode + level compatibility gate
        ↓
setCodecPreferences
        ↓
createAnswer
        ↓
HEVC or same-session H264 fallback
```

The transport-only connection-address correction remains; H265 `profile-id`, `tier-flag`, `level-id`, PT and RTX are not rewritten.

## 3. Android capability normalization

`GfnHevcProductionCapability.kt` introduces explicit normalized models:

- `GfnHevcProfile.Main`
- `GfnHevcTier.Main / High`
- `GfnHevcLevel` from Level 1 through Level 6.2, including SDP `level-id`
- `GfnHevcDecoderCapability`
- `GfnHevcStreamSupport`

Every Android `HEVCMainTierLevel*` / `HEVCHighTierLevel*` constant is mapped explicitly. Raw Android level integers are never used as a linear ordering; only normalized `rank` participates in level comparison.

## 4. Decoder selection

The production probe enumerates `MediaCodecList.ALL_CODECS` and requires:

```text
non-encoder
+ video/hevc
+ HEVCProfileMain
+ hardwareAccelerated=true
+ High Tier
+ maxLevel >= 5.1
+ 1920x1080@60 supported
```

No concrete Qualcomm/Exynos/MediaTek decoder name is hardcoded. The selected component name is obtained from `MediaCodecInfo.name`.

Before a Session is allowed to use HEVC, the same component is checked against the requested stream size/rate and bitrate range.

## 5. Advertisement and decoder binding

`GfnHevcAwareVideoDecoderFactory` keeps the upstream default factory for non-H265 codecs. For H265 it:

1. removes generic H265 from the exposed fallback capability list;
2. exposes H265 only if the production probe found a valid component;
3. advertises explicit SDP parameters:

```text
profile-id=1
tier-flag=1
level-id=<normalized decoder max>
```

4. creates H265 through a `HardwareVideoDecoderFactory` predicate restricted to the exact selected `MediaCodecInfo.name`.

Therefore:

```text
Advertised H265 capability
==
actual H265 decoder component allowed for creation
```

If the bound WebRTC hardware factory itself rejects that component, H265 is not advertised.

## 6. Offer compatibility gate

v6.0.4 accepts a remote H265 candidate only when all of the following hold:

```text
profile-id = 1
+ tier-flag = 1
+ tx-mode = SRST (explicit or default)
+ explicit recognized level-id
+ remote level <= local normalized max level
+ requested width/height/fps supported by bound decoder
+ requested max bitrate inside bound decoder bitrate range
```

Main10, Tier0, unsupported tx-mode and over-level candidates are rejected for the production HEVC Main path.

## 7. Codec preference planner

The pre-answer planner no longer treats generic local H265 as sufficient. It only puts a local H265 capability ahead of H264 when it is compatible with an actual remote Main / High / SRST candidate at a supported level.

Order remains:

```text
compatible H265 Main/High
→ H264
→ required auxiliary codecs (RTX / RED / ULPFEC / FLEXFEC)
```

Same-session H264 fallback remains intact.

## 8. Answer lineage

The v6.0.3 Offer/Answer payload lineage remains because libwebrtc may normalize Answer H265 fmtp and omit explicit profile/tier fields. The lineage is now anchored to the **original** GFN Offer, not a rewritten Tier0 Offer.

A HEVC Answer payload is accepted as Main-lineage only if its dynamic PT is also an H265 Main PT in the same original Offer.

## 9. New diagnostics

Unified Logcat tag remains `GfnHevcCompat`. Important v6.0.4 phases:

```text
HEVC_DECODER_CANDIDATE
HEVC_DECODER_PROBE_ERROR
HEVC_PRODUCTION_ADVERTISEMENT
LOCAL_DECODER
LOCAL_RECEIVER
OFFER_HEVC_COMPATIBLE
OFFER_HEVC_REJECT
PREFERENCE_PLAN
PREFERENCE_APPLY
RAW_ANSWER
ANSWER_HEVC_MAIN_LINEAGE
FINAL_ANSWER
DECISION
MEDIA
```

Expected production-success signals include:

```text
HEVC_PRODUCTION_ADVERTISEMENT enabled=true
  decoder=<dynamic component>
  profile=1 tier=1 level>=153

LOCAL_RECEIVER H265
  params include profile-id=1;tier-flag=1;level-id=<...>

OFFER_CODEC H265 Main
  profile=1 tier=1 level=153

OFFER_HEVC_COMPATIBLE compatible=true matched=[<dynamic PT>] streamSafe=true

PREFERENCE_PLAN compatibleHevcMain>=1
RAW_ANSWER hevc != []
FINAL_ANSWER hevc != []
DECISION effective=Hevc fallback=false
MEDIA stage=FIRST_FRAME effective=Hevc
```

## 10. Expected safe fallback

If the real Android decoder reports no Main / High-Tier / Level >= 5.1 capability, or rejects the requested size/rate/bitrate, v6.0.4 intentionally does **not** fake High Tier. HEVC is considered production-incompatible and the existing same-session H264 fallback is the expected behavior.

A prior experimental HEVC decode success does not override this production capability gate.

## 11. Production true-device PASS gate — CLOSED

`45.log` proved all of the originally defined gate:

```text
Requested codec = Hevc
ResolvedLaunchProfile = Hevc
Original Offer H265 Main profile=1 tier=1 level=153
No experimental tier rewrite
Local production decoder = Main / High / maxLevel>=5.1
Explicit WebRTC H265 advertisement = Main / High
Offer compatibility = true
setCodecPreferences = applied
RAW_ANSWER HEVC != empty
FINAL_ANSWER HEVC != empty
fallback = false
HEVC RTP
bound hardware HEVC decoder created
mime = video/hevc
FIRST_FRAME effective=Hevc
stable decode/render around 60fps
```

## 12. Closeout boundary / next scope

At v6.0.4 closeout, these were deliberately outside the Main/SDR8 change:

```text
Main10 / profile-id=2
10-bit
HDR10 / HDR metadata
AV1
```

The v6.0.4 PASS now unlocks Main10/SDR10 as a separate v6.1 line. HDR activation remains deferred to v6.2; AV1 remains outside the current milestone.

Separate backlog:

```text
Surface / EGL lifecycle
EglRenderer: Dropping frame - No surface
fullscreen/navigation SurfaceView recreation
```

These are not mixed into the v6.0.4 HEVC Main production capability change.
