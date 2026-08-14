# 第四版真机测试指南

## 测试目标

第 4 版只验证：

```text
CloudMatch Create
→ Queue
→ Preparing
→ Ready
→ Claim / Resume
→ End
```

**不要用 v4 判断视频、H.264、HEVC、Main10 或 HDR 是否工作。v4 根本没有创建 WebRTC PeerConnection。**

## 0. 前置回归

启动应用后先确认：

```text
登录态正常恢复
会员正常
Library 正常
Catalog 正常
Search 正常
Detail 正常
```

v4 加入了严格分页检查。如果 Catalog 此时出现新错误，请记录完整错误，而不要继续 Session 测试。

## 1. 选择游戏

优先使用：

```text
Library 中已拥有的游戏
↓
打开详情
↓
选择正确商店 Variant
```

详情中的按钮类似：

```text
建立 STEAM Session · 已拥有
```

如果同一个游戏有多个商店，请选择你真实拥有/已经关联的商店。

## 2. Create

点击建立 Session 后进入“会话”页。

预期：

```text
Creating
↓
出现真实 Session ID
```

记录：

```text
Session ID
status
store
```

如果 Create 失败，请保留：

```text
错误类型
HTTP 状态
CloudMatch API status
```

日志不应包含 token。

## 3. Queue

如果需要排队：

```text
Queued(position)
```

位置应随服务端 poll 更新。

Queue 本身不受 180 秒 setup timeout 限制。

### Queue Ad

如果服务端要求强制广告，v4 会显示：

```text
服务端要求 Queue Ad；v4 尚未接广告播放器
```

随后会尝试 DELETE 清理 Session。

这是 v4 的已知功能边界，不是普通 Queue 失败。

## 4. Preparing

离开 Queue 后应进入：

```text
Preparing
```

观察：

```text
seatSetupStep
seatSetupEta
GPU（如果服务端已返回）
```

只有此阶段开始计算 180 秒 setup timeout。

## 5. Ready

v4 不接受单次偶发 Ready。

必须连续观察两次 status 2/3 才进入：

```text
Ready
```

Ready 页重点记录：

```text
Server IP
ConnectionInfo 条数
ICE server 条数
Signaling URL
Server streamingProfile（如果有）
```

此时**不要期待画面**。

## 6. Claim / Resume

Ready 后点击：

```text
验证 Claim / Resume
```

预期：

```text
preflight GET
↓
PUT action=2 / RESUME
↓
Claimed
```

v4 的 Resume 不重新协商 HDR/分辨率等流参数。

## 7. End

点击：

```text
End Session
```

预期：

```text
DELETE
↓
Ended
↓
本地 resume record 清除
```

再次创建同一游戏应能产生新的 Session，而不是被旧 Session 阻挡。

## 8. 取消 Race 测试

至少做一次：

```text
Create / Queue / Preparing 中
↓
点击“取消并清理 Session”
```

之后再次启动游戏。

目标是确认：

```text
旧 poll 不再改变 UI
旧 Create 如果迟到，也不会成为当前 Session
旧服务端 Session 最终被 cleanup
```

## 9. 重启恢复

当 Session 仍存在时退出应用再打开。

“会话”页应该显示：

```text
可恢复 Session
```

点击：

```text
Claim / Resume
```

如果服务端 Session 仍有效，应重新获取最新状态；如果已经过期，应显示明确失败，而不是无限 loading。

## 10. 建议反馈格式

最好直接告诉我：

```text
Create：成功/失败
Session ID：...
Queue：有/无，位置变化 ...
Preparing：seatSetupStep=...
Ready：成功/失败
GPU：...
Server：...
ConnectionInfo：N
ICE：N
Claim：成功/失败
End：成功/失败
是否出现 Queue Ad：是/否
```

如发生异常，再附 logcat。

## 第四版通过标准

全部成立才算 v4 Session Core 真机通过：

```text
Create
Queue
Preparing
双 Ready
Claim / Resume
End
Cancel cleanup
Restart resume
```

通过后再进入 v5 WebRTC。
