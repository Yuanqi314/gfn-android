# v5.1.2 `31.log` 取证

## 1. 音频

日志确认游戏音频已经真实播放；停止时：

```text
WebRtcAudioTrackExternal: stopPlayout
WebRtcAudioTrackExternal: underrun count: 0
AudioTrack: prior state:STATE_ACTIVE ... stream 0 ...
AudioTrack: called with 9456480 frames delivered
```

因此问题不是“没有 Audio RTP / AudioTrack 没工作”，而是 Android 输出类别/路由错误。`stream 0` 与用户观察到的通话音量一致。v5.1.2 在 ADM 创建层把 playout AudioAttributes 改为 GAME/MUSIC。

不强制 `setCommunicationDevice(BUILTIN_SPEAKER)` 或 legacy `speakerphoneOn`，因为这会覆盖用户真实耳机/蓝牙选择。

## 2. 键盘导致远端游戏窗口最小化

日志期间 Android Activity 保持前台和焦点，stream 持续：

```text
GfnStream: state=FirstFrame ice=CONNECTED pc=CONNECTED
```

直到服务端真实退出事件：

```text
GfnStream: state=SessionEnded
server session ended source=control_channel.exitMessage
```

才发生 Activity pause/stop。

因此该“最小化”不是 Android App 自己进入后台，而是在远端 Windows 会话内发生。

旧日志没有 raw KeyEvent/metaState，所以不能 100% 证明是哪一个 modifier。当前源码风险点是普通字母 packet 直接使用 Android `KeyEvent.metaState` 生成 GFN Shift/Ctrl/Alt/Meta mask；若 OEM/物理键盘附带 phantom META，远端会把字母解释成 Windows 系统快捷键。

v5.1.2 不修改字母 VK/scan code，而是将 modifier 真值改成 `InputStateTracker` 实际 held modifier；同时保留 raw meta diagnostics，供真机最终确认。

## 3. 已确认 v5.1.1 通过项

用户真机确认：

```text
声音恢复                  PASS
滚轮方向                  PASS
自动横屏/适配             PASS
游戏退出自动 Session End  PASS
```

这些链不在 v5.1.2 中重构。
