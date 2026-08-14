# 第二版架构说明

## 模块边界

```text
app
├── Compose / Material 3 Expressive UI
├── AuthController
└── AndroidKeystoreTokenStore

core-network
├── HttpTransport
├── UrlConnectionHttpTransport
└── NetworkRedaction

gfn-auth
├── NvidiaAuthApi
├── AuthSessionService
├── SimpleJson
└── OAuth 数据模型

gfn-identity
└── GFN 协议身份

gfn-cloudmatch
└── SessionRequest / GFN Header 模型

gfn-session
└── Session 生命周期状态机

diagnostics
└── 跨层诊断状态

stream-core
└── StreamingEngine 抽象
```

## 认证边界

```text
Compose
→ AuthController
→ AuthSessionService
→ NvidiaAuthApi
→ HttpTransport
→ NVIDIA login endpoints
```

`AuthController` 只维护 UI 状态与 generation，不接触 refresh token 内容。`AuthSessionService` 负责登录态生命周期，`NvidiaAuthApi` 负责协议字段，`AndroidKeystoreTokenStore` 负责 Android 加密存储。

## Device Flow 成功后的凭据链

```text
device_code token
    ↓
userinfo
    ↓
client_token
    ↓
client_token grant
    ↓
主 GFN client ID token
    ↓
再次获取 client_token
    ↓
加密保存
```

这是第二版区别于“只拿到 Device Flow access token 就结束”的关键设计。

## 取消与并发

认证任务使用 operation generation：

```text
旧任务 generation N
用户取消 / 新登录
→ generation N+1
→ N 的任何迟到结果都不能覆盖 UI
→ 如果旧任务已经写入 token，则再次清理
```

Android 侧轮询等待使用 coroutine `delay`，可立即响应取消；当前 `HttpURLConnection` 本身仍是阻塞 I/O，因此取消发生在 HTTP 请求期间时，最坏要等连接/读取超时返回。后续换 OkHttp 时可以进一步实现 call-level cancellation。

## 后续串流边界

```text
Stream UI
→ StreamSession
→ StreamingEngine
→ WebRTC signaling / RTP
→ VideoDecoder
   ├── 标准 WebRTC SDR path
   └── Direct MediaCodec → SurfaceView Main10/HDR path
```

Compose 不持有 WebRTC 或 MediaCodec 实例，避免后续 Main10/HDR decoder 变化迫使 UI 重写。
