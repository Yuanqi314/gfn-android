# v5.1.2：音频路由与键盘 modifier 真值修复

## 1. 已确认：音频被当作通话流

上传的 `31.log` 在停止播放时记录：

```text
WebRtcAudioTrackExternal: underrun count: 0
AudioTrack: prior state:STATE_ACTIVE ... stream 0 ...
AudioTrack: called with 9456480 frames delivered
```

因此不是“无音频数据”，而是已经工作的 WebRTC playout 被 Android 归入了错误的输出类别。

v5.1.2 不再使用 libwebrtc 默认 playout attributes，而是在 `GfnWebRtcRuntime` 创建自定义 `JavaAudioDeviceModule`：

```text
USAGE_GAME
CONTENT_TYPE_MUSIC
```

再注入 `PeerConnectionFactory`。Activity 的硬件音量键目标同时设为 `STREAM_MUSIC`。

不强制指定内置扬声器；这样未连接外设时由 Android 正常选择扬声器，有线/蓝牙设备存在时仍可遵循系统路由。

`JavaAudioDeviceModule` 是调用方拥有的对象；Factory 创建后立即释放调用方 native ref，避免 ADM 生命周期泄漏。

## 2. 已确认：窗口最小化发生在远端，不是 Android App 回后台

`31.log` 在问题观察期间持续出现：

```text
GfnStream: state=FirstFrame ice=CONNECTED pc=CONNECTED
```

最终结束时才出现：

```text
state=SessionEnded
server session ended source=control_channel.exitMessage
```

所以这次“游戏窗口最小化”属于远端 Windows 输入语义问题。

## 3. 不确定：具体是哪一个 modifier 被错误带入

日志没有记录原始 `KeyEvent.keyCode/metaState`，因此目前不能 100% 证明是 phantom Meta/Win。

v5.1.1 的风险点是：普通字母 packet 的 modifier mask 直接由 Android `metaState` 生成。v5.1.2 改成：

```text
Android metaState
→ 仅 diagnostics

InputStateTracker 实际 modifier DOWN/UP
→ remote modifier truth
```

这样如果 Android 报 `META_META_ON`，但客户端没有实际收到/持有 Meta DOWN，普通字母仍发送 modifier=0。

真实 Meta 键并未被禁用：只有真实 `META_LEFT/META_RIGHT DOWN` 被状态机持有后，后续字母才会带 `0x0008`。

## 4. 本轮明确不改

```text
CloudMatch
GFN WSS
SDP
ICE
H.264
control_channel
Session End
mouse packet framing
wheel direction
releaseAll / epoch / ordered queue
Windows VK / Set-1 scan code mapping
```

## 5. 真机判断标准

Audio：

```text
无耳机/蓝牙 → 内置扬声器
音量键 → 媒体音量
AudioTrack 日志不再显示 stream 0
```

Keyboard：

```text
W/A/S/D/M/D 等普通字母 → 只进入游戏
不得触发 Windows 桌面/最小化
```

如果仍发生，记录 HUD：

```text
mods android=0x?
tracked=0x?
mismatch=N
```

并提供 `GfnInput` 日志即可继续确定真实 modifier 来源。
