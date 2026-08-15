# v6.0.2 — HEVC Main Tier-Flag Single-Field A/B

## 目标

本版只完成一个真机因果实验：验证 GFN Offer 的 HEVC Main `tier-flag=1` 与 Android WebRTC generic H265 默认 Tier0 之间的 codec identity mismatch，是否就是 `createAnswer()` 排除 H265 的直接触发条件。

v6.0.1 真机链已经确认：

```text
Requested/Resolved = Hevc
GFN Offer H265 Main = profile-id=1;tier-flag=1;level-id=153
Local receiver H265 = generic params={}
setCodecPreferences = applied=true
RAW_ANSWER HEVC = empty
same-session fallback = H264
actual decoder = H264
```

因此 v6.0.2 不再调整 codec preference 顺序，也不碰 MediaCodec decoder factory。

## 唯一行为变量

仅在请求/选择 HEVC 时，对 **首个 video m-line** 动态解析出的 H265 Main 候选执行：

```text
profile-id=1
tier-flag=1
        ↓
profile-id=1
tier-flag=0
```

保持以下全部不变：

```text
level-id
profile-id
Main10/profile-id=2
H264
RTX / apt
RED / ULPFEC / FLEXFEC
m-line payload order
Audio
Resolution / FPS / bitrate
CloudMatch
NVST
Answer filter / H264 fallback
DefaultVideoDecoderFactory
```

Payload Type 不硬编码。目标 PT 来自当前 SDP 第一条 video m-line 内的 codec name + fmtp。

## 调用位置

```text
Original GFN Offer
        ↓
rewriteOfferConnectionAddresses
        ↓
HEVC Main tier-only A/B rewrite
        ↓
setRemoteDescription(rewritten Offer)
        ↓
setCodecPreferences
        ↓
createAnswer
        ↓
RAW_ANSWER
```

rewrite 必须发生在 `setRemoteDescription()` 之前；`createAnswer()` 后改 SDP 没有诊断价值。

## Logcat

统一 tag：

```text
GfnHevcCompat
```

新增阶段：

```text
OFFER_TIER_AB_REWRITE
OFFER_TIER_AB
OFFER_TIER_AB_CODEC
```

关键字段示例：

```text
phase=OFFER_TIER_AB_REWRITE applied=true from=1 to=0 candidates=[...] rewritten=[...]
phase=OFFER_TIER_AB_CODEC codec=H265 profile=1 tier=0 level=153 ...
```

只输出 codec/fmtp/PT/RTX 与选择结果，不输出 token、ICE password 或完整 SDP。

## 第一验收点

本版第一验收点不是“有画面”，而是 `RAW_ANSWER`：

```text
Before / v6.0.1:
RAW_ANSWER hevc=[] hevcMain=[]

After / v6.0.2 treatment:
RAW_ANSWER hevc=[...] hevcMain=[...]
```

若只有 tier 这一位变化就使 RAW_ANSWER 出现 H265 Main，则 tier mismatch 的 negotiation 因果闭环成立。

## 完整 HEVC 成功链

即使 RAW_ANSWER 出现 H265，也必须继续追踪：

```text
RAW_ANSWER HEVC Main != empty
FINAL_ANSWER HEVC Main != empty
effective=Hevc
fallback=false
FIRST_VIDEO_RTP effective=Hevc
actual HEVC decoder created
mime=video/hevc
FIRST_FRAME effective=Hevc
stable visible SDR8 video
```

如果后续 MediaCodec 失败，那是下一层独立问题，不否定 negotiation 层的 tier 因果结论。

## 明确边界：不能作为 production 修复

v6.0.2 的 rewrite 是取证实验。它把远端声明的 High Tier 改成 Main Tier，只用于验证 libwebrtc codec intersection 条件。

正式版本必须删除该 rewrite，并改为：

```text
MediaCodecList / CodecProfileLevel
        ↓
真实探测 HEVC Main + tier + level
        ↓
准确向 WebRTC 暴露本地 H265 capability
        ↓
保持原始 GFN tier-flag=1
        ↓
自然完成合法 codec intersection
```

Main10/HDR/AV1 继续冻结，直到 HEVC Main / SDR8 的 production capability advertisement、decode、render 全链通过。
