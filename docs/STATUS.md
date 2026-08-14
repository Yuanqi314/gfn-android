# 第二版项目状态

## 已实现并通过纯 Kotlin smoke test

- [x] Windows / GFN-PC 协议身份集中建模
- [x] `HttpTransport` 网络边界
- [x] Device Flow 请求模型
- [x] Device Flow token 轮询
- [x] `authorization_pending`
- [x] `slow_down`
- [x] `expired_token`
- [x] `access_denied`
- [x] `/userinfo`
- [x] refresh token 回退
- [x] `client_token` 获取
- [x] `client_token grant` 主 client ID re-bind
- [x] re-bind 后保留缺失的 Device Flow refresh/id token
- [x] 登录态 `/userinfo` 401 → refresh → 重试
- [x] Session queue / preparing / 连续两次 ready 状态机
- [x] Authorization / Cookie / x-device-id 日志脱敏

## Android 侧已接线，但需要真机构建/联网验证

- [x] 首页真实登录按钮
- [x] 登录码页面
- [x] 打开 NVIDIA 授权页
- [x] 登录成功账号卡片
- [x] 取消登录 / 退出登录
- [x] credential generation 防旧任务回写
- [x] AndroidKeyStore AES-GCM token 存储
- [x] UTF-8 长度前缀 token 序列化，带 1 MiB 单字段上限
- [x] `INTERNET` permission
- [x] 中文 UI 文本

> 当前执行环境没有 Android SDK，也不能向 NVIDIA 登录端点发真实请求。因此以上 Android 项目项属于“代码已接线”，不是“真机线上已通过”。

## 尚未完成

- [ ] 真机 NVIDIA Device Flow 线上验证
- [x] Provider discovery（代码与 fixture 已验证，线上待真机验证）
- [ ] 真实 Account / Subscription
- [ ] 真实 Catalog
- [ ] 真实 Library
- [ ] CloudMatch Create / Poll / Stop
- [ ] Queue 页面接真实 session
- [ ] WebRTC signaling
- [ ] H.264 SDR 第一帧
- [ ] 音频
- [ ] 手柄输入
- [ ] HEVC Main
- [ ] HEVC Main10 SDR10
- [ ] HDR10 / BT.2020 / ST2084

## 第二版成功判定

真机验证应满足：

```text
首页点击登录
→ 获取 NVIDIA Device Flow 登录码
→ 浏览器完成授权
→ token 轮询成功
→ /userinfo 成功
→ client_token 获取成功
→ 主 GFN client ID re-bind
→ 首页显示账号
→ 重启应用恢复账号
→ 退出登录后凭据清理
```

只有这条链在真实账号环境通过，第二版认证阶段才算最终验收。
