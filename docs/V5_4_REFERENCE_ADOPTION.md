# v5.4 Reference Adoption

原则：CloudNow / OpenNOW / upstream WebRTC 都是证据来源；本项目不把第三方实现当协议规范。

## CloudNow

可吸收证据：

- Answer 对 Opus 补 `stereo=1`。
- GFN surround Offer 可出现 `multiopus/48000/6`。
- 当 createAnswer 拒绝 multiopus 时，可只重建 game-audio section，并复用 Offer exact fmtp 与 Answer bundle transport。
- CloudNow 为真正 5.1 另外实现了自定义 audio device / multichannel playout。

本项目 v5.4 吸收前三项作为 negotiation probe，但**没有复制 CloudNow 的 Apple 自定义 audio device**。

## OpenNOW

可吸收证据：

- Answer 注入 audio bandwidth。
- Opus fmtp 补 `stereo=1`。

当前检查没有找到 OpenNOW 的 multiopus/native-5.1 对等实现，因此不从它推导 6ch 行为。

## upstream Android WebRTC

关键边界：

- `JavaAudioDeviceModule` 默认 stereo output 关闭；可通过 `setUseStereoOutput(true)` 启用 2ch。
- Android Java ADM 的公开/原生配置分支只表达 1 或 2 output channels。
- 内置 WebRTC tree 有 multi-channel Opus decoder，但 builtin decoder factory 不主动 advertise multiopus。

因此 v5.4 的裁决是：

```text
ADM stereo/2ch configuration              IMPLEMENT
multiopus 6ch signaling/receive probe      IMPLEMENT
Native 5.1 physical playout                DO NOT CLAIM
```
