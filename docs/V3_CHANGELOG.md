# 第三版变更说明

## 从 v2.1 到 v3

### 认证

- 保留已经真机验证的 Device Flow / AndroidKeyStore；
- 重启恢复后补做 Provider discovery，恢复 `streamingServiceUrl`；
- `AuthUiState.SignedIn` 内部持有只读 `AuthSession`；
- 新增内容 API 遇到 HTTP 401 时“一次 refresh + 一次重试”；
- GFN API token 选择：优先 `id_token`，回退 `access_token`；
- OAuth `display_name` 默认改为 Android，与 streaming identity 完全分离。

### Account / Subscription

新增 `gfn-account`：

```text
/v2/serverInfo
→ VPC ID
→ MES /v4/subscriptions
```

支持解析会员等级、时长和服务端已授权分辨率 / FPS。

### Catalog / Library

新增 `gfn-games`：

```text
Catalog
Library
Search
Game Detail
```

Library 使用服务端 `status != NOT_OWNED` 过滤；Game Detail 使用当前 CloudNow 的 app metadata persisted query 语义。

### UI

保留原来的 Compose / MD3E 视觉基线，仅把 fixture 替换为真实数据：

- 首页真实内容状态；
- 真实游戏库；
- 新增全部游戏；
- 服务端搜索；
- 游戏详情；
- 内容服务诊断；
- 所有交互按钮都有实际行为或明确标记为下一阶段。

### 尚未包含

```text
CloudMatch Create
Queue
Ready
WebRTC
H.264
Audio
Controller
HEVC / Main10 / HDR
```

这些不在第三版完成范围内。

## v3.0.1 编译修复

- 删除 `GfnAndroidApp.kt` 中 `import androidx.compose.foundation.layout.weight`。
- 保留 `CatalogScreen` 的 `Modifier.weight(1f)`；该调用位于 `Row {}` 内容作用域中，使用公开 `RowScope.weight()`。
- 全工程扫描未发现其他同类 scope API 错误显式 import。
- `./verify-core.sh` 回归通过；协议、认证、Account、MES、Library、Catalog、Game Detail fixture 均未受影响。
- 本修复不改变第三版协议与 UI 方案。
