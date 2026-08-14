# 架构说明

## 模块边界

```text
app
├── MD3E / Compose UI
├── AuthController
└── AndroidKeystoreTokenStore

core-network
└── HttpTransport + UrlConnectionHttpTransport + 日志脱敏

gfn-auth
├── NvidiaAuthApi
├── AuthSessionService
└── OAuth 数据模型

gfn-identity
└── GFN 协议身份

gfn-cloudmatch
└── SessionRequest / GFN Header 模型

gfn-session
└── Session 生命周期状态机

stream-core
└── StreamingEngine 抽象
```

## 认证链

```text
Compose
→ AuthController
→ AuthSessionService
→ NvidiaAuthApi
→ HttpTransport
→ NVIDIA login endpoints
```

Token 不允许进入 UI 日志；本地保存时使用 AndroidKeyStore AES-GCM。

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
