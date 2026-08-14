# v5.2 Reference Adoption

## Rule

CloudNow / OpenNOW 是 witnesses，不是 specification。

v5.2 只采用两边都能支持的工程结论，不复制尚未由本项目证据证明的高级能力。

---

## CloudNow witness

参考 commit：

```text
f9292868369b0fe41a2d559d0c8f3805193f4389
```

`CloudNow/Session/SessionState.swift` 有独立、可持久化的 `StreamSettings`：

```text
resolution
fps
maxBitrateKbps
codec
colorPreference
keyboardLayout
gameLanguage
audioFormat
...
```

并且新字段解码使用 default fallback，避免旧 settings 文件缺字段时整份设置重置。

v5.2 采用的不是 Swift 结构本身，而是两个原则：

```text
1. stream intent 是 persistent model
2. keyboard layout 与 game language 是独立 setting
```

CloudNow 当前存在 100000 kbps client-side selectable ceiling；本项目只把这作为设置 guard 的 witness，不宣称它等于 Android 真机已验证服务端上限。

---

## OpenNOW witness

参考 commit：

```text
9299ac5109916c1c1f4b41f7fe7fd944acdb7acb
```

`opennow-stable/src/shared/gfn/settings.ts` 的 `Settings` 同样包含：

```text
resolution
fps
maxBitrateMbps
codec
colorQuality
keyboardLayout
gameLanguage
...
```

默认值中可见：

```text
1920x1080
60 FPS
75 Mbps
keyboardLayout = DEFAULT_KEYBOARD_LAYOUT
```

这些值只证明 OpenNOW 的实现选择，**不是** GFN Android 的协议规格。

v5.2 采用的共同原则是：

```text
settings 在 Session 之前明确存在
keyboard layout 与 media settings 都是 launch intent
```

没有因为 OpenNOW 暴露 codec/color choices，就在本项目提前开启 HEVC/Main10/HDR。

---

## gfn-android independent decision

本项目当前真机已经证明：

```text
H.264 SDR8 / 1080p60 / Stereo
CloudMatch → WSS → SDP → ICE → RTP → Decode → Surface
```

键盘真机又证明：

```text
Session keyboardLayout=en-US
```

是 Cyberpunk 2077 输入问题的有效变量。

因此 v5.2 自己定义：

```text
PersistentStreamSettings
        ↓
resolve against entitlement + current engine capability
        ↓
immutable ResolvedLaunchProfile
```

这不是 CloudNow/OpenNOW 某一方的逐行移植，而是为了消除本项目已有的两个 truth source。

---

## Explicitly NOT adopted

```text
HEVC selection
Main10
HDR10
5.1
120/240 FPS
Cloud G-Sync
L4S
runtime live renegotiation
```

原因：v5.2 没有足够的本项目真机/wire 闭环证据。

这些能力继续按独立版本、单变量实验引入。
