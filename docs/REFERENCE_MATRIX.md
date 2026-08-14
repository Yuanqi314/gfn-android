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
| Claim/Resume layout stability | session setting | session setting | frozen launch snapshot | fixture；v5.2 真机待回归 |
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
| Max bitrate setting | Yes | Yes | 5–100 Mbps client guard, 20 Mbps default | Encoding path/static verified；非默认值真机 A/B 待验证 |
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
Current stable media path:
1920x1080 @ 60 FPS
H.264
SDR8
Stereo 2ch
20 Mbps default
```

`5–100 Mbps` 是 v5.2 客户端允许写入现有 SDP/NVST bitrate 字段的 guard 范围，参考实现也允许可调码率；它**不是**本项目已经真机证明的服务端最大码率。非默认值必须继续做单变量真机 A/B。

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

## Reconnect — v5.2.1 placeholder

| Feature | CloudNow | OpenNOW | gfn-android | Verified |
|---|---|---|---|---|
| same Session ID | TODO inspect | TODO inspect | reconcile only | - |
| recreate Session | TODO inspect | TODO inspect | must not | - |
| signaling refresh | TODO inspect | TODO inspect | TODO | - |
| DataChannel rebuild | TODO inspect | TODO inspect | TODO | - |
| input re-handshake | TODO inspect | TODO inspect | TODO | - |
| reuse frozen launch profile | expected session-stable | expected session-stable | v5.2 foundation ready | fixture only |

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
