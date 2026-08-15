# v6.0 — CloudNow / OpenNOW Reference Adoption

固定参考版本：

```text
CloudNow  f9292868369b0fe41a2d559d0c8f3805193f4389
OpenNOW   9299ac5109916c1c1f4b41f7fe7fd944acdb7acb
```

原则：两个仓库是 witness，不是规范。只吸收可被本项目现有 wire/fixture/真机链验证的共同语义。

## 1. CloudNow 证据

### `GFNVideoDecoderFactory.swift`

CloudNow 明确说明其默认 LiveKit decoder factory 已有 H.265 Main (`profile-id=1`)；自定义 factory 的额外工作主要是把 `profile-id=2` Main10 保留下来，并为 HDR/Main10 使用独立 H.265 decoder。

v6.0 只做 SDR8 Main，因此采用：

```text
DefaultVideoDecoderFactory
+ H265 Main capability inspection
```

不采用 CloudNow 的 Main10/HDR decoder 扩展。

### `SDPMunger.swift`

CloudNow 的 H.265 SDR 路径把 `profile-id=1` 作为优先 profile，并保留 H.264 fallback 逻辑。

采用的语义：

```text
HEVC/H265 name normalization
HEVC SDR => prefer profile-id=1
retain linked RTX
H264 remains fallback
```

没有复制其完整 SDP 实现阶段；gfn-android 继续在自己既有 Answer-munging 架构内实现，以避免重写已验证 H.264/Audio 流程。

### `CloudMatchClient.swift`

CloudNow 当前代码明确把 codec preference 留在 SDP 处理层，而不是额外写进 CloudMatch session JSON。

采用：v6.0 不修改本项目 CloudMatch codec/color request。

## 2. OpenNOW 证据

### `shared/gfn/stream.ts`

OpenNOW 将视频 codec 与 color quality 分离：H265 可以和 8-bit 4:2:0 独立组合，Main10/HDR 并不是启用 H265 的必然结果。

采用：

```text
HEVC Main + CompatibilitySdr
```

而不是“HEVC = HDR”。

### `sdp/codec.ts`

OpenNOW：

```text
HEVC -> H265 normalization
H265 可按 profile-id 排序
profile-id=1 用于兼容性优先
H264 fallback 可保留
```

采用这些选择边界。

未采用：

```text
rewriteH265TierFlag
rewriteH265LevelIdByProfile
```

因为当前 gfn-android 尚没有真机证据证明服务端的 tier/level 会导致 Android decoder 失败。提前改写会增加第二变量。

### `webrtc/codecPreferences.ts`

OpenNOW 会先查看 receiver codec capabilities，再构造 preference list。

采用其核心原则：

```text
本地 decoder capability
+ 实际 Offer
+ 实际 Answer
= 最终 codec
```

gfn-android 没有照搬浏览器 `RTCRtpTransceiver.setCodecPreferences` API，而是在 Android libwebrtc 当前已有的 SDP Answer 路径里实现同一判断边界。

## 3. 两仓库共同支持的 v6.0 最小集合

| 项 | CloudNow | OpenNOW | gfn-android v6.0 |
|---|---|---|---|
| H.265/HEVC selectable | Yes | Yes | Yes |
| SDR HEVC | Yes | Yes | Yes |
| Main/profile-id=1 | SDR preference | compatibility preference | **required explicitly** |
| local decode capability check | decoder factory path | receiver capabilities | `DefaultVideoDecoderFactory.supportedCodecs` |
| H.264 fallback | Yes | Yes | Yes, same Session |
| Main10/HDR in same milestone | implemented separately | separate color quality | **No** |
| AV1 in same milestone | supported elsewhere | supported | **No** |
| CloudMatch codec field required | No | not adopted as requirement | **No change** |

## 4. 明确拒绝的推断

不能从参考仓库推出：

```text
所有 Android 设备必然支持 H265
GFN 每个节点必然 Offer profile-id=1
有 H265 PT 就等价于 Main
HEVC 有画面就证明未 fallback
HEVC 必然硬件解码
HEVC 必然 10-bit/HDR
某个固定 tier/level 改写对 Android 必需
```

这些全部由 v6.0 diagnostics / 真机结果裁决。
