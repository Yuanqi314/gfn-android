# 第三版架构

## 总原则

```text
Compose UI
≠
GFN 内容 API
≠
CloudMatch Session
≠
WebRTC
≠
MediaCodec
```

任何一层失败都不应迫使其他层重写。

## 当前调用链

```text
GfnAndroidApp
│
├── AuthController
│   └── AuthSessionService
│       └── NvidiaAuthApi
│
└── GfnContentController
    ├── GfnAccountClient
    │   ├── provider streamingServiceUrl
    │   ├── /v2/serverInfo
    │   └── mes.geforcenow.com/v4/subscriptions
    │
    └── GfnGamesClient
        ├── Catalog browse
        ├── Library browse
        ├── Search
        └── Persisted-query Game Detail
```

## Auth soft-freeze

v2.1 已经完成真实登录和重启恢复，所以第三版不再重构认证流程。

只新增两个内容层需要的边界：

```text
AuthUiState.SignedIn
    ↓
只读 AuthSession

内容 API HTTP 401
    ↓
AuthController.refreshForApi()
    ↓
最多一次重试
```

内容层不能读取或直接修改 refresh token。

## GFN token

当前与 CloudNow 的选择思路保持一致：

```text
id_token != null
    → GFNJWT id_token
否则
    → GFNJWT access_token
```

真实 endpoint 如果证明某条 API 必须固定使用其中一种，再针对该 API 收窄。

## Provider 与 VPC

登录时 Provider discovery 已存在。

重启恢复后第三版会重新做一次 Provider discovery，因为旧 TokenStore 只持久化 token，不持久化 Provider。

之后：

```text
Provider.streamingServiceUrl
↓
/v2/serverInfo
↓
requestStatus.serverId
↓
VPC ID
```

不采用 CloudNow 的固定欧洲 VPC fallback，避免在亚洲或合作运营商账号上引入错误区域假设。

## Account / Subscription

```text
GET {streamingServiceUrl}/v2/serverInfo
↓
VPC
↓
GET https://mes.geforcenow.com/v4/subscriptions
    serviceName=gfn_pc
    languageCode=<device locale>
    vpcId=<real VPC>
    userId=<real user id>
```

解析：

```text
membershipTier
subType
remainingTimeInMinutes
totalTimeInMinutes
features.resolutions
```

## Catalog / Library

参考 CloudNow 当前 `GamesClient`：

```text
POST https://games.geforce.com/graphql
```

Catalog：

```text
filters = {}
```

Library：

```text
variants.gfn.library.status.notEquals = NOT_OWNED
```

分页：

```text
500 / page
↓
必要时 200 / page retry
```

Library / Catalog 分开加载，UI 不再使用 fixture。

## Game Detail

使用 CloudNow 当前 metadata persisted query：

```text
requestType=appMetaData
sha256Hash=cf8b620d...
variables={vpcId, locale, appIds}
```

metadata 负责：

```text
title
longDescription
genres
developer
publisher
contentRating
images
variants
```

HDR / RTX / Reflex feature 仍以 browse 结果为主，详情 metadata 不凭空推断 feature。

## 下一阶段边界

第四版新增：

```text
gfn-cloudmatch
    ↓
真正 HTTP Client

gfn-session
    ↓
create / queue / ready / stop
```

此时 `gfn-games` 不承担任何 session 逻辑。
