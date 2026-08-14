# GFN Android Lab · v5.1 全屏键鼠

这是一个独立 Android GeForce NOW 客户端实验工程。v5.0 已在真实 Android 设备完成 **GFN WSS → SDP → ICE → H.264 RTP → 解码 → Surface 画面**；v5.1 在不修改已验证媒体链的前提下，只新增 **全屏串流页面、键盘、鼠标、Pointer Capture 与输入失焦安全状态机**。

> 仅使用用户自己的合法 GeForce NOW 账号；不修改订阅等级、账号 entitlement 或服务端授权。

## 当前真机里程碑

```text
Auth / restart restore           ✅
Membership                       ✅
Library / Catalog                ✅
Search / Game Detail             ✅
CloudMatch / Claim / RESUME      ✅
WSS / Offer / Answer             ✅
ICE / PeerConnection             ✅
H.264 RTP / Decode / Surface     ✅

v5.1 Keyboard / Mouse            ⏳ 待真机验证
```

真实媒体测试曾出现：`ConnectionInfo=2`、两条 `usage=14`、`Server ICE entries=0`，但 H.264 画面仍成功。因此 v5.1 不为了 `Server ICE=0` 改动已经成功的 ICE/host-candidate 路径。

## v5.1 唯一范围

```text
FullscreenStreamScreen
        ↓
GfnVideoSurfaceView
        ↓
Android KeyEvent / MotionEvent
        ↓
GfnKeyboardMouseInputController
        ↓
InputStateTracker
        ↓
GfnInputPacketEncoder
        ↓
input_channel_v1
```

只实现：

```text
Keyboard DOWN / UP
Modifiers
Mouse buttons
Mouse wheel
Relative mouse
Pointer Capture
releaseAll(reason)
Input diagnostics
```

本版明确不做：

```text
Gamepad / Touch Controller
Audio
HEVC / Main10 / HDR
复杂 Overlay
```

## 模块

```text
:app
:core-model
:core-network
:gfn-auth
:gfn-account
:gfn-games
:gfn-cloudmatch
:gfn-identity
:gfn-session
:diagnostics
:stream-core
:stream-input       ← v5.1 新增纯 Kotlin GFN Input 协议/状态模型
:stream-signaling
:stream-webrtc      ← Android 输入采集 + DataChannel transport
:protocol-cli
```

`stream-webrtc` 继续使用：

```kotlin
api("io.github.webrtc-sdk:android:144.7559.09")
```

不能改回 `implementation(...)`：公开的 `GfnVideoSurfaceView` 继承 WebRTC 的 `SurfaceViewRenderer`，WebRTC 类型属于模块公共 ABI。

## DataChannel Gate

`input_channel_v1` 的 `OPEN` 不代表可以发送键鼠。v5.1 等待服务器第一条 input handshake：

```text
DataChannel OPEN
↓
server handshake
↓
parse protocol version
↓
neutralize uncertain remote state
↓
protocolReady = true
↓
KeyboardActive / MouseActive
```

不会 echo server handshake。

## releaseAll(reason)

统一入口覆盖：

```text
Activity pause / destroy
Window focus lost
WebRTC disconnect
DataChannel close
Overlay open
Session End / switch
Input disable
Fullscreen exit
Reconnect / user disconnect
```

Pointer Capture lost 是特例：它只关闭 `MouseActive`，释放鼠标按钮并清 motion/wheel；如果窗口仍有焦点，不释放键盘。

全量释放使用：

```text
freeze admission
→ input epoch++
→ ordered queue
→ ordinary key UP
→ mouse button UP
→ modifier UP
→ clear mouse/wheel accumulator
→ queue barrier / bounded transport drain（主动断开）
```

如果 DataChannel 已关闭，本地只能清状态并把远端标为 `UNKNOWN`；不能宣称远端已经收到 UP。

## 输入状态分离

```text
physicalHeldKeys / physicalHeldMouseButtons
remoteAssumedHeldKeys / remoteAssumedHeldMouseButtons
uncertainRemoteKeys / uncertainRemoteMouseButtons

RemoteState:
ASSUMED_SYNCED
RELEASING
UNKNOWN
```

`DataChannel.send() == true` 只表示本地 WebRTC 接受 packet，不等价于远端游戏已经处理。

## 全屏页面

全屏页隐藏系统栏，视频占满窗口。普通 `Esc` 继续发给远端游戏；本地 Overlay 使用 Android Back。Overlay 打开前先 `releaseAll(OverlayOpen)` 并释放 Pointer Capture；关闭后重新请求 Pointer Capture，只有 capture callback 真正确认后才恢复鼠标相对输入。

## 验证边界

当前环境没有 Android SDK，因此不能在这里声明完整 `assembleDebug` 已通过。已完成：

```text
stream-input JVM 编译                         PASS
GFN input packet / handshake fixture          PASS
releaseAll / pointer-capture / UNKNOWN fixture PASS
stream-webrtc API-shaped Kotlin 类型检查       PASS
GfnStreamingController 类型检查               PASS
模块边界 staged 编译                          PASS（针对 v5.1 变更链）
```

完整 `verify-core.sh` 在当前受限容器里仍会因累计编译耗时达到执行超时；最新一次到 `stream-core` compile start 为止均无 compiler error。v5.1 变更模块已另外逐个完成 targeted compile，因此不把全量超时冒充成 PASS。

真机下一步只验证：键盘 W/A/S/D、Shift/Ctrl/Alt、鼠标左右中键、滚轮、Pointer Capture 相对移动，以及 pause/focus/overlay/disconnect 后不会出现“卡 W / 卡鼠标键”。
