# v6.0.4 HEVC Main Production Capability — 真机测试指南

## 目的

验证客户端在**不修改 GFN H265 tier/profile/level** 的情况下，依赖真实 Android decoder capability 与原始 Main / High-Tier Offer 完成 HEVC Main / SDR8 协商、解码和渲染。

## 测试前提

- 新建 Session，不复用旧 v6.0.3 PeerConnection。
- Settings 选择 HEVC。
- 维持当前 production 边界：1920x1080@60、SDR8、Main10/HDR 关闭。
- 不启用任何 tier rewrite。

## Logcat

```text
adb logcat -v time GfnHevcCompat:I AndroidVideoDecoder:I CCodec:I CCodecConfig:I OplusMediaMonitor:W EglRenderer:W '*:S'
```

如果 OEM tag 不同，至少保留 `GfnHevcCompat`、`AndroidVideoDecoder`、MediaCodec/CCodec 和 renderer 日志。

## 第一裁决：真实本地 capability

必须先看：

```text
phase=HEVC_DECODER_CANDIDATE
phase=HEVC_PRODUCTION_ADVERTISEMENT
phase=LOCAL_DECODER
phase=LOCAL_RECEIVER
```

Production HEVC 的必要信号：

```text
HEVC_PRODUCTION_ADVERTISEMENT enabled=true
profile=1
tier=1
level >= 153
```

并确认 `decoder=<name>` 是运行时探测结果，不是代码硬编码。

如果 `enabled=false`，保存所有 `HEVC_DECODER_CANDIDATE` / `HEVC_DECODER_PROBE_ERROR` 日志。此时 H264 fallback 是预期结果，不能重新启用 Tier0 rewrite。

## 第二裁决：原始 GFN Offer

确认 Offer 中 Main candidate 仍为：

```text
H265
profile-id=1
tier-flag=1
level-id=153
```

日志中不应出现：

```text
OFFER_TIER_AB
OFFER_TIER_AB_REWRITE
```

## 第三裁决：production intersection

必须出现：

```text
phase=OFFER_HEVC_COMPATIBLE compatible=true
matched=[<dynamic PT>]
streamSafe=true
```

PT 不固定为 103；以当前 Session 动态解析结果为准。

若 `compatible=false`，以 `OFFER_HEVC_REJECT` 和 `reason=` 为根因，不推测。

## 第四裁决：WebRTC receiver capability / preference

确认 receiver 的 H265 capability 是显式 Main/High，而不是 generic：

```text
LOCAL_RECEIVER ... H265 ... profile-id=1 ... tier-flag=1 ... level-id=<...>
```

随后：

```text
PREFERENCE_PLAN compatibleHevcMain>=1
PREFERENCE_APPLY attempted=true applied=true
```

## 第五裁决：Answer

```text
RAW_ANSWER hevc=[...]
ANSWER_HEVC_MAIN_LINEAGE stage=RAW_ANSWER matched=[...]
DECISION stage=RAW_ANSWER effective=Hevc fallback=false

FINAL_ANSWER hevc=[...]
ANSWER_HEVC_MAIN_LINEAGE stage=FINAL_ANSWER matched=[...]
DECISION stage=FINAL_ANSWER effective=Hevc fallback=false
```

Answer 的 fmtp 可能被 libwebrtc 规范化，因此 Main 判断继续以同 Session Offer/Answer H265 PT lineage 为准。

## 第六裁决：真实解码 / 渲染

继续确认：

```text
HEVC RTP received
AndroidVideoDecoder / MediaCodec creates H265
mime=video/hevc
actual decoder component matches production-bound decoder
FIRST_FRAME effective=Hevc
```

稳定阶段建议至少观察数秒：

```text
inputFps ≈ 60
outputFps ≈ 60
renderFps ≈ 60
```

## PASS

只有以下全链同时成立，v6.0.4 才标记 `HEVC Main / SDR8 Production PASS`：

```text
original Tier1 Offer unchanged
+ real Main/High/Level>=5.1 local capability
+ exact decoder binding
+ compatible=true / streamSafe=true
+ RAW Answer H265
+ Final Answer H265
+ fallback=false
+ H265 hardware decoder
+ FIRST_FRAME Hevc
+ stable rendering
```

## 安全 fallback

以下任一情况出现时，H264 fallback 是正确 production 行为：

```text
no recognized HEVC Main High-Tier profileLevel
local max level < remote level
size/rate unsupported
requested max bitrate outside decoder range
bound WebRTC hardware factory rejects selected component
original Offer has no compatible Main/High candidate
```

不要用实验 tier rewrite 绕过这些 gate。

## 独立 backlog

`EglRenderer: Dropping frame - No surface` 继续单独记录 Surface/EGL 生命周期，不作为 HEVC codec negotiation failure。
