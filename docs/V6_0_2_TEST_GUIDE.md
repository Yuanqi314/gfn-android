# v6.0.2 — HEVC Tier-Flag A/B True-Device Test Guide

## 固定变量

保持与 v6.0.1 基线一致：

```text
1920x1080
60 FPS
100 Mbps
Audio 6ch
Keyboard en-US
Game language zh_CN
Video codec HEVC Main · SDR8
```

必须结束旧 Session 后创建新 Session，避免复用既有协商状态。

## 测试前静态验证

```text
sh ./verify-hevc-compat.sh
sh ./verify-hevc.sh
sh ./verify-reconnect-engine.sh
```

## Logcat

```text
adb logcat -c
adb logcat -v time GfnHevcCompat:I '*:S'
```

## 必看顺序

```text
SESSION requested=Hevc
LOCAL_RECEIVER codec=H265 params="-"
OFFER_CODEC codec=H265 profile=1 tier=1 level=153
OFFER_TIER_AB_REWRITE applied=true from=1 to=0
OFFER_TIER_AB_CODEC codec=H265 profile=1 tier=0 level=153
PREFERENCE_APPLY attempted=true applied=true
RAW_ANSWER ...
DECISION stage=RAW_ANSWER ...
FINAL_ANSWER ...
DECISION stage=FINAL_ANSWER ...
MEDIA stage=FIRST_VIDEO_RTP ...
MEDIA stage=FIRST_FRAME ...
```

## A/B 因果判定

A 基线是 v6.0.1 已保存的真实结果：

```text
Offer Main Tier1
RAW_ANSWER HEVC empty
```

B 是 v6.0.2：

```text
同一环境
唯一新增 SDP 变量 = Main tier-flag 1→0
```

如果 B 出现：

```text
RAW_ANSWER hevcMain != empty
```

即可确认 tier mismatch 是 `createAnswer()` 排除 HEVC Main 的直接触发条件。

## 结果分类

### B1 — negotiation 因果成立且全链成功

```text
RAW_ANSWER HEVC Main != empty
FINAL_ANSWER HEVC Main != empty
fallback=false
HEVC RTP
HEVC decoder
FIRST_FRAME Hevc
```

记录为：tier negotiation root cause confirmed；随后删除实验 rewrite，进入 production capability advertisement。

### B2 — negotiation 因果成立，但 decoder/render 失败

```text
RAW_ANSWER HEVC Main != empty
后续 HEVC MediaCodec / RTP / frame 失败
```

记录为：tier negotiation root cause confirmed，同时出现新的下游故障；继续跟踪到具体 HEVC decoder component/configure/output，不允许退回“协商失败”解释。

### B3 — RAW_ANSWER 仍无 HEVC

不得继续猜测。保存全部 `GfnHevcCompat` 行，重新比较：

```text
OFFER_TIER_AB_CODEC
LOCAL_RECEIVER
PREFERENCE_PLAN / APPLY
RAW_ANSWER_CODEC
```

此时 tier mismatch 不能被判定为唯一真机根因，需要继续检查 exact WebRTC codec identity / tx-mode / native capability path。

## 禁止项

本轮禁止同时修改：

```text
level-id
profile-id
Main10
HDR
decoder factory
CloudMatch
NVST
Answer munging
H264 fallback
```
