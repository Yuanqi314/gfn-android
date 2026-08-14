# v5.0 真机测试指南

## 前提

必须先获得一个真实 `Claimed` Session，且 UI 已显示真实 signaling URL。

不要同时修改 CloudMatch 参数、HDR、HEVC 或分辨率。

## Test 1 — Android 构建

```text
Android Studio Sync
→ :app assembleDebug
→ 安装真机
```

如果编译失败，保留**第一条 Kotlin/Java compiler error**，不要只提供最后一行 Gradle FAILED。

## Test 2 — WSS

在 Claimed Session 点击：

```text
连接 WebRTC H.264
```

期望：

```text
OpeningSignaling
→ AwaitingOffer
```

Diagnostics：

```text
WSS connected = 是
TX last = peer_info
```

如果失败，只记录：

```text
异常类型
TLS/HTTP 状态
host
```

不要关闭证书校验，也不要先套用 Apple 的 TLS workaround。

## Test 3 — Offer

期望：

```text
RX offer
Offer = 是
Offer H264 PT = 非空
```

如果 H.264 PT 为空，立即停止；v5.0 不回退 HEVC/AV1。

## Test 4 — Answer / NVST

期望：

```text
Answer = 是
Answer H264 PT = 非空
ICE ufrag/pwd = 是/是
DTLS fingerprint = 是
TX last = answer 或 ice
```

不要提供完整 SDP、ICE password、TURN credential；Diagnostics 只需存在性与 PT 列表。

## Test 5 — ICE

真实环境已观察：

```text
Server ICE entries = 0
```

所以重点看：

```text
Effective ICE servers
Local candidates
Remote signaling candidates
Injected host candidates
ICE connection state
Peer connection state
```

理想：

```text
CHECKING → CONNECTED/COMPLETED
```

如果失败，请同时给出两条真实 ConnectionInfo 的：

```text
usage
host
port
resourcePath
```

但不要给 token/TURN password。

## Test 6 — Video

期望顺序：

```text
First RTP packet = 是
Remote video track = 是
First surface frame = 是
```

如果：

```text
ICE Connected + First RTP=否
```

优先看 RTP/服务器 media endpoint。

如果：

```text
First RTP=是 + Remote Track=是 + First Frame=否
```

优先看 H.264 decoder / Surface。

如果：

```text
FIRST FRAME=是
```

记录分辨率，即完成 v5.0 首要 Gate。

## Test 7 — Cleanup

测试结束务必：

```text
断开 WebRTC
→ End Session
```

v4 的 DELETE + active-session GET 复核仍建议单独补测。

## 回报最小信息

```text
媒体状态
WSS connected
RX/TX count + last type
Offer codecs + H264 PT
Answer codecs + H264 PT
Server/Effective ICE count
Local/Remote/Injected candidate count
ICE state
PeerConnection state
First RTP
Remote Track
First Frame + Resolution
错误文本（若有）
```
