# CloudNow 参考记录

CloudNow 是当前最重要的已实现参考。本项目采用 clean-room 方式：只复现公开可观察的协议行为、状态机与架构边界，不逐行翻译 Swift。

## 第二版认证参考

参考：

```text
CloudNow/Auth/NVIDIAAuthAPI.swift
CloudNow/Auth/AuthManager.swift
```

当前吸收的行为：

```text
GET /v1/serviceUrls
→ 登录 Provider discovery
→ POST /device/authorize
→ user_code / device_code / verification_uri
→ POST /token 轮询
→ authorization_pending：继续
→ slow_down：轮询间隔 +5 秒
→ expired_token：失败
→ access_denied：失败
→ Device Flow token
→ /userinfo
→ /client_token
→ client_token grant
→ re-bind 到主 GFN client ID
→ 再获取 client_token
```

refresh 时当前参考顺序：Device Flow client ID 优先，主 client ID 回退。

CloudNow 的 `AuthManager` 还使用 credential generation 防止旧登录任务覆盖新状态；Android 第二版在 `AuthController` 实现了同类保护。

### 当前保留的不确定项

`display_name = Apple TV` 是 CloudNow 当前已验证参数，但它只属于 Device Flow 的设备显示名称。Android App 现已改为 `display_name = Android`；CloudMatch 的 `WINDOWS / DESKTOP` 协议身份不受此字段影响。

## 后续 CloudMatch 参考

参考：

```text
CloudNow/Session/CloudMatchClient.swift
```

重点模型：

```text
clientIdentification = GFN-PC
clientPlatformName = windows
nv-device-os = WINDOWS
nv-device-type = DESKTOP
nv-device-make = UNKNOWN
nv-device-model = UNKNOWN
```

第二版没有把真实 CloudMatch HTTP 接入 UI，也没有提前硬编码 HDR/Main10 请求。第三版会先完成 Catalog / Library / CloudMatch SDR 基线。

## 后续 Session 生命周期参考

参考：

```text
CloudNow/Session/SessionOrchestrator.swift
```

当前纯 Kotlin fixture 已实现：

```text
create
→ queue
→ preparing
→ 连续两次 ready
```

后续真实 CloudMatch 接入时继续保持 generation、single-flight、取消和 teardown 边界。

## 后续 Main10 参考

参考：

```text
CloudNow/Streaming/GFNVideoDecoderFactory.swift
```

CloudNow 会保留 H.265 `profile-id=2`。Android 端不会现在就假设需要相同 patch；只有真实 SDP 证明 Main10 payload 在 answer 阶段被过滤后，才实现对应的 `GfnVideoDecoderFactory`。
