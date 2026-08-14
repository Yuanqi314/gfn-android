# GFN Android Reference Matrix

原则：CloudNow / OpenNOW 是 witnesses，不是 specification。第三方实现冲突时保留冲突，本项目 fixture / wire / 真机 A/B 做最终裁决。

## Keyboard — v5.1.9

| Feature | CloudNow | OpenNOW | gfn-android v5.1.9 | Verified |
|---|---|---|---|---|
| Keyboard VK | Windows VK | Windows VK | Windows VK | Yes |
| Normal scan | Windows Set-1 | `0` | Windows Set-1 | Encoder fixture |
| CapsLock | normal VK_CAPITAL | VK_CAPITAL + synthetic LSHIFT | normal VK_CAPITAL | Pending final regression |
| type19 lock sync | No | Yes | No | Removed after A/B |
| tracked modifiers | client state | DOM event/state path | InputStateTracker | Existing true-device evidence |
| Session keyboardLayout | explicit setting | explicit setting | explicit persistent setting | `en-US` Cyberpunk verified |
| Claim/Resume layout stability | session setting | session setting | frozen session snapshot | Offline fixture |
| ordered input channel | Yes | Yes | Yes | Existing true-device evidence |

### Current verdict

```text
Session keyboardLayout=en-US
= verified effective fix for the Cyberpunk 2077 A-Z/completion-string issue.

scan=0 / type19 / synthetic LSHIFT
= investigation witnesses only; not production requirements.
```

## Reconnect — v5.2.1 placeholder

| Feature | CloudNow | OpenNOW | gfn-android | Verified |
|---|---|---|---|---|
| same Session ID | TODO inspect | TODO inspect | reconcile only | - |
| recreate Session | TODO inspect | TODO inspect | must not | - |
| signaling refresh | TODO inspect | TODO inspect | TODO | - |
| DataChannel rebuild | TODO inspect | TODO inspect | TODO | - |
| input re-handshake | TODO inspect | TODO inspect | TODO | - |

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
