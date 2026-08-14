# 第四版架构

## 总边界

```text
Compose UI
≠
Auth
≠
GFN Content
≠
CloudMatch Session
≠
WebRTC
≠
MediaCodec
```

第 4 版只新增 Session 层，不把媒体逻辑塞进 CloudMatch。

## 调用链

```text
GfnAndroidApp
│
├── AuthController               [soft-freeze]
│   └── AuthSessionService
│
├── GfnContentController         [soft-freeze]
│   ├── GfnAccountClient
│   └── GfnGamesClient
│
└── GfnSessionController         [v4]
    ├── AuthRefreshingCloudMatchPort
    │   └── GfnCloudMatchClient
    ├── SessionOrchestrator
    ├── AndroidStableDeviceId
    └── AndroidSessionRecordStore
```

## Auth 与 401

内容和 Session 都不能直接操作 refresh token。

```text
API request(token=A)
↓
HTTP 401
↓
AuthController.refreshForApi(rejectedToken=A)
↓
Mutex
├─ token 仍是 A → 真正 refresh
└─ token 已变 B → 直接复用 B
↓
request retry exactly once
```

403 不进入 refresh。

## GameVariant 到 Session

GraphQL 顶层 `GameSummary.appId` 不能直接假设等于 CloudMatch 启动 ID。

```text
GameDetail
↓
variants
↓
selected variant 优先
否则 owned variant 优先
↓
variant.launchAppId
```

```text
launchAppId = variant.appId ?: variant.id
```

当前 browse 中数值 variant id 会记录到 `appId`。

## CloudMatch lifecycle identity

每次新 Session：

```text
lifecycle clientId = random UUID
x-device-id        = stable local UUID
```

Header 由 `GfnRequestContext` 集中构造：

```text
Authorization       GFNJWT <token>
nv-client-id        <lifecycle UUID>
nv-client-type      NATIVE
nv-client-streamer  NVIDIA-CLASSIC
nv-device-os        WINDOWS
nv-device-type      DESKTOP
nv-device-make      UNKNOWN
nv-device-model     UNKNOWN
x-device-id         <stable UUID>
```

Session JSON：

```text
clientIdentification = GFN-PC
clientPlatformName    = windows
```

## Create

```text
POST {providerBase}/v2/session
    ?keyboardLayout=...
    &languageCode=...
```

v4 固定：

```text
1920x1080 以下的账号已授权分辨率
<= 60 FPS
SDR8
stereo
appLaunchMode = 1 (Default)
```

不把 CloudNow 的 tvOS Big Picture 默认值直接复制到 Android。

## Queue 与 Ready

`SessionReadinessTracker`：

```text
seatSetupStep == 1
OR queuePosition > 1
    → InQueue
```

Queue 中：

```text
不启动 180 秒 setup timeout
连续 Ready 计数归零
```

离开 Queue 后：

```text
start setup clock
↓
status 2/3 连续出现 2 次
↓
Ready
```

## resolved server

Provider endpoint 可能先返回具体 server host。

```text
poll provider base
↓
status 2/3 + resolved server
↓
再 GET https://<resolved-server>/v2/session/{id}
↓
取得最终 connectionInfo / ICE
```

## Claim / Resume

恢复前先 GET 当前状态。

仍在 Queue：

```text
返回 Queue 状态，不发送 RESUME
```

Ready status 2/3：

```text
PUT /v2/session/{id}
{
  action: 2,
  data: "RESUME",
  sessionRequestData: <minimal>
}
```

Resume 不重新发送 monitor / HDR / requestedStreamingFeatures。

## End / cleanup

```text
DELETE /v2/session/{id}
```

`SessionOrchestrator` 维护 owned session，并保证同一 Session 不重复 stop；DELETE 失败时撤销本地 stop 标记，以便再次重试。

## stale result

```text
Attempt generation N
↓
用户取消
↓
generation N+1
↓
旧 create 返回 session S
↓
N 已 stale
↓
DELETE S
↓
拒绝 UI 更新
```

## 本地 resume 数据

只保存非凭据数据：

```text
sessionId
appId
game/store
status
serverIp
base/routingZone
clientId/deviceId
createdAt
```

文件位于 `noBackupFilesDir`。

Token 永远由 Auth / AndroidKeyStore 管理，不写入 Session record。

## Queue Ad

CloudNow 已经实现 Queue Ad 播放与事件上报，但 v4 暂不引入这一层。

当前策略：

```text
发现 sessionAdsRequired / isAdsRequired / queuePaused / ad payload
↓
抛出明确“不支持”状态
↓
best-effort DELETE
```

避免无限隐藏轮询或留下 Session。

## v5 接口

v4 Ready 后仅展示：

```text
server
signalingUrl
connectionInfo
ICE
streamingProfile
```

不会创建 PeerConnection。

v5 再将 `SessionInfo` 交给：

```text
StreamingEngine
↓
WebRTC signaling / SDP
↓
H.264 First Frame
```
