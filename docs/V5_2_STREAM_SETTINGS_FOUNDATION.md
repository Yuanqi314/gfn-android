# v5.2 Stream Settings Foundation

## Goal

v5.2 不新增 HEVC/Main10/HDR/5.1/120 FPS。目标是先消除 Session 创建参数与 WebRTC 运行参数之间的隐式漂移，并建立后续 Reconnect/Gamepad/Audio/HEVC 都可以复用的 launch snapshot 基础。

核心数据流：

```text
PersistentStreamSettings
        ↓
GfnStreamSettingsResolver
        ↓ resolve against
SubscriptionInfo.entitledResolutions
+ StreamCapabilityProfiles.V52_ANDROID_WEBRTC
+ auto keyboard layout / game language
        ↓
ResolvedLaunchProfile
        ↓
SessionCreateRequest
PersistedSessionRecord
SessionClaimRequest
GfnStreamingController
GfnWebRtcEngine
```

原则：**live WebRTC 不读取 persistent settings。**

---

## Why this version exists

v5.1.8/v5.1.9 已经证明 Session `keyboardLayout` 是真实的启动级配置：Cyberpunk 2077 在中文自动布局下进入 completion-string 路径，而新 Session 明确使用 `en-US` 后恢复正常。

这说明影响 Session / WebRTC negotiation 的设置不能在不同层各自重新计算。

v5.1.9 之前还存在另一个结构性风险：

```text
CloudMatch:
select entitlement preset
        ↓
SessionCreateRequest(width / height / fps)

WebRTC:
connect(session, StreamConfig())
        ↓
重新使用默认值
```

即使当时默认值恰好都是 1080p60，也属于两个独立 truth source。v5.2 删除这个结构性漂移点。

---

## Persistent settings

`PersistentStreamSettings` 只表示“用户对下一新 Session 的意图”：

```kotlin
PersistentStreamSettings(
    keyboardLayoutSelection,
    resolutionSelection,
    fpsSelection,
    maxBitrateKbps,
    audioChannels,
)
```

当前选项：

```text
Keyboard Layout:
  Auto
  en-US (default)
  en-GB / de-DE / fr-FR / ... / zh-CN / zh-TW

Resolution:
  Auto
  1920x1080

FPS:
  Auto
  60

Max bitrate:
  5–100 Mbps client guard
  20 Mbps stable default
  5 Mbps step

Audio:
  Stereo 2ch only
```

### Bitrate evidence boundary

`5–100 Mbps` 不是“已证明服务器最大支持 100 Mbps”的结论。

当前代码已经存在完整 bitrate wire path：

```text
ResolvedLaunchProfile.maxBitrateKbps
        ↓
StreamConfig
        ↓
SDP bandwidth injection
+ NVST video.initialPeakBitrateKbps
+ vqos.bw.maximumBitrateKbps
+ vqos.bw.peakBitrateKbps
```

因此 v5.2 允许修改该参数，但只有 20 Mbps 是当前项目稳定默认值。非默认值需要真机 A/B 后才能升级为 VERIFIED。

---

## Current engine capability profile

`StreamCapabilityProfiles.V52_ANDROID_WEBRTC` 统一定义 resolver 和 WebRTC validator 的共同边界：

```text
Resolution : 1920x1080
FPS        : 60
Codec      : H.264
Color      : Compatibility SDR8
Audio      : Stereo 2ch
Bitrate    : client guard 5–100 Mbps
```

这避免 resolver 允许一个设置，但 `GfnWebRtcEngine` 使用另一套 hard-coded validation 再拒绝。

HEVC/Main10/HDR/5.1/120 FPS 不在 capability set 中，因此 UI 不会把它们伪装成可用功能。

---

## Resolution against entitlement

如果 `SubscriptionInfo.entitledResolutions` 非空：

```text
用户选择 / Auto
        ↓
必须同时满足：
1. current engine capability
2. account entitlement
```

没有交集直接返回 `StreamProfileResolutionException`，不会自行猜一个服务器可能接受的值。

如果 entitlement 列表为空，则不能证明账号限制；resolver 只使用当前 engine default，并标记：

```text
entitlementVerified=false
```

不会把“服务端没返回 entitlement”误解释成“账号一定支持 1080p60”。

---

## Immutable `ResolvedLaunchProfile`

```kotlin
ResolvedLaunchProfile(
    streamConfig,
    keyboardLayout,
    gameLanguage,
    entitlementVerified,
)
```

创建新 Session 时仅 resolve 一次。

随后：

```text
CREATE
  width / height / fps / color / audio
  keyboardLayout / gameLanguage

Persist
  complete ResolvedLaunchProfile

CLAIM / RESUME
  original keyboardLayout / gameLanguage / audio
  original profile retained

WebRTC
  profile.streamConfig
```

设置页面即使在 Session 期间被修改，也只改变“下一 Session”的 persistent intent。

---

## Legacy resume policy

v5.1.9 或更早保存的 `gfn-session-v4.properties` 没有完整 `ResolvedLaunchProfile`。

v5.2 **不会猜**：

```text
legacy record
+ keyboardLayout only
        ↓
launchProfile = null
        ↓
Claim/Resume blocked
        ↓
要求 End / Cleanup
        ↓
重新创建 v5.2 Session
```

这是为了遵守 Session Snapshot 规则；否则 Claim 可能使用当前 Settings，而原 Session 是用另一组参数建立的。

另外 v5.2 区分：

```text
orchestrator-owned Session
vs
仅从磁盘恢复、尚未 claim 的 persisted Session
```

legacy record 被拒绝 Resume 后执行 End/Cleanup 时，会直接 DELETE persisted Session；不会因为 Error state 携带 `SessionInfo` 就误调用空的 `stopOwnedSession()`。仅清除本地 resume record 时，如果当前仍有真正的 owned Session，则保留其 active `ResolvedLaunchProfile`，避免把正在运行的 WebRTC snapshot 一并清掉。

---

## Keyboard soft-freeze

v5.2 没有修改：

```text
Windows VK
Windows Set-1 scan
tracked modifiers
keyboard packet framing
CapsLock semantics
ordered input_channel_v1
releaseAll state machine
```

`verify-keyboard-stable.sh` 继续作为回归门禁。

---

## Diagnostics

新增/强化：

```text
GfnStreamSettings
  persistent next-Session setting changes

GfnLaunchProfile
  RESOLVED
  CREATE
  CLAIM
  WEBRTC
```

同一 Session 应能从日志中看到相同 profile 一直传递到 WebRTC。

---

## Validation status before true-device test

已完成离线：

```text
settings resolver fixture                 PASS
v5.1.8 keyboard preference migration      PASS
persistent stream settings round-trip     PASS
session profile persistence round-trip    PASS
legacy profile remains null               PASS
settings controller targeted compile      PASS
session controller targeted compile       PASS
streaming controller targeted compile     PASS
v5.1.9 keyboard packet fixture            PASS
v5.1.9 keyboard static guards             PASS
```

完整 Android Gradle assemble 仍受构建环境依赖/联网条件限制时，不以这些离线结果冒充 APK 全工程 build。
