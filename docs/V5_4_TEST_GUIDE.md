# v5.4 True-Device Test Guide

## A. 2ch production regression

保持已验证配置：

```text
1920x1080
60 FPS
100 Mbps
H.264
Keyboard en-US
Game language zh_CN
Audio = Stereo 2ch
```

必须创建新 Session。

检查：

```text
[ ] 画面正常
[ ] 声音正常
[ ] Cyberpunk / CS2 keyboard regression 正常
[ ] Audio Diagnostics: Requested channels = 2ch
[ ] ADM output = 2ch
[ ] ADM stereo enabled = YES
[ ] Offer audio = opus/2ch（若服务端如此提供）
[ ] Answer audio = opus/2ch（若 libwebrtc 选择 Opus）
[ ] Opus stereo=1 = YES（Offer/Answer 有 Opus fmtp 时）
[ ] First audio RTP = YES
```

左右声道是否真的独立，需要游戏/测试素材提供明确 L/R 声源；“听到声音”不能单独证明 stereo separation。

## B. Experimental 6ch negotiation probe

设置：

```text
Audio = 5.1 / 6ch（实验：multiopus；ADM 仍 2ch）
```

结束旧 Session 后创建新 Session。

可能结果：

### B1. Offer 不含 multiopus/6

客户端会明确停止：

```text
GFN Offer 未包含 multiopus/6
```

记录为服务端/Session 不提供该实验能力，不修改 SDP 去伪造。

### B2. Offer 含 multiopus/6，但 setLocalDescription / remote audio 失败

保留完整日志，重点提供：

```text
Offer audio
Answer audio
6ch offer
6ch negotiated
ICE/PC state
First audio RTP
```

这说明当前 Android WebRTC build 的 multiopus receive path 不能仅靠 Answer munging闭环。

### B3. 6ch negotiated = YES，First audio RTP = YES

这只证明：

```text
GFN multiopus 6ch negotiation/receive path reached
```

仍然**不证明 native 5.1**，因为 v5.4 本地 ADM 配置为 2ch。

## C. 不属于 v5.4 回归

```text
v5.2.1 第一次 reconnect 黑屏：已知 backlog
v5.3 gamepad 真机：当前无手柄，已决定跳过
```
