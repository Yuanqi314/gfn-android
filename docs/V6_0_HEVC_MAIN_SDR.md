# v6.0 — HEVC Main / SDR8

## 1. 目标

v6.0 只新增一个视频变量：**HEVC Main（`profile-id=1`）+ SDR8**。

稳定基线继续保留：

```text
1920x1080 @ 60 FPS
H.264
CompatibilitySdr
2ch 或已验证可播放的 experimental multiopus/6ch
5-100 Mbps client guard
```

本版不把以下能力混进同一次实验：

```text
HEVC Main10 / profile-id=2
10-bit
HDR10
AV1
120 FPS
自定义 MediaCodec renderer
H.265 tier / level 强制重写
```

## 2. 配置链

`PersistentStreamSettings` 新增 `videoCodec`，默认仍是 `H264`。

```text
Settings.videoCodec
        ↓ resolve once
ResolvedLaunchProfile.streamConfig.codec
        ↓ immutable for Session lifetime
CREATE / persist / CLAIM / WebRTC / Reconnect
```

用户可选：

```text
H.264 · SDR8（稳定 fallback）
HEVC Main · SDR8
```

`RequestedColorMode` 仍固定为 `CompatibilitySdr`，因此选择 HEVC 不会隐式开启 Main10/HDR。

## 3. 本地 decoder capability

`GfnWebRtcRuntime` 只创建一个 `DefaultVideoDecoderFactory`，并同时：

1. 用其 `supportedCodecs` 记录本机当前 libwebrtc decoder capability 名称；
2. 把同一个 factory 交给 `PeerConnectionFactory`。

这避免了“检测用一个 factory、真正协商又用另一个 factory”的状态漂移。

本版只把 `H265` / `HEVC` capability 名称视作本地 HEVC 可用证据；**不根据机型名单猜测**。

## 4. Offer 决策

收到 GFN Offer 后先解析第一条 video m-line：

```text
H.264 PT
H.265/HEVC PT
H.265 Main PT（必须显式 fmtp profile-id=1）
```

如果用户请求 H.264：

```text
Offer 有 H.264 → H.264
Offer 无 H.264 → fail
```

如果用户请求 HEVC Main：

```text
local decoder advertises H265/HEVC
+ Offer has explicit H265 profile-id=1
        ↓
HEVC Main
```

否则只要同一 Offer 仍有 H.264：

```text
same Session fallback → H.264
```

如果两者都没有可用交集，则明确失败，不制造 payload。

## 5. Answer 决策

libwebrtc 正常 `createAnswer` 后，再从真实 Answer 形成两个候选：

### HEVC candidate

仅保留：

```text
H265 profile-id=1
其 RTX apt
既有 repair PT（RED / ULPFEC / FLEXFEC）
```

不会保留 `profile-id=2`。

### H.264 candidate

复用 v5.4 已验证的 H.264 Answer filter 语义：

```text
H264
其 RTX apt
既有 repair PT
```

如果 Offer 阶段选了 HEVC，但 `createAnswer` 实际没有接受 HEVC Main，而 H.264 仍存在，则同一 Session 回退 H.264，并在 diagnostics 中记录原因。

## 6. CloudMatch 冻结

v6.0 **没有修改 CloudMatch 视频请求语义**。

原因：本版目标是单变量 codec negotiation。当前 H.264 production Session 已经使用稳定的 `requestedStreamingFeatures` / SDR8 参数；把 `chromaFormat`、`bitDepth`、显示能力或其他 CloudMatch 字段一起改掉会破坏归因。

因此：

```text
CloudMatch color/requestedStreamingFeatures = v5.4 原样
codec preference                             = SDP/WebRTC 层处理
```

## 7. Diagnostics

新增/扩展：

```text
Requested codec
Negotiated codec
Local decoder codecs
Codec fallback
Codec fallback reason

Offer HEVC PT
Offer HEVC Main PT
Answer HEVC PT
Answer HEVC Main PT

First RTP
First frame
Frame size
Decoder path（只描述 factory/codec；具体硬件/软件 decoder 待真机）
```

判断 HEVC 是否真的生效，不能只看“有画面”。必须同时满足：

```text
Requested codec      = Hevc
Local decoder codecs = 包含 H265 或 HEVC
Offer HEVC Main PT   = 非空
Answer HEVC Main PT  = 非空
Negotiated codec     = Hevc
Codec fallback       = false
First RTP            = true
First frame          = true
```

## 8. H.264 fallback

HEVC 失败时若 H.264 交集仍存在，不创建第二 Session：

```text
same Session
same ResolvedLaunchProfile identity
same audio / resolution / fps / bitrate / keyboard
        ↓
video codec effective fallback = H264
```

Diagnostics 会给出以下之一：

```text
本机 DefaultVideoDecoderFactory 未声明 H265 decoder
GFN Offer 未包含显式 HEVC Main(profile-id=1)
libwebrtc createAnswer 未接受 HEVC Main
```

## 9. 已知独立 backlog

以下问题不属于 v6.0 HEVC 变更：

```text
v5.2.1 第一次 reconnect 可能黑屏，第二次 reconnect 可恢复
v5.3 Gamepad 无设备，true-device skipped
v5.4 6ch 已确认可正常播放，但离散 5.1 声道分离未验证
```

## 10. 构建边界

当前容器可验证：

```text
纯 Kotlin SDP fixture
codec policy fixture
settings/store fixture
WebRTC API-shaped compile
v5.4 Audio regression
v5.3 Gamepad regression
v5.1.9 Keyboard regression
```

完整 Android Gradle build 仍取决于 Gradle 9.5.0 wrapper 可用性；如果 wrapper 无缓存且网络不可达，不能声称 APK 全工程构建通过。
