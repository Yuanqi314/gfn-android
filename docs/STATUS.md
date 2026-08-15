# Current v6.0.4 HEVC Main production candidate

## 已确认的真机基线

v6.0.3 `44.log` 已完成实验路径的 negotiated + decoded + rendered 闭环：

```text
effective=Hevc
→ c2.qti.hevc.decoder
→ FIRST_FRAME effective=Hevc
→ sustained ~60fps decode/render
```

因此 HEVC Main / SDR8 的设备媒体路径已经证明可运行，但 v6.0.3 仍依赖 `tier-flag=1 -> 0` diagnostic Offer rewrite，不能作为 production 方案。

## v6.0.4 当前实现

```text
GFN original Main / High-Tier Offer
→ no H265 fmtp rewrite
→ MediaCodec profileLevels probe
→ explicit normalized profile/tier/level
→ exact decoder-component binding
→ explicit WebRTC H265 Main/High advertisement
→ profile+tier+tx-mode+level+stream safety intersection
→ createAnswer
→ HEVC or same-session H264 fallback
```

生产安全门要求真实硬件 decoder 同时满足 HEVC Main、High Tier、Level >= 5.1、请求的 size/rate 以及 bitrate range。不能因为 v6.0.3 实验解码成功就无条件宣告 High Tier。

Main10/HDR 继续冻结；`EglRenderer: Dropping frame - No surface` 继续作为独立 Surface/EGL lifecycle backlog。

## v6.0.4 真机下一验收点

新 Session 首先检查：

```text
HEVC_PRODUCTION_ADVERTISEMENT enabled=true
LOCAL_RECEIVER H265 profile-id=1;tier-flag=1
OFFER_HEVC_COMPATIBLE compatible=true streamSafe=true
RAW_ANSWER HEVC != empty
FINAL_ANSWER HEVC != empty
DECISION effective=Hevc fallback=false
HEVC hardware decoder
FIRST_FRAME effective=Hevc
~60fps stable
```

如果 production probe 没有证实本机 High Tier >= 5.1，则 H264 fallback 是预期安全行为，不再使用 Tier0 rewrite 绕过。

## v6.0 HEVC Main / SDR8

### 新增能力

```text
Video codec:
  H.264 · SDR8（default / stable fallback）
  HEVC Main · SDR8
```

唯一新增视频变量是 HEVC Main：

```text
profile-id=1
CompatibilitySdr
1920x1080@60
```

明确未启用：

```text
profile-id=2 / Main10
10-bit
HDR10
AV1
120 FPS
H265 tier/level forced rewrite
```

### 决策链

```text
PersistentStreamSettings.videoCodec
        ↓ resolve once
ResolvedLaunchProfile.streamConfig.codec
        ↓
local DefaultVideoDecoderFactory capabilities
+ actual GFN Offer
        ↓
H264 or explicit H265 Main(profile-id=1)
        ↓ createAnswer
actual Answer intersection
        ↓
selected codec
or same-session H264 fallback
```

HEVC 成功必须由 diagnostics 同时确认：

```text
requested=Hevc
local codecs includes H265/HEVC
offer HEVC Main PT != empty
answer HEVC Main PT != empty
negotiated=Hevc
fallback=false
firstRtp=true
firstFrame=true
```

“有画面”本身不是 HEVC 成功证据，因为 v6.0 允许 H.264 fallback。

### CloudMatch 边界

v6.0 没有修改 v5.4 的 CloudMatch color/requestedStreamingFeatures 语义。codec preference 只在 Settings snapshot + SDP/WebRTC 层处理，避免同时改变第二个协议变量。

## Stream Settings snapshot

继续保持：

```text
PersistentStreamSettings
        ↓ resolve once
ResolvedLaunchProfile
        ↓ immutable for Session lifetime
CREATE / persist / CLAIM / WebRTC / Reconnect
```

活动 Session 中修改 codec 只影响下一次新 Session。

## 后续顺序

```text
v6.0 HEVC Main SDR8 true-device
        ↓
v6.1 Main10 SDR（不启 HDR）
        ↓
v6.2 HDR10
```

Reconnect 首次黑屏、v5.3 Gamepad true-device、离散 5.1 分离验证继续作为独立 backlog，不阻塞 HEVC 主线。

## 构建边界

当前容器可执行 pure Kotlin SDP/policy/settings fixtures、WebRTC API-shaped compile 与既有 keyboard/gamepad/audio regressions。完整 Android Gradle build 仍受 Gradle 9.5.0 wrapper 缓存/网络条件限制；未真正进入 Android Gradle compile 前不能声称 APK 全工程编译通过。
