# v6.0.1 — HEVC Main Negotiation Compatibility

## 已知真机基线

v6.0 真机结果：`ResolvedLaunchProfile.codec=Hevc` 从 CREATE 到 CLAIM/WebRTC 保持不变，本机 `DefaultVideoDecoderFactory` 声明 H265，但最终 `Negotiated codec=H264`，fallback reason 为 `libwebrtc createAnswer 未接受 HEVC Main；同 Session 回退 H264`。因此 v6.0 只证明 H264 fallback 工作，未证明 HEVC Main negotiated/decoded/rendered。

## 本版唯一行为变量

v6.0.1 不启用 Main10/HDR/AV1，也不 rewrite `tier-flag` / `level-id`。本版唯一新的协商行为是：

```text
GFN Offer
  -> setRemoteDescription
  -> 读取 PeerConnectionFactory VIDEO receiver capabilities
  -> H265 profile-id=1
     -> generic H265
     -> H264 fallback
     -> RTX/RED/ULPFEC/FLEXFEC auxiliary
  -> RtpTransceiver.setCodecPreferences(...)
  -> createAnswer
  -> 保存/分析 raw Answer
  -> 既有 final Answer filter + H264 fallback
```

如果 `setCodecPreferences` 查询、transceiver 定位或 API 调用失败，本版不会因此中止 Session；错误写入 diagnostics/Logcat 后继续 `createAnswer`，让既有 H264 fallback 保持可用。

## Logcat 取证

统一 tag：

```text
GfnHevcCompat
```

阶段：

```text
SESSION
LOCAL_DECODER
LOCAL_RECEIVER
OFFER / OFFER_CODEC
DECISION stage=OFFER
PREFERENCE_PLAN
PREFERENCE_ITEM
PREFERENCE_APPLY
RAW_ANSWER / RAW_ANSWER_CODEC
DECISION stage=RAW_ANSWER
FINAL_ANSWER / FINAL_ANSWER_CODEC
DECISION stage=FINAL_ANSWER
MEDIA stage=FIRST_VIDEO_RTP
MEDIA stage=FIRST_FRAME
```

只打印 codec/fmtp/PT/RTX 与选择结果，不打印 ICE password、鉴权 token 或完整 SDP。

推荐过滤：

```text
adb logcat -v time GfnHevcCompat:I '*:S'
```

动态 payload type 仅用于当前 SDP 内取证。Offer PT 与本地 receiver capability 的 `preferredPayloadType` **不以数值相等作为兼容条件**。

## 记录的 H265 证据

Offer / Raw Answer / Final Answer 的第一条 video m-line 逐 PT 记录：

```text
PT
codec name
clock rate
profile-id
tier-flag
level-id
tx-mode
完整 fmtp
linked RTX PT / apt
```

本地同时记录：

```text
DefaultVideoDecoderFactory.supportedCodecs: name + params
PeerConnectionFactory.getRtpReceiverCapabilities(VIDEO):
  preferredPayloadType
  name / mimeType
  clockRate
  parameters
```

## 成功边界

HEVC Main / SDR8 只有以下链同时成立才算 true-device PASS：

```text
Requested=Hevc
Offer explicit H265 profile-id=1
Local receiver contains compatible H265 capability
PREFERENCE_APPLY applied=true
Raw Answer contains H265 profile-id=1
Final Answer contains H265 profile-id=1
Negotiated=Hevc
Fallback=false
First video RTP=true
First frame=true
Stable visible video
Color=SDR8
```

若仍为 H264，则保留所有 `GfnHevcCompat` 行用于下一轮 `Offer fmtp vs receiver params` 精确比较。只有证据指向具体字段时，才进入单字段 `tier-flag` 或 `level-id` compatibility rewrite。
