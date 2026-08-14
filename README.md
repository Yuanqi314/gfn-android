# GFN Android Lab · 第五版（v5.0）

这是一个独立 Android GeForce NOW 客户端实验工程。官方客户端只作为协议取证参考，CloudNow 作为已经实现的行为/架构参考；Android 端保持自己的模块边界，不修改 NVIDIA 官方 APK。

> 仅使用用户自己的合法 GeForce NOW 账号，不修改订阅等级、账号 entitlement 或服务端授权。

## 已有真机基础

当前用户已在真实 Android 设备确认：

```text
Device Flow 登录 / 重启恢复       ✅
用户名 / 邮箱                     ✅
会员 / Subscription               ✅
Library / Catalog                 ✅
Server-side Search                ✅
Game Detail                       ✅
CloudMatch Create / Provision     ✅
Resolved Server / Signaling URL   ✅
Claim / RESUME PUT                ✅
```

因此 Auth、Content 与 v4 Session Create/Claim 进入 soft-freeze。没有新的真实协议证据，不因为 v5 媒体问题回头改这些层。

仍未独立真机确认：

```text
Double Ready 日志                 ⏳
DELETE + active-session 复核      ⏳
Cancel cleanup 真机               ⏳
Process-death reconcile           ⏳
```

这些保留为 v4.x 加固项，不改变 v5.0 的媒体边界。

## v5.0 唯一目标

```text
Existing Claimed Session
↓
GFN WSS /nvst/sign_in
↓
peer_info / offer / answer / ICE
↓
PeerConnection
↓
H.264 RTP
↓
libwebrtc decoder
↓
SurfaceViewRenderer
↓
FIRST FRAME
```

v5.0 固定：

```text
Codec       H.264
Color       SDR8
Resolution  1920x1080
FPS         60
Audio       Stereo（仅协商；v5.0 不验证播放）
HEVC        OFF
Main10      OFF
HDR10       OFF
AV1         OFF
120 FPS     OFF
5.1         OFF
```

## v5 新模块

```text
gfn-android
├── app                 Compose UI + Controller
├── core-model          Account/Game/Session/ICE/Connection 模型
├── core-network        HTTP / JSON / 日志脱敏
├── gfn-auth            Device Flow / refresh / client_token / re-bind
├── gfn-account         serverInfo / VPC / MES
├── gfn-games           Catalog / Library / Search / Detail
├── gfn-identity        Windows/GFN-PC identity + locale
├── gfn-cloudmatch      CloudMatch Create/Poll/Claim/Delete [soft-freeze]
├── gfn-session         generation / Queue / Ready / cleanup [soft-freeze]
├── stream-core         媒体状态与诊断模型
├── stream-signaling    纯 JVM GFN WSS envelope / SDP / NVST SDP
├── stream-webrtc       Android OkHttp WSS + libwebrtc H.264 + SurfaceViewRenderer
├── diagnostics         诊断模型
└── protocol-cli        脱敏 fixture 回归
```

核心边界保持：

```text
GFN Session
≠ GFN Signaling Envelope
≠ WebRTC PeerConnection
≠ Decoder
≠ Compose UI
```

## GFN Signaling

当前按 CloudNow 已验证行为实现：

```text
wss://<resolved-server>/nvst/sign_in
?peer_id=<random-peer-name>
&version=2
&peer_role=1
&pairing_id=<session-id>
```

连接后：

```text
TX peer_info
↕ heartbeat / ack
RX peer_msg.msg { type=offer, sdp=... }
RX peer_msg.msg { candidate=... }
TX peer_msg.msg { type=answer, sdp=..., nvstSdp=... }
TX local ICE candidate
```

Diagnostics 只记录 envelope 类型、数量和状态，不打印完整 SDP credential、TURN password、Authorization 或 token。

Android 首先使用标准 TLS 验证和 HTTP/1.1 WebSocket Upgrade。不会因为 Apple 参考实现存在平台特定 TLS 处理，就直接在 Android 关闭证书验证。

## WebRTC / H.264

收到真实 Offer 后才创建 PeerConnection。

```text
Offer
↓
确认 H.264 PT 存在
↓
修正明确的 0.0.0.0 / 127.0.0.1 media 地址占位（有真实 media IP 时）
↓
setRemoteDescription
↓
createAnswer
↓
只在 Answer 收敛 H.264 + RTX/FEC
↓
注入带宽 hint
↓
setLocalDescription
↓
提取本地 ICE ufrag / pwd / DTLS fingerprint
↓
构造 NVST SDP（bitDepth=8）
↓
发送 Answer
```

v5.0 使用 `DefaultVideoDecoderFactory`。具体真机最终选到硬件还是软件 decoder，在拿到实际 WebRTC stats 前标记为“不确定”，不会预先宣称 MediaCodec 硬解。

## ICE=0 的处理

真实 Session 已观察到：

```text
Server ICE entries = 0
```

v5 不把它直接解释成错误，也不自动塞公共 STUN。

```text
Server ICE entries  = CloudMatch 原始值
Effective ICE       = 实际 PeerConnection 配置
```

当 Offer/Answer 完成后，客户端会基于真实 `ConnectionInfo` 和 Offer 的 video m-line 端口注入服务器 host candidate；优先级参考当前已实现行为：

```text
usage=2
↓
usage=17
↓
usage=14（最高有效端口 fallback）
```

是否需要额外 STUN/TURN fallback 必须由真机 ICE 结果决定。

## v5 Diagnostics

会话/诊断页新增：

```text
SIGNALING
- WSS connected
- endpoint host
- RX/TX count
- last RX/TX type
- close code/reason

SDP
- Offer/Answer present
- codec list
- H.264 payload types
- ICE ufrag/pwd 是否存在
- DTLS fingerprint 是否存在

ICE / PeerConnection
- Server ICE entries
- Effective ICE servers
- fallback active
- local/remote signaling candidates
- injected host candidates
- signaling/ICE/PC state

VIDEO
- Remote video track
- First RTP packet
- First surface frame
- first-frame resolution
- decoder path（未真机确认前不假设硬件）
```

## 依赖

```text
OkHttp                  5.3.0
io.github.webrtc-sdk    android:144.7559.09
```

`stream-webrtc` 使用直接 libwebrtc API，不引入 LiveKit Room/服务端协议。

## 当前验证

```bash
./verify-core.sh
```

纯 Kotlin fixture 已覆盖：

```text
v4 Create → Queue → Preparing → Ready → Claim → End
v4 stale create cleanup
v5 /nvst/sign_in URL
v5 peer_info / ACK / heartbeat / offer / ICE envelope
v5 H.264 Answer 收敛
v5 NVST SDP
Auth / Content 回归
```

另外，本环境执行了基于当前官方 WebRTC/OkHttp API 形状的 Android 媒体源码类型检查，以及 Compose Kotlin parser 检查。

**限制：当前容器没有 Android SDK，也无法在这里完成最终 AGP + libwebrtc AAR + Compose APK 构建。完整 Android 编译和真实 WSS/WebRTC/First Frame 必须以联网 Android Studio + 真机为准。**

## 下一步真机判定顺序

```text
1. WSS connected?
2. RX peer_info / offer?
3. Offer H.264 PT?
4. Answer set/sent?
5. ICE CHECKING → CONNECTED?
6. First video RTP?
7. Remote VideoTrack?
8. FIRST FRAME?
```

这八层中的第一处失败点，就是下一轮需要分析的位置。
