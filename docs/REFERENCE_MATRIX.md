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
| Audio mode setting | Yes | stereo-focused | 2ch native + 6ch experimental request | v5.4 fixture；6ch true-device 未验证 |
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
Stereo 2ch（v5.4 显式启用 ADM stereo；独立 L/R 尚待素材验证）
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

True-device result recorded before v5.3:

```text
network loss -> reconnect keeps the same Session ID        VERIFIED
first reconnect attempt can remain black-screen            KNOWN DEFECT
second disconnect/reconnect can restore video              OBSERVED
```

Per current development scope, the first-reconnect black-screen defect is explicitly deferred and is not mixed into v5.3 Gamepad changes.

## Gamepad — v5.3

| Feature | CloudNow | OpenNOW | gfn-android v5.3 | Verified |
|---|---|---|---|---|
| type12 raw body | 38-byte body | 38-byte body | 38-byte body | exact packet fixture |
| body endianness | type/length/index/bitmap/state/axes/timestamp LE | same | same | exact offset fixture |
| timestamp clock origin | wall-clock µs default | session/input clock | existing gfn input clock retained | field layout verified; origin intentionally not changed in v5.3 |
| button bitmap | XInput flags | XInput flags | XInput flags | fixture + static guard |
| LT/RT encoding | independent `u8` 0–255 | independent `u8` 0–255 | Android analog axis/digital fallback -> `u8` | controller fixture |
| stick range | signed `i16`, radial deadzone 15% | signed `i16`, radial deadzone 15% | signed `i16`, radial deadzone 15% | controller fixture |
| Y-axis convention | platform GC values mapped into XInput state | browser Y inverted for XInput | Android Y inverted for XInput | controller fixture |
| connected bitmap | slot bit + XInput-style high bit | slot bit + XInput-style high bit | single normalized slot 0 = `0x0101` | packet/controller fixture |
| controller index | supports slots | up to 4 | **v5.3 only slot 0** | enforced |
| disconnect state | neutral/update bitmap | neutral/update bitmap | neutral snapshot + bitmap `0x0000` | controller fixture |
| v3 reliable framing | current encoder emphasizes PR framing | `[0x23][ts][0x21][size][body]` supported | reliable framing, 50 bytes | exact packet fixture |
| partially reliable gamepad | implementation supports it | capability-gated | **No in v5.3**; NVST advertises `0` | static guard |
| periodic presence refresh | periodic snapshots/heartbeat behavior | changed state or 100ms refresh | changed state or 100ms snapshot refresh | controller fixture/static |
| haptics / type13 | Yes | Yes | **Deferred** | - |
| multi-controller | Yes | Yes | **Deferred** | - |

### v5.3 deliberate scope

```text
Android physical gamepad
        ↓
normalize to one XInput-style virtual slot (slot 0)
        ↓
ABXY / D-Pad / LB-RB / LT-RT / Start-Back / L3-R3 / sticks
        ↓
GFN type12
        ↓
reliable input_channel_v1
```

The `0x0101` bitmap is a **client-side normalized virtual-controller declaration** for slot 0; it does not claim the physical Android controller is actually an Xbox-branded device. Guide/Mode is mapped when Android delivers `KEYCODE_BUTTON_MODE`, but Android or the device firmware may consume it before the app; true-device behavior remains unverified until device testing.

v5.3 intentionally does not enable type13 rumble/haptics or the partially-reliable gamepad channel. The current NVST answer still advertises `a=ri.enablePartiallyReliableTransferGamepad:0`, so PR gamepad transport remains a later isolated experiment rather than an assumed protocol requirement.


## Audio — v5.4

| Feature | CloudNow | OpenNOW | gfn-android v5.4 | Verified |
|---|---|---|---|---|
| Opus stereo Answer | adds `stereo=1` | adds `stereo=1` | adds `stereo=1` on first game-audio Opus fmtp | SDP fixture |
| Android ADM stereo output config | custom Apple audio device | host/browser dependent | `JavaAudioDeviceModule.setUseStereoOutput(true)` | API-shaped compile; true-device L/R separation pending |
| 6ch CloudMatch request | Yes | not used as native-5.1 witness here | existing `audioMode/surroundAudioInfo` with snapshot=6 | source + settings fixture |
| multiopus Offer detection | Yes | no equivalent path found in current checked source | requires `multiopus/*/6` for 6ch mode | SDP fixture |
| rejected multiopus answer repair | rebuilds game-audio section | no equivalent path found | rebuild first game-audio only; reuse Offer fmtp + Answer transport/BUNDLE | SDP fixture |
| exact 5.1 channel mapping | Offer-driven | n/a | Offer-driven; fixture uses `0,4,1,2,3,5` | fixture only |
| native physical 5.1 playout | custom multichannel audio device | not established | **No**; upstream Android Java ADM configured 2ch | explicit capability guard |
| 6ch output label | native when route supports it | n/a | **experimental negotiation/2ch-ADM probe** | UI/static guard |

### v5.4 evidence boundary

The important distinction is deliberate:

```text
requested audio channels = {2, 6}
native preserved output  = {2}
```

CloudNow demonstrates that multiopus negotiation and true multichannel device playout are separate engineering problems: it performs SDP repair **and** owns a custom multichannel audio device. gfn-android v5.4 implements the negotiation side but keeps upstream Android Java ADM, so a successful `multiopus/6` negotiation must not be reported as native 5.1.

OpenNOW is useful as a second witness for Opus `stereo=1`/audio-bandwidth Answer behavior, but the checked current source did not provide an equivalent multiopus/native-5.1 implementation. Absence of a found implementation is not treated as protocol proof.

---

## HEVC — v6.0 placeholder

| Feature | CloudNow | OpenNOW | gfn-android | Verified |
|---|---|---|---|---|
| codec request | TODO | TODO | H.264 only | - |
| Offer detection | TODO | TODO | H.264 path | - |
| HEVC preference | TODO | TODO | TODO | - |
| H.264 fallback | TODO | TODO | current baseline | - |
| decoder selection | TODO | TODO | libwebrtc H.264 | - |
