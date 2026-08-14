# 第四版变更说明

## 基线

以 v3.0.1 为代码基线。

v3.0.1 真机已经确认：

```text
Auth / 重启恢复
Subscription
Library
Catalog
Search
Game Detail
```

这些能力未被重写。

## 新增

### Session 模型

新增/扩展：

```text
GameVariant.appId / launchAppId
SessionInfo
SessionConnectionInfo
IceServer
SessionClaimRequest
SessionAdRequirement
```

### `gfn-cloudmatch`

新增真实：

```text
Create
Poll
Claim / Resume
Delete
CloudMatch response parser
Signaling URL parser
ICE parser
StreamingProfile parser
Queue Ad detection
```

### `gfn-session`

新增：

```text
generation
Queue/Preparing/Ready reducer
post-queue timeout
stale create cleanup
owned session cleanup
```

### Android app

新增：

```text
GfnSessionController
AndroidStableDeviceId
AndroidSessionRecordStore
“会话”页面
Game Detail 的 Variant Session 按钮
Session diagnostics
```

### Auth / Content 加固

新增：

```text
401 single-flight refresh
严格 GraphQL pagination
cursor cycle protection
ProtocolDrift
```

## 明确未加入

```text
WebRTC
H.264 decode
Audio
Input
HEVC
Main10
HDR
Queue Ad player
```

## 验证

纯 Kotlin `./verify-core.sh` 已通过：

```text
Create → Queue → Preparing → 双 Ready → Claim → End
Cancel while Creating → stale cleanup
```

Session Android Controller 另外用最小 Android/Coroutines 类型桩做 Kotlin 类型检查通过。

完整 Android/Compose APK 仍需真实 Android Studio 环境编译验证。
