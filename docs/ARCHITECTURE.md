# 第五版架构

## 总边界

```text
Compose UI
≠ Auth
≠ GFN Content
≠ CloudMatch Session
≠ GFN Signaling Envelope
≠ WebRTC PeerConnection
≠ Video Decoder / Surface
```

v5 不把 signaling JSON 塞进 CloudMatch，也不让 WebRTC 重新创建 Session。

## 调用链

```text
GfnAndroidApp
│
├── AuthController                 [soft-freeze]
├── GfnContentController           [soft-freeze]
├── GfnSessionController           [v4 soft-freeze]
│   └── Claimed SessionInfo
│
└── GfnStreamingController         [v5]
    └── GfnWebRtcEngine
        ├── GfnSignalingClient     OkHttp WebSocket transport
        ├── stream-signaling       envelope / SDP / NVST SDP
        ├── PeerConnectionFactory  direct org.webrtc API
        └── GfnVideoSurfaceView    SurfaceViewRenderer
```

## 输入契约

`GfnWebRtcEngine` 只接受：

```text
SessionInfo.isReadyStatus == true
signalingUrl != null
```

并强制：

```text
H264 / SDR8 / 1920x1080 / 60 / Stereo
```

它不调用 CloudMatch Create/Claim。

## Signaling 状态

```text
Idle
↓
OpeningSignaling
↓
AwaitingOffer
↓
NegotiatingSdp
↓
IceChecking
↓
Connected
↓
FirstFrame
```

失败：

```text
Failed(reason)
```

用户主动断开：

```text
Closed
```

## WebSocket

```text
{signalingUrl}/sign_in
  peer_id=random
  version=2
  peer_role=1
  pairing_id=sessionId
```

目前 Android 使用标准 TLS + OkHttp HTTP/1.1。不会在没有真机错误证据时绕过证书验证。

## Signaling envelope

```text
root
├── peer_info
├── ackid / ack
├── hb
└── peer_msg
    ├── from
    ├── to
    └── msg = JSON string
        ├── offer + sdp
        └── candidate + sdpMid + sdpMLineIndex
```

Answer：

```text
peer_msg.msg = {
  type: answer,
  sdp: <WebRTC Answer>,
  nvstSdp: <GFN capability descriptor>
}
```

## SDP 原则

1. 不先修改服务器 Offer 的 codec 列表。
2. 有明确 media IP 时只修正 `0.0.0.0/127.0.0.1` 占位地址。
3. `setRemoteDescription(Offer)`。
4. libwebrtc 生成 Answer。
5. 只在 Answer 侧保留 H.264 + 其 RTX/FEC repair PT。
6. 注入带宽 hints。
7. `setLocalDescription(Answer)`。
8. 从本地 Answer 读取 ICE credential / DTLS fingerprint，构造 NVST SDP。

## ICE

服务端 ICE 与有效 ICE 分开：

```text
Server ICE entries     = SessionInfo.iceServers
Effective ICE servers  = 实际 RTCConfiguration
```

v5.0 不启用公共 STUN fallback。

GFN Offer 可能不带 `a=candidate`。Answer 发出后，v5 根据 Session 数据构造远端 host candidate：

```text
media connection priority:
usage 2
→ usage 17
→ usage 14 highest valid port
```

端口来源：

```text
selected ConnectionInfo.port
+
Offer first video m-line port
```

IP 来源：

```text
selected media ConnectionInfo
resolved server
sessionControlIp
```

只接受可以严格解析成 IPv4 的 dotted/dash-encoded host；不猜其他 host 结构。

## DataChannel

为了让生成的 Answer 与 GFN 预期的 application m-line 保持一致，收到 Offer 后、创建 Answer 前建立：

```text
input_channel_v1                  ordered
input_channel_partially_reliable unordered / lifetime 来自 Offer
stats_channel                     unordered / no retransmit
```

v5.0 **不实现输入协议**，只建立协商骨架。真实 input packet 在后续 v5.x。

## Video

```text
RtpReceiver
↓
first video packet observer
↓
VideoTrack
↓
GfnVideoSurfaceView
↓
SurfaceViewRenderer
↓
onFirstFrameRendered
```

当前解码工厂：`DefaultVideoDecoderFactory`。

具体真机选择：

```text
hardware MediaCodec
或
software decoder
```

当前不确定，必须用真实 stats/设备结果确认。

## 后续 Main10/HDR

未来不会把 HDR 解码逻辑塞进 v5 H.264 renderer：

```text
stream-webrtc       H264/HEVC bring-up
        │
        └── future direct decoder boundary
            → MediaCodec HEVC Main10
            → SurfaceView
            → HDR10
```

Session 与 Signaling API 保持不变。
