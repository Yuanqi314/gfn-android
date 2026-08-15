# v5.4 — Audio Foundation

## 1. 目标

v5.4 只处理音频能力边界，不修改 H.264 视频、Keyboard、Gamepad packet 或 same-session reconnect 语义。

目标拆成两个互相独立的层：

1. **2ch：让现有 Android WebRTC Java ADM 明确配置为 stereo/2ch playout。**
2. **6ch：验证 GFN 是否提供 `multiopus/48000/6`，并建立可重复的协商探针。**

不把第二项冒充成 native 5.1。

## 2. 关键约束

当前工程使用 upstream-style `JavaAudioDeviceModule`。其公开 Android playout 配置只有 mono/stereo 开关，因此 v5.4 的本地物理输出能力模型是：

```text
nativeAudioOutputChannels = {2}
```

但 CloudMatch 已经存在 2..6 channel request 字段，因此 v5.4 允许：

```text
audioChannels = {2, 6}
```

两个集合故意不同。

## 3. Stereo 修复

`GfnWebRtcRuntime` 的 ADM builder 新增：

```text
setUseStereoOutput(true)
```

并继续使用：

```text
USAGE_GAME
CONTENT_TYPE_MUSIC
```

Answer 对第一条 game-audio Opus fmtp 做幂等 `stereo=1` 补充。

## 4. 6ch multiopus 探针

当 `ResolvedLaunchProfile.streamConfig.audioChannels == 6`：

1. CloudMatch 继续走已有 `audioMode=6 / surroundAudioInfo` 链。
2. 收到 GFN Offer 后，必须检测到第一条 game-audio 中 `multiopus` 且 channel count 为 6。
3. 若不存在，立即给出明确失败原因，不假设服务端支持。
4. 若存在，先让 libwebrtc 正常 `createAnswer`。
5. 如果 Answer 已接受同一个 multiopus PT，直接保留。
6. 如果第一 audio m-line 被拒绝（port 0），仅重建这一节：
   - payload type：复用 Offer；
   - `rtpmap`：复用 Offer codec/rate/channels；
   - `fmtp`：逐字复用 Offer parameters；
   - extmap：复用 Offer game-audio extmap；
   - ICE/DTLS transport：复用 Answer 已成功 video BUNDLE transport；
   - BUNDLE：恢复 game-audio mid。
7. 6ch audio bandwidth 使用 256 kbps；2ch 保持 128 kbps。

## 5. Diagnostics

新增：

```text
requestedChannels
admConfiguredOutputChannels
admStereoOutputEnabled
likelyRouteMaxChannels
likelyRouteSummary
offerCodec / offerChannels
answerCodec / answerChannels
opusStereoEnabled
surroundOfferPresent
surroundNegotiationAccepted
nativeSurroundOutput
outputMode
limitation
```

`likelyRoute*` 只是 Android public AudioManager 暴露的候选输出能力，不声称等于 libwebrtc 最终实际 AudioTrack route。

## 6. 故障边界

### 2ch 失败

如果没有声音或仍为单声道，优先看：

```text
Offer audio
Answer audio
Opus stereo=1
ADM stereo enabled
First audio RTP
Likely route
```

### 6ch Offer 不存在

这是有效的负结果：当前 Session/节点没有提供可验证 multiopus 6ch，不应继续构造假 5.1。

### 6ch 协商成功但本地仍 2ch

这是 v5.4 预期边界，不是实现宣称失败。真正 native 5.1 属于后续 custom ADM 工作。

## 7. 未包含

```text
custom Android AudioDeviceModule
native 6-channel AudioTrack / AAudio playout
5.1 speaker mapping verification
Dolby / spatial audio
microphone
HEVC / Main10 / HDR
```
