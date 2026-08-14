# CloudNow 参考记录 · 第五版

CloudNow 是协议/行为参考，不是 Android 代码模板。当前固定参考仓库：`owenselles/CloudNow`，取证基准 commit：`f9292868369b0fe41a2d559d0c8f3805193f4389`。

## v5 重点参考文件

### `CloudNow/Streaming/SignalingClient.swift`

用于确认当前已经工作的 GFN signaling 行为：

```text
/nvst/sign_in
peer_id
version=2
peer_role=1
pairing_id=sessionId
```

连接后：

```text
peer_info
ack / heartbeat
offer
remote ICE
answer
local ICE
```

Android 不复制 Apple 的 `NWConnection`、DNS race、证书处理等平台实现。第一版使用标准 OkHttp/TLS；只有真机证据证明握手不兼容时才修改 transport。

### `CloudNow/Streaming/SignalingMessageCodec.swift`

用于确认 envelope：

```text
peer_info
ackid / ack
hb
peer_msg.from / to / msg
```

其中 `peer_msg.msg` 是 JSON 字符串；offer/ICE/answer 在第二层 JSON 内。

### `CloudNow/Streaming/GFNStreamController.swift`

只参考已经验证的协议顺序：

```text
Offer
→ PeerConnection
→ setRemote
→ create Answer
→ Answer codec policy
→ setLocal
→ extract local ICE / DTLS
→ answer + nvstSdp
→ inject remote host candidates
```

以及：

```text
server ICE 可能为空
GFN offer 可能没有 a=candidate
media ConnectionInfo priority: 2 → 17 → 14 fallback
```

不复制 Apple 音频设备、VideoToolbox、tvOS input、重连 UI。

### `CloudNow/Streaming/SDPMunger.swift`

v5.0 只吸收：

```text
H.264 codec 收敛放在 Answer 侧
保留关联 RTX/FEC
带宽 hint
```

H.265/Main10 变换明确不进入 v5.0。

## Android 自己的决策

```text
OkHttp WebSocket + HTTP/1.1
标准 Android TLS 验证
直接 org.webrtc PeerConnection
H.264 / SDR8 / 1080p60
不自动启用公共 STUN fallback
不实现 Audio/Input
不实现 HEVC/Main10/HDR
```

## 零假设边界

当前仍不确定：

```text
真实 Android WSS 是否需要额外 TLS/hostname 兼容
真实 Offer 消息到达顺序
真实 zone 是否始终无 server ICE
真实 H.264 profile/PT 组合
最终 decoder 是硬件还是软件
```

这些只由真机 Diagnostics 决定，不从 Apple 实现反推为 Android 必然行为。

## v5.1 Input 参考

当前 `CloudNow/Streaming/InputSender.swift` 用于取证 GFN 键鼠 wire format，而不是复制 Apple 输入框架。

确认的 packet type：

```text
2 heartbeat
3 keyDown
4 keyUp
7 mouseRel
8 mouseBtnDown
9 mouseBtnUp
10 mouseWheel
```

确认 keyboard body 为 `u32 LE type + u16 BE vk + u16 BE modifiers + u16 BE Set-1 scan + u64 BE timestamp`；鼠标 move/button/wheel 使用对应固定布局。protocol >=3 对单事件和 pointer move 使用额外 wrapper。

当前 `GFNStreamController` 还确认：`input_channel_v1` OPEN 后必须等待服务器 handshake；`0x020e` 形式从 bytes[2:3] 读取版本，另一形式以 `0x0e` 开头，客户端不 echo，只有 handshake 完成后才启动输入 sender。

Android 只采用上述协议行为。`KeyEvent`/`MotionEvent`、Pointer Capture、lifecycle、ordered executor 与 releaseAll 状态机均为 Android 自己的实现。
