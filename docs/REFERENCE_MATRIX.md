# GFN Android Reference Matrix

原则：CloudNow / OpenNOW 是 witnesses，不是 specification。第三方实现冲突时保留冲突，本项目 fixture / wire / 真机 A/B 做最终裁决。

## Keyboard — v5.1.9 soft-freeze

| Feature | CloudNow | OpenNOW | gfn-android v5.1.9+ | Verified |
|---|---|---|---|---|
| Keyboard VK | Windows VK | Windows VK | Windows VK | 真机 |
| Normal scan | Windows Set-1 | `0` | Windows Set-1 | fixture + 真机回归 |
| CapsLock | normal VK_CAPITAL | VK_CAPITAL + synthetic LSHIFT | normal VK_CAPITAL | 真机回归 |
| type19 lock sync | No | Yes | No | A/B 后删除 |
| tracked modifiers | client state | DOM event/state path | InputStateTracker | 既有真机证据 |
| Session keyboardLayout | explicit setting | explicit setting | persistent setting | `en-US` Cyberpunk 真机修复 |
| Claim/Resume layout stability | session setting | session setting | frozen launch snapshot | v5.2 真机通过 |
| ordered input channel | Yes | Yes | Yes | 既有真机证据 |

### Current verdict

```text
Session keyboardLayout=en-US
= Cyberpunk 2077 A-Z/completion-string 问题的已验证有效修复。

scan=0 / type19 / synthetic LSHIFT
= 调查期 witness 行为；不是本项目 production requirement。
```

v5.1.9 已完成 Cyberpunk 2077 + CS2 回归，Keyboard packet semantics 进入 soft-freeze。

---

## Stream Settings — v5.2

| Feature | CloudNow | OpenNOW | gfn-android v5.2 | Verified |
|---|---|---|---|---|
| Persistent settings model | Yes (`StreamSettings`) | Yes (`Settings`) | Yes (`PersistentStreamSettings`) | Kotlin fixture |
| Keyboard layout independent from game language | Yes | Yes | Yes | en-US 真机修复 + fixture |
| Resolution setting | Yes | Yes | Auto + 当前 1920x1080 | Resolver fixture；默认路径既有真机 |
| FPS setting | Yes | Yes | Auto + 当前 60 FPS | Resolver fixture；默认路径既有真机 |
| Max bitrate setting | Yes | Yes | 5–100 Mbps client guard, 20 Mbps default | 100 Mbps 当前真机环境通过；不外推为服务端全局上限 |
| Audio mode setting | Yes | platform capability based | 当前仅 Stereo 2ch | Resolver fixture；Stereo 既有真机 |
| Codec choice exposed | Yes | Yes | **No**，当前固定 H.264 | 保守冻结 |
| Main10/HDR exposed | implementation-dependent | Yes | **No** | 后续版本单独取证 |
| Immutable launch snapshot | settings copied into session flow | settings passed through session flow | `ResolvedLaunchProfile` | Resolver + persistence fixtures |
| CREATE uses snapshot | Yes | Yes | Yes | targeted compile/static guard |
| CLAIM uses same snapshot | session-stable behavior | session-stable behavior | Yes | persistence fixture + targeted compile |
| WebRTC uses same `StreamConfig` | Yes | Yes | Yes | targeted compile/static guard |
| Legacy pre-v5.2 resume guessed | n/a | n/a | **No** | fixture: profile remains null |

### v5.2 evidence boundary

```text
Current verified media path:
1920x1080 @ 60 FPS
H.264
SDR8
Stereo 2ch
20 Mbps default
100 Mbps tested successfully on the current true-device/session environment
```

`100 Mbps` 已在当前真机环境验证功能正常；这只证明该设备/账号/节点/Session 组合可用，**不等价于 NVIDIA 服务端的全局最大码率规范**。5–100 Mbps 仍是本客户端 guard。

`ResolvedLaunchProfile` 的工程约束：

```text
Persistent StreamSettings
        ↓ resolve once
account entitlement
+ current engine capabilities
        ↓
ResolvedLaunchProfile (immutable snapshot)
        ↓
CREATE / persist / CLAIM / WebRTC
```

活动 Session 期间不得重新读取 Settings 并静默改变协议参数。

---

## Reconnect — v5.2.1

| Feature | CloudNow | OpenNOW | gfn-android v5.2.1 | Verified |
|---|---|---|---|---|
| same Session ID | same-session `claimSession` | active-session candidate -> same-ID `claimSession` | enforced; changed ID rejected | fixture |
| recreate Session on transport failure | No | No | **No** | static guard + fixture (`CREATE_COUNT=1`) |
| signaling refresh | reclaim returns refreshed Session, then new signaling | active lookup + claim, then reconnect signaling | same-session Claim response drives fresh signaling info | fixture + code path |
| PeerConnection rebuild | tears down old PC, creates new | disposes old client/WebRTC, reconnects | old transport drain/close -> new PC | controller fixture + static guard |
| DataChannel rebuild | new transport channels | new WebRTC client/channels | `createExpectedDataChannels` on new PC | static guard |
| input re-handshake | new InputSender/channel readiness | input bridge reset then new connection | fresh `input_channel_v1` + handshake required for success | static guard + recovery success gate |
| transient disconnect grace | reconnect implementation uses bounded retry/backoff | 7s ICE disconnected grace | 7s grace before reclaim | deterministic controller fixture |
| bounded retry | max 3, delays 0.5/1/2s | recovery budget 2, 0/3s | max 3; 1/3s retry after failed attempts | deterministic controller fixture |
| server terminal event | `serverStopped` blocks reconnect | explicit/expected close terminal handling | `exitMessage` / 404 / 410 terminal; reconnect blocked | existing guards + static guard |
| reuse frozen launch profile | reconnect callback captures its reconnect settings | recovery rebuilds settings from current settings | **always existing immutable `ResolvedLaunchProfile`** | session fixture (`SETTINGS_RESOLVE_COUNT=1`) |
| video surface input after rebuild | platform-specific | platform-specific | re-installs dynamic `inputListener` on bound Surface | static guard |

### Deliberate reference divergence

CloudNow and OpenNOW agree on the important protocol/lifecycle boundary: a recoverable transport failure reclaims the running Session and rebuilds transport; neither needs a new CloudMatch Session. Their retry timing and recovery orchestration differ. OpenNOW currently rebuilds recovery stream settings from current settings, while this project deliberately does **not** copy that behavior because the v5.2 project rule requires a frozen Session snapshot.

The v5.2.1 success gate is stricter than merely reaching ICE connected:

```text
same Session ID verified
+ frozen profile verified
+ new FIRST FRAME
+ new input_channel_v1 protocolReady
= reconnect success
```

## Gamepad — v5.3 placeholder

| Feature | CloudNow | OpenNOW | gfn-android | Verified |
|---|---|---|---|---|
| type12 packet | Yes | Yes | TODO | - |
| button bitmap | TODO | TODO | TODO | - |
| LT/RT encoding | TODO | TODO | TODO | - |
| stick range | TODO | TODO | TODO | - |
| controller index | TODO | TODO | TODO | - |

## HEVC — v6.0 placeholder

| Feature | CloudNow | OpenNOW | gfn-android | Verified |
|---|---|---|---|---|
| codec request | TODO | TODO | H.264 only | - |
| Offer detection | TODO | TODO | H.264 path | - |
| HEVC preference | TODO | TODO | TODO | - |
| H.264 fallback | TODO | TODO | current baseline | - |
| decoder selection | TODO | TODO | libwebrtc H.264 | - |
