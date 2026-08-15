# v6.0.2 — Evidence Adoption and Boundary

## 直接依据

本版行为只来自 v6.0.1 真机日志、当前仓库调用链以及当前固定 Android WebRTC 版本的 H265 codec identity 行为。

已确认的工程事实：

```text
GFN HEVC Main Offer: profile-id=1;tier-flag=1;level-id=153
Local WebRTC receiver: generic H265 params={}
setCodecPreferences: applied=true
RAW_ANSWER: HEVC absent
H264 fallback: after createAnswer
HEVC MediaCodec: never attempted in failed run
```

固定依赖：

```text
io.github.webrtc-sdk:android:144.7559.09
```

在该版本的 H265 codec identity 中，profile/tier/tx-mode 参与相同性判断；`level-id` 不作为 codec identity 的直接相等条件。因此本版只对 `tier-flag` 做单字段 A/B，不把 `level-id` 一并改掉。

## 不采用的内容

v6.0.2 不实现 production `GfnHevcAwareVideoDecoderFactory`，不伪造本地高阶 capability，也不启用 Main10/HDR/AV1。

原因是本版目标仅是完成真机因果闭环：

```text
tier1 baseline -> no H265 RAW_ANSWER
tier0 treatment -> observe whether H265 appears
```

只有该实验完成后，才进入 Android `MediaCodecInfo.CodecProfileLevel` 真能力探测与 capability/decoder component 绑定。

## Production gate

实验 rewrite 不能留在正式方案中。production PASS 必须满足：

```text
Original GFN tier-flag=1 remains unchanged
Local advertised H265 capability is derived from real Android decoder capability
Advertised capability is bound to the actual decoder component used
RAW/FINAL Answer retain HEVC Main
HEVC RTP + decoder + first frame succeed
```
