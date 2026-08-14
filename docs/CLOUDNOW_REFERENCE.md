# CloudNow 参考记录 · 第三版

CloudNow 是第三版的“已实现协议参考”，不是 Android 代码模板。

## 第三版重点参考文件

### `CloudNow/Session/GamesClient.swift`

参考内容：

```text
games.geforce.com/graphql
Catalog browse
Library filter
Search
分页
VPC discovery
metadata persisted query
HDR / RTX / Reflex feature flag 解析
```

我们采用：

```text
Library status != NOT_OWNED
Catalog / Library 分离
GraphQL cursor pagination
500 page → 200 page retry
genres 不兼容 fallback
metadata hash / appMetaData 请求语义
```

没有直接照搬：

```text
CloudNow 的 Swift cache actor
完整 metadata cache 策略
public catalog merge
Favorites
Library sync coordinator
```

### `CloudNow/Session/MESClient.swift`

参考：

```text
/v2/serverInfo → VPC ID
mes.geforcenow.com/v4/subscriptions
membership tier
entitled resolutions
```

我们的差异：

CloudNow 在 VPC discovery 失败时有固定 VPC fallback；第三版不采用该行为，因为当前 Android 客户端需要优先保持真实 provider / 区域，不做欧洲区域假设。

### `CloudNow/Auth/AuthManager.swift`

继续参考：

```text
id_token 优先 / access_token 回退
401 后 refresh
credential generation
```

认证层仍保持 soft-freeze，不因内容 API 开发反复改写。

## 后续继续参考

第四版重点继续看：

```text
CloudMatchClient.swift
SessionOrchestrator.swift
SessionReadinessTracker / SessionState
ServerInfo / region / network test
```

串流阶段重点：

```text
GFNStreamController.swift
SDP munger
GFNVideoDecoderFactory.swift
GFNVideoDecoderH265.swift
```

## 原则

```text
参考协议行为
参考状态机设计
参考失败恢复策略

≠
逐行翻译 Swift
≠
复制 Apple 平台设备假设
≠
复制 tvOS renderer
```
