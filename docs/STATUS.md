# 第三版状态


## v3.0.1 编译修复

已修复 Compose `weight()` 编译错误：删除 `import androidx.compose.foundation.layout.weight`，保留 `Row {}` 内的 `Modifier.weight(1f)`，由 `RowScope.weight()` 公开 API 解析。

全工程同类作用域 API 显式导入扫描结果：未发现 `weight / align / alignBy / alignByBaseline / matchParentSize` 的其他错误显式 import。

## 真机已经确认

以下来自当前 Android 真机测试：

```text
NVIDIA Device Flow 登录             通过
用户名 / 邮箱显示                   通过
AndroidKeyStore 保存                通过
应用重启后登录态恢复                通过
```

重启时会短暂显示恢复状态，随后正常进入已登录状态。

## 第三版已实现

```text
AuthSession 向内容层提供只读凭据快照
Provider discovery 在重启恢复后重新建立
GFN token 选择：id_token 优先，access_token 回退
401 时允许一次认证 refresh 后重试

serverInfo / VPC
MES subscription
Catalog GraphQL
Library GraphQL
Search GraphQL
Game Detail persisted query

首页内容概览
真实 Library 页面
真实 Catalog 页面
服务端搜索
真实 Game Detail
内容服务诊断
```

## 当前核心 fixture 已通过

```text
Provider discovery
Device Flow
authorization_pending
OAuth token
userinfo
client_token
main-client re-bind
登录恢复 401 → refresh
Provider 恢复
serverInfo → VPC
MES Subscription
Library
Catalog
Game Detail
```

最新输出：

```text
docs/V3_SMOKE_OUTPUT.txt
```

## 尚未实现

```text
Library sync orchestration
Catalog 本地缓存 / Room
图像下载缓存
Favorites
Regions / ServerInfo UI
NetTest
CloudMatch Create
Queue
Ready / Claim / Resume / End
WebRTC signaling
H.264 video
Audio
Controller input
HEVC
Main10
HDR10
```

## 不确定 / 需要真机验证

第三版内容请求结构主要参考 CloudNow 当前公开实现，但以下仍必须由真实 GFN endpoint 结果确认：

```text
当前用户所在区域的 serverInfo 响应结构
MES 在当前账号 / provider 下的实际字段
GraphQL browse 在当前区域是否接受 500 page size
当前 GraphQL 是否仍接受 genres 字段
persisted-query metadata hash 是否仍有效
id_token / access_token 在当前 API 上的实际接受范围
```

代码已经对部分兼容情况做保护：

```text
500 page size 无结果 / 特定 4xx → 200 page retry
genres GraphQL error → 无 genres query retry
401 → 认证层 refresh 一次后重试
403 不自动当作 token 过期
```

## 下一版目标

```text
第四版：CloudMatch / Session lifecycle

Regions
ServerInfo
CloudMatch Create
Queue
Ready
Claim
Resume
End
```

第四版仍不要求 HDR；成功标准是：

```text
选择一个真实 Library 游戏
→ 创建真实 session
→ 正确显示排队位置
→ 到 Ready
→ 可以正常 End Session
```
