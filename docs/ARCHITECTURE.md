# v5.1.1 架构

## 总边界

```text
Auth / Content                   [soft-freeze；Auth persistence 仅 diagnostics]
CloudMatch / Session             [wire protocol soft-freeze；新增 server-end/reconcile UI glue]
GFN Signaling / WebRTC H.264     [真机成功，soft-freeze]
Remote AudioTrack                [v5.1.1 只恢复 enable]
Input Protocol                   [v5.1 frozen architecture]
Android Input Capture            [v5.1.1 wheel sign fix]
Runtime ownership / Fullscreen   [v5.1.1]
```

## Configuration-safe runtime ownership

```text
MainActivity
    ↓ ViewModelProvider
GfnAppRuntimeViewModel
    ├── AuthController
    ├── GfnContentController
    ├── GfnSessionController
    └── GfnStreamingController
            ↓
        GfnWebRtcEngine
```

Activity/Compose recreation 只重建 UI 和新的 `GfnVideoSurfaceView`；Session/WebRTC owner 不由 Composable 生命周期持有。

UI route：

```text
tabName
fullscreenStream
```

使用 `rememberSaveable`。Fullscreen 重新组成后通过 identity-safe `bind/unbindVideoOutput(view)` attach 到现存 video track。

## Fullscreen policy

```text
enter fullscreen
→ best-effort SENSOR_LANDSCAPE
→ immersive system bars
→ actual Window bounds / fillMaxSize
→ SurfaceViewRenderer SCALE_ASPECT_FIT
```

`requestedOrientation` 不是布局真值。设备不执行方向请求时，仍以实际 Window 尺寸布局；不为填满屏幕而拉伸 16:9 视频。

## Stream / input chain

```text
FullscreenStreamScreen
        ↓
GfnStreamingController
        ↓
GfnWebRtcEngine
        ├── VideoTrack → GfnVideoSurfaceView
        ├── AudioTrack → enabled playout
        ├── control_channel → exitMessage
        └── GfnKeyboardMouseInputController
                ├── AndroidKeyboardMapper
                ├── InputStateTracker
                ├── InputEpochGate
                ├── GfnInputPacketEncoder
                └── input_channel_v1
```

## Input invariants

- DataChannel OPEN != protocolReady。
- KeyboardActive 与 MouseActive 分离。
- Pointer Capture lost 只释放鼠标。
- 所有离散 input packet / release 走 ordered executor。
- 全量 suspension 先推进 epoch；旧 DOWN 不得在 release 后复活。
- transport 已关闭时 remote state 为 UNKNOWN，不伪造 release ACK。
- 主动 End：release → queue barrier → bounded local drain → close。

### Wheel

Android 真机证据优先于 Apple event sign：

```text
GFN wheel delta = Android AXIS_VSCROLL * 3
```

packet framing/type 不变。

## Server terminal event

Primary：

```text
PeerConnection.onDataChannel
→ control_channel
→ copy callback bytes
→ JSON exitMessage
→ current connection generation
→ exact channel identity
→ terminal idempotence
→ input SessionEnd release
→ StreamState.SessionEnded
→ SessionUiState.Ended
→ clear resume record
```

Secondary（transport 异常）：

```text
ICE / PC disconnected/failed 或 control_channel CLOSED
→ request Session reconcile
→ poll 当前已知 Session
→ HTTP 404 / 410：terminal
→ 其他 HTTP/API/status：只记录，不猜
```

不会引入未经验证的 active-session endpoint。

## Audio

v5.1 的 remote audio receiver 被显式 `setEnabled(false)`；v5.1.1 改为 `setEnabled(true)`。WebRTC 自带 Android playout 继续负责设备输出。本轮不改变 SDP audio codec、不引入自定义 ADM/DSP/5.1。

Diagnostics：

```text
remoteAudioTrackPresent
remoteAudioTrackEnabled
firstAudioRtpPacketReceived
requestedChannels
```

## JNI callback safety

`DataChannel.Observer` 和 WebRTC observer 都是 native→Java 边界；control/input callbacks 内部使用 exception containment，并在 callback 内复制 ByteBuffer，避免异常穿回 JNI 或使用被 native 回收的 buffer。
