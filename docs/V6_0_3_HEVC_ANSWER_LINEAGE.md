# v6.0.3 - HEVC Main Answer Lineage Continuation

## Scope

v6.0.3 is still a diagnostic continuation of the v6.0.2 tier-only A/B. It does not implement the production MediaCodec capability advertisement fix yet.

The only new behavior is Answer classification after libwebrtc createAnswer().

## True-device evidence from 43.log

The v6.0.2 rewrite executed before setRemoteDescription:

```text
OFFER_TIER_AB_REWRITE applied=true from=1 to=0 candidates=[103] rewritten=[103]
OFFER_TIER_AB_CODEC pt=103 codec=H265 profile=1 tier=0 level=153
```

Unlike v6.0.1, createAnswer() now retained H265:

```text
RAW_ANSWER h264=[101] hevc=[103] hevcMain=[]
RAW_ANSWER_CODEC pt=103 codec=H265 profile=- tier=- level=93 fmtp="level-id=93"
```

This completes the true-device causal A/B for the original negotiation failure:

```text
Tier1 original Offer -> no H265 in RAW_ANSWER
Tier0 single-field rewrite -> H265 appears in RAW_ANSWER
```

The remaining H264 fallback was caused by gfn-android itself. The old Answer policy required an explicit `profile-id=1` in the generated Answer. libwebrtc omitted profile-id and tier-flag, so `hevcMain=[]` even though H265 PT 103 had already survived createAnswer(). The policy then selected H264 and the Answer filter removed H265.

## v6.0.3 rule

Do not accept arbitrary generic H265.

Instead:

1. Parse the first video m-line of the rewritten Offer.
2. Find H265 candidates with explicit `profile-id=1`.
3. Parse H265 payloads in the generated Answer.
4. Intersect them by payload type only inside this same Offer/Answer exchange.
5. Treat only that intersection as `HEVC Main matched`.
6. Filter the Answer to those matched H265 payloads plus their RTX and repair payloads.

Payload type equality is not treated as a globally stable codec identity. It is used only as session-local Offer/Answer lineage after both sides have independently identified the payload as H265.

This prevents the Main10 Offer payload from being accepted as Main.

## Expected v6.0.3 log chain

```text
OFFER_TIER_AB_REWRITE ... rewritten=[<dynamic-main-pt>]
RAW_ANSWER ... hevc=[<same-dynamic-main-pt>]
ANSWER_HEVC_MAIN_LINEAGE stage=RAW_ANSWER ... matched=[<same-dynamic-main-pt>]
DECISION stage=RAW_ANSWER requested=Hevc effective=Hevc fallback=false
FINAL_ANSWER ... hevc=[<same-dynamic-main-pt>]
ANSWER_HEVC_MAIN_LINEAGE stage=FINAL_ANSWER ... matched=[<same-dynamic-main-pt>]
DECISION stage=FINAL_ANSWER requested=Hevc effective=Hevc fallback=false
FIRST_VIDEO_RTP effective=Hevc
```

The next decisive evidence is then the actual decoder path:

```text
AndroidVideoDecoder ... type: H265
MediaCodec mime = video/hevc
FIRST_FRAME effective=Hevc
```

If negotiation stays HEVC but the HEVC decoder fails, the investigation moves to MediaCodec capability/bitstream compatibility. That would not invalidate the tier mismatch causal proof.

## Production boundary

v6.0.3 still rewrites the remote tier flag for diagnostic purposes. It is not the production fix.

After HEVC Main is negotiated, decoded, and rendered on the test device, the experimental rewrite must be removed and replaced with the production path from the original plan:

```text
MediaCodec profileLevels probe
-> exact local HEVC capability model
-> decoder-component-bound capability advertisement
-> original GFN High Tier Offer unchanged
```

Main10/HDR remain frozen.
