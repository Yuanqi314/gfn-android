# v5.1 真机测试指南

## 前提

v5.0 H.264 画面已经真机成功。v5.1 只判断输入，不重新调整 CloudMatch/WSS/SDP/ICE/H.264。

## 1. 构建

Android Studio：

```text
Sync
→ :app assembleDebug
→ 真机安装
```

如果失败，只保留第一条 Kotlin/Java compiler error。

## 2. 进入全屏

真实 Session 到可播放状态后进入：

```text
进入全屏键鼠
```

期望：系统栏隐藏、视频继续播放；Input HUD 能看到 DataChannel / Protocol / Pointer Capture / RemoteState。

## 3. DataChannel handshake

期望：

```text
input_channel_v1 = OPEN
Protocol = v2 或 v3（以服务器实际为准）
protocolReady = true
```

如果只有 OPEN 而 Protocol 未出现，不应发送键鼠；提供 Input HUD 状态即可。

## 4. Keyboard

依次测试：

```text
W A S D
Space
Enter
Esc
Shift
Ctrl
Alt
Tab
Arrow Keys
```

验证 DOWN/UP 都能在远端产生对应动作。Esc 应传给游戏，不用于本地 Overlay。

## 5. releaseAll 防卡键

在远端持续按住 W，分别触发：

```text
Activity pause
Window focus lost
打开本地 Overlay
主动退出全屏
WebRTC disconnect（可控场景）
Session End
```

期望：远端角色停止，不出现永久向前。

HUD 应看到 held keys 归零；如果 DataChannel 已经先关闭，允许 `RemoteState=UNKNOWN`，不能要求假装 ASSUMED_SYNCED。

## 6. Pointer Capture

鼠标捕获后测试相对移动。然后在仍保持窗口焦点时主动失去 Pointer Capture：

```text
鼠标按钮必须 release
relative motion / wheel accumulator 清空
KeyboardActive 仍可继续
```

例如 W 保持按下时失去 Pointer Capture，不应立即产生 W UP；之后 Window focus lost 才应释放 W。

## 7. Mouse

验证：

```text
Left / Right / Middle
Wheel Up / Down
Relative X/Y
```

如果 Y 方向反了，请只回报“上下反向”，不要改 GFN packet framing；修正应局限在 Android mouse mapper。

## 8. 重复 lifecycle 幂等

```text
W DOWN
→ onPause
→ focus lost
→ pointer capture lost
```

不应产生重复有效 W UP，也不应闪退。

## 9. 主动 End

按住 W 后执行 End Session：

```text
freeze input
→ W UP submitted
→ local DataChannel drain/barrier
→ End Session
```

没有 application-level ACK，因此只能验证本地 packet submission/drain 和远端实际行为，不能仅靠 `send=true` 宣称服务器已处理。

## 建议回报

```text
Protocol v?
DataChannel OPEN/CLOSED
KeyboardActive
MouseActive
PointerCapture
RemoteState
Physical Held / Remote Assumed
Generated / Submitted / Rejected / Dropped
最后输入事件
最后 release reason

以及真机：
WASD 是否生效
鼠标按钮/滚轮是否生效
鼠标 X/Y 是否方向正确
pause/focus/overlay 后是否有卡键
```
