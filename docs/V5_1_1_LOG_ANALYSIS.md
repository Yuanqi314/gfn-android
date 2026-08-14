# v5.1.1 真机日志取证

输入日志：`14_08-13-27-19_719.log`。

## 1. 手动横屏 / 回主页

日志顺序：

```text
初始 MainActivity DecorView: 1264 x 2780
GfnAuth: 开始恢复本地登录状态
GfnAuth: 登录状态恢复成功

随后手动旋转：
MainActivity: window dying
新的 MainActivity DecorView 被 Add to mViews
rotation=1
bounds=2780 x 1264
GfnAuth: 再次开始恢复本地登录状态
GfnAuth: 再次恢复成功
```

结论等级：

- **已确认**：手动横屏 captured case 发生了 Activity/UI recreation。
- **已确认**：v5.1 当时 Controllers 与 fullscreen route 由 Composable `remember` 持有，不具备 recreation ownership。
- **高度吻合**：这解释了手动旋转后掉回普通/Home UI 的现象。
- **未确认**：用户曾提到的“没有主动旋转也偶发回主页”是否同根因；本日志没有复现，不做推断。

修复：Controller runtime 提升到 AndroidViewModel；UI route 使用 rememberSaveable；新增 Activity/Nav/WebRTC/Input correlation logging。

## 2. 无声音

日志明确出现：

```text
WebRtcAudioTrackExternal: initPlayout(sampleRate=48000, channels=1,...)
AudioTrack session ID 创建成功
WebRtcAudioTrackExternal: startPlayout
...
WebRtcAudioTrackExternal: stopPlayout
underrun count: 0
AudioTrack.stop: ... 5286240 frames delivered
```

这证明当前设备上的 Android/WebRTC playout 基础链已经工作，不是 AudioTrack 创建失败。

与此同时 v5.1 源码：

```kotlin
is AudioTrack -> track.setEnabled(false)
```

直接关闭 remote audio track。

修复：v5.1.1 改为启用 remote track，并记录 `remoteAudioTrackPresent / remoteAudioTrackEnabled / firstAudioRtp`。不在本轮引入自定义 ADM、5.1、音频 DSP 或 codec 重写。
