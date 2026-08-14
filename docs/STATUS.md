# 第四版状态（v4.0.1）

> 已修复 `CloudMatchProtocol.kt` 跨模块 `serverIp` smart-cast 编译错误，并新增真实模块边界编译检查。Session 协议逻辑未改变。

## 已由真机确认的基础

以下结果来自用户对 v2.1 / v3.0.1 的真实 Android 测试：

```text
NVIDIA Device Flow 登录            ✅
用户名 / 邮箱                      ✅
AndroidKeyStore 保存               ✅
重启恢复登录态                     ✅
会员 / Subscription                ✅
游戏库 Library                     ✅
全部游戏 Catalog                   ✅
服务端 Search                      ✅
Game Detail                        ✅
```

因此 `gfn-auth` 与 `gfn-games/gfn-account` 当前为 soft-freeze。

## v4 已实现

```text
GameVariant launchAppId
CloudMatch POST Create
CloudMatch GET Poll
Queue / Preparing
双 Ready 确认
resolved server re-poll
Claim / Resume PUT
DELETE End
Session resume record
稳定 x-device-id
Session generation / stale cleanup
401 single-flight refresh
Queue Ad 不支持时的安全 cleanup
```

## v4 内容层加固

```text
GraphQL cursor cycle protection
hasNextPage=true + 空 cursor → Protocol error
达到 maxPages 且仍有下一页 → Protocol error
分页 totalCount 中途变化 → Protocol error
PersistedQueryNotFound → ProtocolDrift
selected / owned GameVariant 优先
数值 variant id → CloudMatch appId
Provider 恢复时重新 discovery
```

## 核心 fixture 已通过

最新输出：

```text
docs/V4_SMOKE_OUTPUT.txt
```

验证链：

```text
Create
→ Queued(5)
→ Queued(2)
→ Preparing
→ Ready #1
→ Ready #2
→ Claim / RESUME
→ DELETE End
```

另外验证：

```text
Cancel while Creating
→ create 迟到返回
→ stale generation
→ DELETE cleanup 一次
```

旧版回归仍通过：

```text
Device Flow
client_token re-bind
登录态 401 → refresh
Provider restore
serverInfo → VPC
MES
Library
Catalog
Game Detail
```

## 仍未真机验证

以下是第 4 版新增行为，本环境没有 NVIDIA 实际网络凭据，因此不能标记成功：

```text
真实 CloudMatch Create              ⏳
真实 Queue position                 ⏳
真实 Preparing / seatSetupStep      ⏳
真实连续 Ready                      ⏳
真实 ConnectionInfo / ICE           ⏳
真实 Claim / Resume                 ⏳
真实 DELETE End                     ⏳
冷启动 Session resume               ⏳
取消期间的服务端 cleanup            ⏳
```

v3 内容层虽然真机功能已正常，但 v4 新加入的“严格 Catalog 完整性检查”仍需要一次真机回归确认。

## v4 明确未实现

```text
Queue Ad 播放 / 上报
WebRTC PeerConnection
SDP
视频第一帧
Audio
Controller input
HEVC
Main10
HDR10
```

Queue Ad 如果出现，v4 会明确停止该测试并 best-effort End Session，不会假装已经支持。

## 第四版成功 Gate

```text
1. 选真实 GameVariant
2. Create 成功并拿到 Session ID
3. Queue 正确更新
4. Preparing 正确更新
5. 连续两次 Ready
6. ConnectionInfo / ICE 可见
7. Claim / Resume 成功
8. End 后服务端会话被清理
9. Cancel while Queue/Preparing 不留下孤儿 Session
10. App 重启可识别并 Claim 一个仍有效的 Session
```

这些完成后才进入 v5 WebRTC。
