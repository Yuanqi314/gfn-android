# v5.1.2 真机测试指南

## A. Audio route

进入串流后：

1. 手机没有连接耳机/蓝牙时，应从内置扬声器播放，不应走听筒。
2. 按系统音量键，应显示/调整媒体音量，而不是通话音量。
3. 连接有线/蓝牙耳机时，应允许 Android 正常切换到该设备；本版不强制固定 speaker。
4. logcat 中关注 `WebRtcAudioTrackExternal` / `AudioTrack`。期望不再是 `stream 0`；若仍为 0，保留完整 AudioTrack 创建/停止日志。

## B. Keyboard

先不要按 Ctrl/Alt/Win，只测试：

```text
W A S D
Q E R F
M D
Space / Enter / Esc
```

期望普通字母只进入游戏，不触发 Windows 桌面/最小化/系统菜单。

然后分别测试真实 modifier：

```text
Shift + W
Ctrl + A
Alt + 一个游戏内已知快捷键
Windows/Meta + D（仅在你明确要验证系统键时）
```

HUD 新增：

```text
mods android=0x? tracked=0x? mismatch=N
```

如果普通字母再次最小化，马上记录：

```text
最后按的 key
mods android
tracked
mismatch count
```

并抓：

```bash
adb logcat -v threadtime GfnInput:W '*:S'
```

若出现例如：

```text
androidMask=0x8 trackedMask=0x0
```

则可直接证明 Android/OEM 报告了 phantom Meta，而 v5.1.2 已阻止其进入远端 packet。
