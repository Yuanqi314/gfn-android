# GFN Android Lab · 第三版

这是一个独立的 Android GeForce NOW 客户端实验工程。

当前路线不再修改 NVIDIA 官方 APK，而是把官方客户端作为协议行为参考，把 CloudNow 作为已实现的第三方客户端架构/协议参考，在 Android 上重新实现：

```text
真实 Android runtime
+ Kotlin / Jetpack Compose / Material 3 Expressive
+ NVIDIA Device Flow
+ Windows / GFN-PC 协议身份
+ 独立 Account / Subscription / Catalog / Library
+ 后续 CloudMatch / WebRTC / MediaCodec
```

> 本项目仅使用用户自己的合法 GeForce NOW 账号，不修改订阅等级、账号 entitlement 或服务端授权。

## 第三版新增

第三版在 v2.1 已经真机验证的登录与重启恢复基础上，新增真实内容 API：

```text
登录 / 恢复
↓
Provider streamingServiceUrl
↓
/v2/serverInfo
↓
VPC ID
↓
┌─────────────────────────────┐
│ MES /v4/subscriptions       │
│ games.geforce.com/graphql   │
└─────────────────────────────┘
↓
Subscription
Library
Catalog
Search
Game Detail
```

UI 中：

- 首页显示真实会员、VPC、Library/Catalog 数量；
- 游戏库页使用真实账号 Library；
- “全部游戏”页使用真实 GFN GraphQL Catalog；
- 搜索走服务端 GraphQL；
- 游戏详情走 CloudNow 当前使用的 persisted-query metadata 请求；
- 诊断页显示内容服务状态；
- 尚未实现 CloudMatch Create / Queue / WebRTC 串流。

## 模块

```text
gfn-android
├── app                 Android / Compose UI 与 controller
├── core-model          跨模块数据模型
├── core-network        HTTP 抽象、脱敏、纯 Kotlin JSON
├── gfn-auth            Device Flow / refresh / client_token / re-bind
├── gfn-account         serverInfo / VPC / MES Subscription
├── gfn-games           Catalog / Library / Search / Game Detail
├── gfn-identity        Windows / GFN-PC 协议身份与协议常量
├── gfn-cloudmatch      SessionRequest 模型（下一阶段继续扩展）
├── gfn-session         Session lifecycle 状态机
├── diagnostics         诊断模型
├── stream-core         串流抽象
└── protocol-cli        脱敏 fixture / 回归验证
```

## 第三版当前验证状态

已经在当前构建环境真实执行：

```text
./verify-core.sh
```

并验证：

```text
Device Flow fixture
Provider discovery
client_token re-bind
登录态 401 → refresh → userinfo retry
Provider 恢复
serverInfo → VPC
MES Subscription
GraphQL Library
GraphQL Catalog
Persisted-query Game Detail
```

Android APK 在本环境无法完整编译，因为容器无法解析 `services.gradle.org`，无法下载 Gradle 9.5.0；因此 Android/Compose 最终编译仍需要在联网 Android Studio 环境完成。

## 真机优先验证

见：

```text
docs/V3_TEST_GUIDE.md
```

第三版第一次真机测试最重要的不是串流，而是确认：

```text
重启恢复
→ 自动加载 VPC
→ 正确显示会员
→ Library 有真实游戏
→ Catalog 有真实游戏
→ 搜索正常
→ 游戏详情正常
```

## 下一阶段

第三版内容层稳定后进入第四版：

```text
Regions / ServerInfo
↓
CloudMatch Create
↓
Queue
↓
Ready
↓
Claim / Resume / End
↓
WebRTC Signaling
↓
H.264 first frame
```

Main10/HDR 仍保持后置：

```text
H.264 SDR
→ HEVC Main SDR8
→ HEVC Main10 SDR10
→ HDR10 / BT.2020 / ST2084
```
