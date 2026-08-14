# 第三版真机测试指南

## 0. 安装

如果继续使用同一个 applicationId 和同一个签名 key，优先覆盖安装，以验证 v2.1 登录态能否被第三版直接恢复。

如果签名不同，Android 会拒绝覆盖，此时只能卸载后重新登录。

## 1. 启动恢复

预期：

```text
启动
→ “正在恢复本地登录状态”短暂显示
→ 用户名 / 邮箱恢复
→ 自动开始加载 GFN 内容
```

## 2. 首页内容服务

成功后应显示：

```text
会员等级
VPC ID
Library 数量
Catalog 数量
最高已授权分辨率 / FPS（MES 有数据时）
```

如果失败，请记录页面错误文本和：

```bash
adb logcat -s GfnAuth GfnContent
```

## 3. 游戏库

进入“游戏库”。

预期：

```text
不再出现 fixture-1 / fixture-2
显示真实账号 Library 游戏
每个游戏可以看到 HDR / RTX / Reflex / Store 等服务端信息
```

## 4. 全部游戏

进入“全部游戏”。

预期：

```text
显示真实 GFN Catalog
数量通常大于 Library
```

## 5. 搜索

输入一个明确存在于 GFN 的游戏名称并点击“搜索”。

预期：

```text
服务端返回搜索结果
不是本地过滤
```

## 6. 游戏详情

点击任一游戏的“查看详情”。

预期根据服务端 metadata 可能显示：

```text
标题
长描述
类型
开发商
发行商
内容评级
商店 variant
HDR / RTX / Reflex（以 browse feature 为准）
```

## 7. 刷新

首页点击“刷新 GFN 内容”。

预期重新请求：

```text
serverInfo
MES
Library
Catalog
```

## 8. 重启

Force stop 后重新启动。

预期：

```text
恢复登录态
→ 恢复 Provider
→ 自动加载真实内容
```

## 9. 当前不应该期待的功能

第三版还没有：

```text
开始游戏
CloudMatch Create
Queue
WebRTC
视频 / 音频
手柄输入
HDR 串流
```

游戏详情中的“启动串流”会明确标记为下一阶段，不应出现假按钮或假成功状态。

## 10. 出错时最有价值的信息

优先提供：

```text
页面错误原文
GfnAuth / GfnContent logcat
HTTP status（如果页面显示）
是首页、Library、Catalog、Search 还是 Detail 失败
登录后立即失败还是重启后失败
```

不要提供 access token / refresh token / id token / client_token。
