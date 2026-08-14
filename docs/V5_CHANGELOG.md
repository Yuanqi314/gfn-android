# v5.0 变更说明

## 新增

- `stream-signaling`：纯 JVM GFN WebSocket envelope、SDP、NVST SDP。
- `stream-webrtc`：Android OkHttp WebSocket + direct libwebrtc。
- Claimed Session → “连接 WebRTC H.264”入口。
- `SurfaceViewRenderer` 视频输出。
- Signaling / SDP / ICE / Video 分层 Diagnostics。
- First RTP / First Surface Frame 标志。

## 协议策略

- v4 CloudMatch/Create/Claim soft-freeze。
- WSS 使用 `/nvst/sign_in` + pairing/session 参数。
- 发送 `peer_info`、ACK、heartbeat。
- 接收 server Offer/ICE。
- Answer 只允许 H.264。
- NVST SDP 固定 bitDepth=8、1920x1080、60 FPS。
- Server ICE=0 不等于失败；不自动启用公共 STUN。
- 远端 host candidate 从真实 ConnectionInfo / video m-line 推导。

## 本轮编译问题修正

验证过程中已发现并纠正：

1. `SessionInfo.signalingUrl` 跨模块 nullable property：改用局部稳定快照。
2. `flushRemoteIce()` 重复声明 `val pc`：删除重复声明。
3. `GfnSignalingClient` trailing lambda 误绑定最后一个 `OkHttpClient` 参数：改成显式 `listener = {}`。
4. `protocol-cli` 漏声明 `:stream-signaling` Gradle 依赖：已补齐。
5. decoder Diagnostics 不再预先宣称 MediaCodec 硬解。
6. partial-reliable DataChannel lifetime 改为从真实 Offer 的 `ri.partialReliableThresholdMs` 读取，而不是固定猜测。
7. media ConnectionInfo 选择改为 `usage 2 → 17 → usage 14 最高有效端口`，避免把任意 control/signaling port 全部当作 media candidate。

## 未改变

- Auth / KeyStore
- Account/MES
- Library/Catalog/Search/Detail
- CloudMatch Create body
- Queue/Ready semantics
- Claim/RESUME body
- Session identity
