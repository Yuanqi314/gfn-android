# CloudNow 参考记录 · 第四版

CloudNow 是协议与状态机参考，不是 Android 代码模板。

## v4 重点参考

### `CloudNow/Session/CloudMatchClient.swift`

当前公开实现用于确认：

```text
POST /v2/session
GET  /v2/session/{id}
PUT  /v2/session/{id}  action=2 / RESUME
DELETE /v2/session/{id}
```

以及：

```text
GFN-PC / windows identity
随机 lifecycle clientId
稳定 deviceId
Queue / seatSetupInfo
connectionInfo usage=14 signaling
ICE servers
resolved server re-poll
phantom session cleanup
```

Android v4 采用这些协议行为，但不复制 Apple 平台实现。

### Resume body

CloudNow 当前实现明确把 RESUME 做成最小请求，不重新发送：

```text
monitor settings
requestedStreamingFeatures
HDR capabilities
physical-resolution metadata
```

Android v4 同样保持最小 Resume。

### `CloudNow/Session/SessionOrchestrator.swift`

参考：

```text
generation
stale callback rejection
late-created session cleanup
Queue 无限等待
post-queue setup timeout
连续 Ready
owned-session stop once
```

Android 实现没有逐行翻译 Swift，而是在纯 Kotlin core 中建立同样的生命周期不变量。

### `CloudNow/UI/StreamView.swift`

用于确认启动 ID 的选择方式：

```text
game.variants.first?.appId
?? game.variants.first?.id
```

以及实际流程：

```text
Create / Claim
↓
waitUntilReady
↓
然后才进入 WebRTC
```

Android v4 停在 Ready/Claim，不执行最后的 WebRTC 步骤。

### `CloudNow/Session/GamesClient.swift`

继续用于确认 GraphQL variant 顺序和 library 状态。

v4 加固：

```text
selected variant 优先
owned variant 次优先
数值 variant id 作为 launch appId
cursor cycle protection
strict completion
PersistedQueryNotFound → ProtocolDrift
```

## 我们没有照搬的 CloudNow 决策

```text
tvOS Big Picture 默认值
Apple VideoToolbox renderer
Queue Ad 播放 UI（v4 暂不实现）
固定欧洲 VPC fallback
Swift actor / Observation 架构
WebRTC decoder（v5 才进入）
```

## 后续继续参考

v5：

```text
GFNStreamController.swift
WebRTC signaling
SDP munger
```

Main10/HDR：

```text
GFNVideoDecoderFactory.swift
GFNVideoDecoderH265.swift
```

原则始终是：

```text
参考已经验证的协议行为
+ 保留 Android 自己的模块设计
+ 真机结果优先
```
