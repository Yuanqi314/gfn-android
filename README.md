# GFN Android Lab

这是一个独立实现的 Android GeForce NOW 实验客户端。正式路线不是继续修改 NVIDIA 官方 APK，而是逐步完成自己的 Android 客户端：

```text
NVIDIA Device Flow 登录
→ Account / Catalog / Library
→ CloudMatch / Queue / Session
→ WebRTC H.264 SDR
→ HEVC Main
→ HEVC Main10 SDR10
→ HDR10（BT.2020 + ST2084/PQ）
```

## 第二版目标

第二版只聚焦一件事：**把第一版只有 UI 的登录入口接成真实的认证链路**。

已经实现：

- 首页“登录 GeForce NOW”按钮有真实行为；
- 请求 `serviceUrls` 并按优先级选择登录 Provider；
- 请求 NVIDIA Device Flow 登录码；
- 显示登录码并打开 NVIDIA 授权页面；
- 按服务端 interval 轮询 token；
- 处理 `authorization_pending`、`slow_down`、`expired_token`、`access_denied`；
- 获取账号 `/userinfo`；
- 获取 `client_token`；
- 参考 CloudNow 当前流程，使用 `client_token grant` 将 Device Flow 凭据重新绑定到主 GFN client ID；
- 如果 re-bind 响应没有新的 refresh/id token，会保留 Device Flow 阶段的原值；
- AndroidKeyStore + AES-GCM 加密持久化 OAuth 凭据；
- 重启后恢复登录态；如果 `/userinfo` 返回 401，会 refresh 后重试；
- 登录取消/退出使用 generation 防止旧异步任务回写登录态；
- 网络日志层默认脱敏 Authorization、Cookie、x-device-id；
- 首页、游戏库、诊断、设置文本已统一为中文。

## 尚未完成

第二版**没有**声称以下功能已经完成：

- Provider discovery；
- 真实 Catalog / Library；
- Subscription / Account capability；
- CloudMatch Create / Poll / Stop；
- Queue 真实页面；
- WebRTC signaling；
- H.264 第一帧；
- 音频与手柄；
- HEVC / Main10 / HDR10。

当前游戏库仍是 fixture，并在 UI 中明确标记“真实 API 待接入”。

## 第二版认证链

```text
Compose 首页
    ↓
AuthController
    ↓
AuthSessionService
    ↓
NvidiaAuthApi
    ↓
/device/authorize
    ↓
/token（device_code grant）
    ↓
/userinfo
    ↓
/client_token
    ↓
/token（client_token grant，re-bind 到主 client ID）
    ↓
AndroidKeyStore AES-GCM
```

## CloudNow 参考原则

CloudNow 是当前最重要的已实现参考，但本项目采用 clean-room 方式：参考公开协议行为、状态机和模块边界，不逐行翻译 Swift。

第二版主要参考：

- `CloudNow/Auth/NVIDIAAuthAPI.swift`：Device Flow、refresh、userinfo、client_token；
- `CloudNow/Auth/AuthManager.swift`：client_token re-bind、credential generation、登录态生命周期；
- `CloudNow/Session/CloudMatchClient.swift`：后续 Windows/GFN-PC headers 与 SessionRequest；
- `CloudNow/Session/SessionOrchestrator.swift`：后续 Create → Queue → Ready → Teardown；
- `CloudNow/Streaming/GFNVideoDecoderFactory.swift`：后续 Main10 `profile-id=2` 参考。

`display_name` 当前暂时保持 CloudNow 已验证使用的 `Apple TV`，只是为了减少第二版认证变量；**不能据此推断 NVIDIA 要求 Android 客户端必须使用这个值**。后续真机认证稳定后会单独做 Android display name A/B 验证。

## 构建

要求：

- JDK 17；
- Android Studio / Android SDK 37；
- 首次构建需要访问 Google Maven、Maven Central 和 Gradle 分发站点。

Linux/macOS：

```bash
./gradlew :app:assembleDebug
```

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

项目中的 `gradlew` / `gradlew.ps1` 是自包含的 Gradle 9.5.0 启动器：首次执行会下载 Gradle，并使用项目内固定的 SHA-256 校验分发包，不依赖 `gradle-wrapper.jar`。

## 核心离线验证

如果本机有 `kotlinc` 和 JDK，可不依赖 Android SDK运行协议 smoke test：

```bash
./verify-core.sh
```

它会验证：

```text
Windows/GFN-PC identity
→ Session fixture queue/ready 状态机
→ Device Flow pending
→ token
→ userinfo
→ client_token
→ 主 client ID re-bind
→ 登录态 userinfo 401 后 refresh 恢复
```

## 当前验证边界

本环境已经真实完成纯 Kotlin 编译和 fixture smoke test；但是当前容器没有 Android SDK，也不能从容器发起真实 NVIDIA 登录请求，因此：

- **认证代码路径已实现并通过 fixture 验证**；
- **Android Compose APK 尚未在本环境编译**；
- **真实 NVIDIA 账号登录仍需要你的联网真机/Android Studio 环境验证**。

不能把 fixture 成功等同于 NVIDIA 线上认证成功。

## 安全边界

- 只使用用户自己的合法 GFN 账号；
- 不修改订阅等级、entitlement 或服务端授权；
- 不记录 access token、refresh token、device code 或 Cookie；
- Android 本机 MediaCodec / Display capability 保持真实；
- 后续 Windows/GFN-PC 身份仅用于协议兼容性实验。
