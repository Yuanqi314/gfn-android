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
| Audio mode setting | Yes | stereo-focused | 2ch native + 6ch experimental request | v5.4 fixture；6ch 模式音频播放真机正常，离散 5.1 未验证 |
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
| 6ch output label | native when route supports it | n/a | **experimental negotiation/2ch-ADM probe** | UI/static guard + true-device audio playback; discrete 5.1 unverified |

### v5.4 evidence boundary

The important distinction is deliberate:

```text
requested audio channels = {2, 6}
native preserved output  = {2}
```

CloudNow demonstrates that multiopus negotiation and true multichannel device playout are separate engineering problems: it performs SDP repair **and** owns a custom multichannel audio device. gfn-android v5.4 implements the negotiation side but keeps upstream Android Java ADM, so a successful `multiopus/6` negotiation must not be reported as native 5.1.

OpenNOW is useful as a second witness for Opus `stereo=1`/audio-bandwidth Answer behavior, but the checked current source did not provide an equivalent multiopus/native-5.1 implementation. Absence of a found implementation is not treated as protocol proof.

---

## HEVC — v6.0

| Feature | CloudNow | OpenNOW | gfn-android v6.0 | Verified |
|---|---|---|---|---|
| HEVC/H265 user selection | Yes | Yes | H.264 / HEVC Main | settings fixture |
| HEVC with SDR8 | Yes | Yes (`8bit_420`) | `CompatibilitySdr` only | capability fixture/static guard |
| Main profile preference | profile-id=1 for SDR | profile-id=1 compatibility preference | **explicit profile-id=1 required** | SDP fixture |
| Main10/profile-id=2 | custom factory/decoder path exists | separate 10-bit color modes | **not exposed** | static guard |
| Local decoder capability | decoder factory capability | receiver codec capabilities | `DefaultVideoDecoderFactory.supportedCodecs` | API-shaped compile/static guard |
| Offer inspection | codec/PT parsing | codec/PT parsing | H264 / HEVC / explicit HEVC Main PT diagnostics | SDP fixture |
| Answer filtering | preferred codec + RTX | codec preference + fallback | H264 or H265 Main + linked RTX + legacy repair PT | SDP fixture |
| H.264 fallback | Yes | Yes | same Session, explicit reason | policy fixture |
| HEVC tier/level rewrite | safety rewrite exists | helper exists | **not adopted in v6.0** | deliberate scope |
| Codec encoded into CloudMatch JSON | No requirement | no requirement adopted | **No change from v5.4** | v5.4-v6.0 byte comparison |
| AV1 | separate support | supported | **not exposed** | static guard |

### v6.0 reference boundary

CloudNow and OpenNOW agree on the important architecture boundary: H.265 codec selection is separable from HDR/Main10, and local receive capability plus the actual SDP intersection must decide whether H.265 can be used. gfn-android adopts that minimum common evidence.

Not adopted yet:

```text
Main10/profile-id=2
HDR metadata / HDR render path
AV1
forced H265 tier/level rewrite
custom H265 decoder
```

The current H.264 production CloudMatch request remains byte-identical to v5.4. Codec selection is intentionally isolated to the frozen stream profile and SDP/WebRTC path so the v6.0 true-device test has one new video variable.

### v6.0 true-device success boundary

```text
Requested codec      = Hevc
Local decoder codecs contains H265/HEVC
Offer HEVC Main PT   != empty
Answer HEVC Main PT  != empty
Negotiated codec     = Hevc
Codec fallback       = false
First RTP            = true
First frame          = true
```

If video renders while `Negotiated codec=H264` or `Codec fallback=true`, that is a successful fallback test, not a successful HEVC test.


---

## HEVC — v6.0.1 negotiation compatibility

| Layer | CloudNow witness | OpenNOW witness | gfn-android v6.0.1 |
|---|---|---|---|
| H265 Main / Main10 separation | Yes | Yes | Main only, SDR8 |
| Full receiver capability evidence | decoder/factory aware | receiver capability aware | `PeerConnectionFactory.getRtpReceiverCapabilities(VIDEO)` Logcat |
| Pre-createAnswer preference | platform negotiation path | explicit pre-answer hook + codec preferences | `RtpTransceiver.setCodecPreferences` |
| Raw createAnswer evidence | SDP munging tests | explicit raw/final negotiation flow | `RAW_ANSWER(_CODEC)` Logcat + diagnostics |
| H264 fallback | Yes | Yes | retained |
| tier/level rewrite | exists | exists | **not adopted yet** |
| Main10/HDR | exists separately | exists separately | **not enabled** |

The v6.0 true-device result selected HEVC but negotiated H264. v6.0.1 therefore targets the earlier PeerConnection negotiation layer before changing any H265 fmtp field.

## HEVC — v6.0.2 tier-flag causal A/B

| Layer | Evidence | gfn-android v6.0.2 decision |
| --- | --- | --- |
| Remote Offer | GFN exposes H265 Main `profile-id=1;tier-flag=1;level-id=153` | Keep original evidence log; treatment rewrites only Main tier 1→0 |
| Local receiver | Android WebRTC exposes generic H265 params | Do not infer that hardware is Tier0; this is metadata insufficiency |
| libwebrtc identity | Current pinned m144 H265 equality distinguishes profile/tier/tx-mode; level is not the direct identity key | Test only `tier-flag`; do not co-vary `level-id` |
| Timing | H265 disappears in raw `createAnswer()` before MediaCodec | Rewrite treatment must occur before `setRemoteDescription()` |
| Decoder | Failed baseline never created HEVC MediaCodec | Decoder factory is frozen for this A/B |
| Production | SDP rewrite changes the remote capability declaration | Diagnostic only; remove rewrite before production and advertise real local capability instead |

The v6.0.2 verdict is intentionally deferred to true-device RAW_ANSWER evidence. Static checks can prove the treatment is single-field and correctly placed, but cannot prove that GFN/libwebrtc will negotiate HEVC on the device.

## HEVC - v6.0.3 Answer lineage continuation

| Layer | True-device evidence / rule | v6.0.3 decision |
|---|---|---|
| Tier A/B | 43.log: tier-only rewrite changed RAW_ANSWER from no H265 to H265 PT 103 | tier mismatch causal proof complete |
| libwebrtc Answer | H265 PT 103 returned with `level-id=93` and no explicit profile/tier | do not classify by explicit profile-id alone |
| Main safety | Rewritten Offer PT 103 is H265 profile-id=1; PT 107 is Main10 | only intersect Answer H265 with offered Main H265 PTs |
| Dynamic PT | PT values are session-local | no hard-coded 103/107 in production logic |
| Final validation | final Answer must retain a matched Main-lineage H265 PT | keep HEVC only when lineage remains non-empty |
| Production | remote tier rewrite is still diagnostic-only | capability factory remains deferred until decode/render evidence |

---

## HEVC — v6.0.4 production capability

| Layer | Evidence / rule | gfn-android v6.0.4 decision |
|---|---|---|
| Experimental true-device baseline | `44.log`: HEVC decoder + FIRST_FRAME + ~60fps after diagnostic path | media path is known-good, but not sufficient to advertise High Tier |
| Server SDP | GFN Main candidate is `profile-id=1;tier-flag=1;level-id=153` | preserve original codec SDP; no tier rewrite |
| Android capability | `MediaCodecInfo.CodecCapabilities.profileLevels` + video capabilities | normalize explicit profile/tier/level; require hardware Main/High >= 5.1 and 1080p60 |
| Android level comparison | HEVC Main-tier and High-tier constants occupy distinct Android constants | explicit constant→normalized model mapping; never raw integer ordering |
| WebRTC capability | upstream decoder factory otherwise exports generic H265 | replace generic H265 advertisement with explicit Main/High/level only after real probe |
| Decoder component | capability can only be trusted if actual decoder is the same component | H265 creation is restricted by exact `MediaCodecInfo.name`; component name is discovered, not hardcoded |
| Safety gate | codec identity alone does not prove requested workload | require remote level <= local max plus size/rate/bitrate support |
| Preference planner | generic H265 previously caused ambiguous matching | only compatible explicit local Main/High capabilities precede H264 |
| Answer fmtp normalization | libwebrtc can omit profile/tier in Answer | retain dynamic Offer/Answer Main PT lineage, anchored to original Tier1 Offer |
| Fallback | unsupported production capability must not be faked | keep same-session H264 fallback |
| Main10/HDR | not part of v6.0.4 | frozen |
| Surface/EGL | `No surface` is independent of codec decode | separate backlog |

### v6.0.4 production PASS boundary

```text
original GFN Tier1 Offer
+ real local Main/High/Level>=5.1 capability
+ exact decoder binding
+ explicit H265 receiver capability
+ compatible Offer intersection
+ RAW/FINAL Answer H265
+ fallback=false
+ hardware HEVC decode
+ FIRST_FRAME Hevc
+ stable ~60fps
= HEVC Main / SDR8 Production PASS
```
