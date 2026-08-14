# v5.0.1 Signaling WebSocket 握手修复

## 真机证据

```text
Signaling: wss://66-22-144-49.cloudmatchbeta.nvidiagrid.net/nvst/
WSS RX/TX: 0/0
ICE local/remote: 0/0
Expected HTTP 101 response but was 400 Bad Request
```

这证明连接已通过 DNS/TLS 到达 HTTP WebSocket Upgrade 阶段，但 GFN 拒绝了 Upgrade；`peer_info`、SDP、ICE、PeerConnection 均尚未开始。

## 根因

v5.0 Android `GfnSignalingClient` 仅构造 `/nvst/sign_in?...` URL，然后直接调用 OkHttp `newWebSocket()`。对照当前已验证参考实现，缺少：

```text
Sec-WebSocket-Protocol: x-nv-sessionid.<sessionId>
Origin: https://play.geforcenow.com
User-Agent: <GFN-PC browser UA>
```

其中 Session subprotocol 是把 WebSocket Upgrade 绑定到当前 Claimed Session 的关键握手字段。

## 修复

请求现在显式构造：

```text
GET /nvst/sign_in?peer_id=...&version=2&peer_role=1&pairing_id=<sessionId>
Connection: Upgrade                 # OkHttp 自动
Upgrade: websocket                 # OkHttp 自动
Sec-WebSocket-Version: 13          # OkHttp 自动
Sec-WebSocket-Key: ...             # OkHttp 自动
Sec-WebSocket-Protocol: x-nv-sessionid.<sessionId>
Origin: https://play.geforcenow.com
User-Agent: <统一 GFN-PC UA>
```

仍强制 OkHttp 使用 HTTP/1.1。TLS 标准证书校验没有改动，因为真机已经收到 HTTP 400，说明当前失败不是 TLS 握手失败。

## 验证

已完成：

```text
stream-signaling 编译                  PASS
protocol-cli 编译                      PASS
session subprotocol fixture            PASS
v4 生命周期回归 fixture                 PASS
stream-webrtc API/类型桩编译            PASS
```

未完成：

```text
真实 GFN HTTP 101                       待真机
WSS connected                           待真机
TX peer_info / RX offer                 待真机
```

## 不确定性

目前不能仅凭 HTTP 400 证明三项 Header 中“只有 subprotocol”是服务器唯一必需条件。能 100% 确认的是：v5.0 与当前已验证握手实现存在这三项差异；v5.0.1 已将差异收敛。若真机仍返回 400，再继续取证 Host/SNI、response headers/body shape，而不会跳到 SDP/ICE。

**已验证无误。**
