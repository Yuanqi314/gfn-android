# GFN Android Lab · 第四版（v4）

这是一个独立 Android GeForce NOW 客户端实验工程。

当前路线不修改 NVIDIA 官方 APK。官方客户端用于协议取证，CloudNow 用作已经实现的 clean-room 行为/架构参考；Android 客户端保持自己的模块边界和实现。

```text
真实 Android runtime
+ Kotlin / Jetpack Compose / Material 3 Expressive
+ NVIDIA Device Flow
+ Windows / GFN-PC 协议身份
+ Account / Subscription / Catalog / Library / Search / Detail
+ CloudMatch Session Lifecycle
+ 后续 WebRTC / MediaCodec / Main10 / HDR10
```

> 仅使用用户自己的合法 GeForce NOW 账号，不修改订阅等级、账号 entitlement 或服务端授权。

## 当前真机基础

v4 建立在 v3.0.1 已经真实验证的基础上：

```text
Device Flow 登录                 ✅
用户名 / 邮箱                    ✅
重启恢复登录态                   ✅
会员 / Subscription              ✅
真实 Library                     ✅
真实 Catalog                     ✅
服务端 Search                    ✅
Game Detail                      ✅
```

这些层进入 soft-freeze。除非出现真实回归，不因 Session 开发重构 Auth / Content。

## 第四版目标

v4 **只做 Session Lifecycle，不创建 WebRTC PeerConnection，也不解码视频**：

```text
Game Detail
↓
选择真实 GameVariant
↓
CloudMatch POST /v2/session
↓
Queue
↓
Preparing
↓
连续两次 Ready
↓
Claim / Resume PUT
↓
DELETE End / Cleanup
```

成功标准不是“HTTP 200”，而是 Session 可以重复创建、排队、确认 Ready、恢复和清理。

## v4 关键实现

### 1. 正确的启动 App ID

内容模型新增：

```text
GameVariant.id
GameVariant.appId
GameVariant.launchAppId = appId ?: id
```

GraphQL 中后端 selected variant 优先；否则 owned variant 优先。数值 variant id 记录为 CloudMatch `appId`。

### 2. CloudMatch 请求身份集中管理

```text
clientIdentification = GFN-PC
clientPlatformName    = windows
nv-device-os          = WINDOWS
nv-device-type        = DESKTOP
nv-device-make        = UNKNOWN
nv-device-model       = UNKNOWN
nv-client-type        = NATIVE
nv-client-streamer    = NVIDIA-CLASSIC
```

每个 Session lifecycle 使用随机 `nv-client-id`，`x-device-id` 在本机 `noBackupFilesDir` 中保持稳定。

### 3. v4 强制 SDR8

为了隔离变量，第 4 版创建 Session 时固定：

```text
sdrHdrMode = 0
requestedStreamingFeatures.bitDepth = 0
clientDisplayHdrCapabilities = null
```

v4 不验证 HDR/Main10。真正媒体链在后续版本单独进入。

### 4. Queue / Ready

```text
Queue：无限等待
↓
离开 Queue
↓
setup timeout = 180 秒
↓
Ready 必须连续观察 2 次
```

恢复/Claim 路径只要求 1 次 Ready 确认。

### 5. Resume 是最小 PUT

Claim/Resume 使用：

```text
PUT /v2/session/{sessionId}
action = 2
data   = RESUME
```

Resume body 不重发：

```text
clientRequestMonitorSettings
requestedStreamingFeatures
HDR capabilities
physical-resolution metadata
```

避免对已配置的服务端 Session 重新协商参数。

### 6. 取消与迟到结果

`SessionOrchestrator` 使用 generation：

```text
Cancel while Creating
↓
旧 POST 稍后返回一个 Session
↓
generation 已变化
↓
拒绝旧结果
↓
DELETE stale Session
```

同样保护 Queue poll / Claim 的旧结果。

### 7. 401 single-flight

Auth 层增加 Mutex + rejected-token 检查：

```text
多个 API 同时 401
↓
只有一个 refresh
↓
其他请求复用已更新凭据
↓
每个请求最多重试一次
```

HTTP 403 不解释为 token 过期。

### 8. Queue Ad 边界

CloudMatch 如果要求必须观看 Queue Ad，而 v4 尚未实现播放器：

```text
识别 Queue Ad
↓
停止继续隐藏轮询
↓
best-effort DELETE Session
↓
UI 明确显示“不支持 Queue Ad”
```

如果 DELETE 失败，则保留本地 resume 数据，允许用户再次手动 Cleanup。

## v3 内容层低风险加固

不重写已经真机成功的内容层，只补：

```text
401 single-flight
cursor 循环保护
hasNextPage / endCursor 完整性检查
达到 maxPages 但仍有下一页 → Protocol error
PersistedQueryNotFound → ProtocolDrift
Provider 启动时 rediscovery
```

因此 `maxPages` 只作为安全上限，不再被当作“Catalog 已完整加载”的成功标志。

## 模块

```text
gfn-android
├── app                 Compose UI + Auth/Content/Session Controller
├── core-model          Game / Account / Session / ICE / Connection 模型
├── core-network        HTTP 抽象、JSON、日志脱敏
├── gfn-auth            Device Flow / refresh / client_token / re-bind
├── gfn-account         serverInfo / VPC / MES
├── gfn-games           Catalog / Library / Search / Detail
├── gfn-identity        Windows/GFN-PC identity + locale
├── gfn-cloudmatch      真实 CloudMatch Create/Poll/Claim/Delete
├── gfn-session         generation / Queue / Ready / cleanup
├── diagnostics         诊断模型
├── stream-core         后续媒体抽象
└── protocol-cli        脱敏 fixture 回归
```

## 本地验证

```bash
./verify-core.sh
```

当前核心 fixture 已覆盖：

```text
CloudMatch Create
Queue 5 → 2
Preparing
resolved-server re-poll
双 Ready
Claim / RESUME PUT
DELETE End
Cancel while Creating → late create cleanup

以及原有：
Device Flow
登录态恢复
Provider 恢复
MES
Library
Catalog
Game Detail
```

详见：

```text
docs/V4_SMOKE_OUTPUT.txt
```

## 当前环境限制

当前容器没有 Android SDK，也没有可用的 Android/Compose 依赖缓存，因此不能在这里声称 APK 已完整构建。

已完成：

- 纯 Kotlin 全核心真实编译；
- `GfnSessionController + AndroidSessionPersistence` 使用最小 Android/Coroutines 类型桩做 Kotlin 类型检查；
- Auth/Content Controller 同样做类型检查；
- Compose 源码做 parser/作用域 import 检查；
- 未重新引入错误的 `foundation.layout.weight` 显式 import。

最终 Android/Compose 构建与真实 CloudMatch 结果仍以联网 Android Studio + 真机为准。

## 下一阶段

v4 真机 Session lifecycle 稳定后，v5 才进入：

```text
Signaling
→ SDP
→ WebRTC
→ H.264 First Frame
→ Audio
→ Controller
```

之后：

```text
HEVC Main SDR8
→ HEVC Main10 SDR10
→ HDR10 / BT.2020 / ST2084
```
