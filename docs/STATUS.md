## v5.0.1 真机握手修复

- v5.0 真机：`wss://<resolved-server>/nvst/` TLS/HTTP 已到达 GFN，WebSocket Upgrade 返回 `HTTP 400 Bad Request`，RX/TX 均为 0。
- 根因证据：Android OkHttp v5.0 只构造了 sign_in URL，没有发送当前已验证实现使用的 `Sec-WebSocket-Protocol: x-nv-sessionid.<sessionId>`、`Origin: https://play.geforcenow.com` 和 GFN-PC User-Agent。
- v5.0.1：三项握手字段已补齐；仍强制 HTTP/1.1；未修改 TLS 校验策略。
- 当前证据等级：**代码/fixture 已修复，真机 WSS 101/Connected 待验证。**

# 第五版状态（v5.0.1）

## 真机已确认

```text
Auth / restart restore             ✅
Membership                         ✅
Library / Catalog                  ✅
Search / Game Detail               ✅
CloudMatch Create                  ✅
真实 Session ID                    ✅
Provision / GPU / resolved server  ✅
ConnectionInfo                     ✅（真实观察到 2 条）
Server ICE entries                 ✅（真实观察到 0 条）
Signaling URL                      ✅
Claim / RESUME PUT                 ✅
```

因此目前已有真实证据支持：GFN Session 已经到达媒体连接边界。

## v4 soft-freeze

没有新的真机协议证据时不修改：

```text
Device Flow / client_token
Catalog / Variant resolution
CloudMatch Create body
Session identity / headers
Queue semantics
Claim / minimal RESUME
resolved-server handling
```

仍保留为 v4.x 加固：

```text
Double Ready 独立日志              ⏳
DELETE + GET active-session 复核   ⏳
Cancel cleanup 真机                ⏳
Process-death reconcile            ⏳
Queue Ad 真机                      ⏳
```

## v5.0 已实现（源码）

```text
stream-signaling JVM 模块
GFN /nvst/sign_in URL builder
peer_info / ack / heartbeat
Offer / ICE decode
Answer / ICE encode
H.264 Answer filtering
SDP bandwidth hints
ICE credential / DTLS fingerprint extraction
NVST SDP（SDR8）

OkHttp HTTP/1.1 WSS transport
PeerConnectionFactory
Unified Plan PeerConnection
Server-provided ICE list（允许 0）
DataChannel skeleton
Remote/Local ICE buffering
ConnectionInfo host candidate injection
RtpReceiver first-packet observer
VideoTrack → SurfaceViewRenderer
FIRST FRAME callback
v5 Diagnostics UI
```

## v5.0 未真机验证

```text
完整 Android/Compose Gradle build       ⏳
GFN WSS HTTP Upgrade                    ⏳
真实 peer_info / offer 顺序             ⏳
真实 Offer 的 H.264 PT                  ⏳
setRemoteDescription                    ⏳
create/set Answer                       ⏳
NVST SDP 被服务器接受                   ⏳
ICE candidate pair                      ⏳
PeerConnection Connected                ⏳
H.264 RTP                               ⏳
实际 decoder 实现                       ⏳
Surface First Frame                     ⏳
```

## 当前本地验证

```text
stream-signaling 分模块编译             PASS
v5 signaling/SDP fixture                PASS
stream-webrtc API-shaped 类型检查       PASS
GfnStreamingController 类型检查         PASS
Compose Kotlin parser                   PASS
v4/Auth/Content fixture 回归             PASS
```

注意：`API-shaped 类型检查` 使用根据当前官方 API 签名构造的最小编译桩，只用于发现 Kotlin 语法、构造参数、override 和模块边界问题；它**不等价于真实 Android AAR/SDK 编译**。

## v5.0 成功标准

```text
Claimed Session
→ WSS connected
→ real Offer
→ H.264 Answer
→ ICE connected
→ first video RTP
→ remote VideoTrack
→ FIRST FRAME on Surface
```

完成这个 Gate 之后才进入 v5.x Audio / Input / Reconnect，再之后才进入 HEVC/Main10/HDR。
