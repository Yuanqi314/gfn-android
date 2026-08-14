# v5.1 架构

## 总边界

```text
Auth / Content             [soft-freeze]
CloudMatch / Session       [soft-freeze]
GFN Signaling / WebRTC     [v5.0 H.264 真机成功，soft-freeze]
Input Protocol             [v5.1]
Android Input Capture      [v5.1]
Compose Fullscreen UI      [v5.1]
```

v5.1 不因为输入问题修改 CloudMatch、WSS、SDP、ICE、H.264 decoder 或 Surface。

## 调用链

```text
FullscreenStreamScreen
        ↓
GfnStreamingController
        ↓
GfnWebRtcEngine
        ├── GfnVideoSurfaceView
        │       ├── KeyEvent
        │       ├── MotionEvent
        │       └── Pointer Capture
        │
        └── GfnKeyboardMouseInputController
                ├── AndroidKeyboardMapper
                ├── InputStateTracker
                ├── InputEpochGate
                ├── GfnInputPacketEncoder
                └── PacketSink
                        ↓
                  input_channel_v1
```

`stream-input` 不依赖 Android/WebRTC，负责 GFN 输入 packet、handshake parser、held-state 与 release plan。`stream-webrtc` 负责 Android 事件和 WebRTC DataChannel transport。

## Server handshake

```text
input_channel_v1 OPEN
↓
等待 server message
↓
firstWord == 526 (0x020e)
  version = bytes[2..3] LE
或 bytes[0] == 0x0e
  version = firstWord
↓
ordered queue 中 neutralize uncertain state
↓
protocolReady = true
```

其他 DataChannel message 不当 handshake；客户端不 echo handshake。

## GFN packet framing

协议类型：

```text
2   heartbeat
3   key down
4   key up
7   mouse relative
8   mouse button down
9   mouse button up
10  mouse wheel
```

v2 keyboard：

```text
u32 LE type
u16 BE virtual key
u16 BE modifiers
u16 BE Windows Set-1 scan code
u64 BE timestamp(us)
```

v3 single-event 包增加 `0x23 + timestamp + 0x22` wrapper；mouse relative 使用 `0x21 + body length` wrapper。

## Android keyboard mapping

绝不直接使用 `KeyEvent.scanCode` 作为 Windows scan code。映射链：

```text
Android KEYCODE_*
→ AndroidKeyboardMapper
→ GfnKey(virtualKey, windowsSet1ScanCode, modifierBit)
```

支持 A-Z、0-9、Esc/Tab/Space/Enter、符号键、F1-F12、方向/导航、数字小键盘、左右 Ctrl/Shift/Alt/Meta。

## Keyboard / Mouse Active 分离

```text
KeyboardActive =
    streamConnected
    && lifecycleResumed
    && windowFocused
    && dataChannelOpen
    && protocolReady
    && inputEnabled
    && !overlayOpen

MouseActive = KeyboardActive && pointerCaptured
```

Pointer Capture lost 只触发 mouse-only suspension；不会推进全局 input epoch。

## ordered queue + epoch

所有 packet/state mutation 进入单线程 ScheduledExecutor。

全量 suspension：

```text
事件线程：inputEpoch++
↓
ordered queue：releaseAll(epoch)
```

每个用户输入事件在产生时捕获 `eventEpoch`，进入 queue 后再次检查；epoch 已变化则丢弃 stale event。

## releaseAll(reason)

触发点：Activity pause/destroy、Window focus lost、WebRTC disconnect、DataChannel close、Overlay open、Session End/Switch、Input disable、Fullscreen exit、Reconnect、User disconnect。

确定化释放顺序：

```text
普通键 UP
→ 鼠标按钮 UP
→ Modifier UP
→ 清 relative motion
→ 清 wheel accumulator
```

Remote state 分为：

```text
ASSUMED_SYNCED
RELEASING
UNKNOWN
```

没有 application ACK，所以命名为 ASSUMED，而不是绝对 SYNCED。

## transport 已关闭

不能发送真实 UP 时：

```text
physical held -> clear
remote assumed -> uncertain
RemoteState -> UNKNOWN
```

重新收到 input handshake 时，先尝试对 uncertain held snapshot 补 UP；完成后才放开 `protocolReady`。

## 主动断开

```text
freeze admission
→ epoch++
→ releaseAll
→ ordered queue barrier
→ bounded bufferedAmount drain (120ms)
→ close PeerConnection / Session action
```

buffer drain 只说明本地 DataChannel 缓冲下降，不代表服务器/游戏 ACK。

## 鼠标

- Pointer Capture 下使用 `AXIS_RELATIVE_X/Y`。
- 消费 `MotionEvent` historical batched relative samples，再消费 current sample。
- motion/wheel 做 accumulator/coalescing，避免高 polling mouse 把离散 Key UP/Mouse UP 堵在队列后面。
- mouse button：Left=1、Middle=2、Right=3。
- wheel 当前按参考行为进行方向/倍率转换，最终以真机为准。

## JNI callback 安全

DataChannel Observer 是 native→Java/JNI 回调入口。v5.1 的 `onStateChange/onMessage` 不允许 Kotlin 异常向外逃逸：输入握手解析失败、executor 已 shutdown 等情况只记录/忽略，避免再次触发 WebRTC `HandleException → SIGABRT` 类型故障。
