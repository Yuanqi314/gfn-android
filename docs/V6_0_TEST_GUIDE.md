# v6.0 — HEVC Main / SDR8 True-Device Test Guide

## 1. 前置条件

使用已经验证稳定的同一设备、账号、区域和游戏。

保持除 codec 以外的全部参数一致。推荐直接沿用当前稳定模板，例如：

```text
1920x1080
60 FPS
100 Mbps
Keyboard en-US
Game language zh_CN
Audio：保持同一个已知可用模式
```

如果当前稳定模板使用 6ch，则 H.264 对照组和 HEVC 组都使用 6ch；不要同时改变音频模式。

## 2. H.264 对照组

```text
Video codec = H.264 · SDR8
结束旧 Session
创建全新 Session
进入游戏并等待稳定画面
```

记录：

```text
Requested codec
Negotiated codec
Offer H264 PT
Answer H264 PT
First RTP
First frame
```

预期：

```text
requested=H264
negotiated=H264
fallback=false
firstRtp=true
firstFrame=true
```

## 3. HEVC 实验组

```text
结束 H.264 Session
Video codec = HEVC Main · SDR8
创建全新 Session
```

不要 Resume 上一个 H.264 Session，因为 `ResolvedLaunchProfile` 在 Session 生命周期内被冻结。

## 4. HEVC 真正成功判据

必须同时满足：

```text
Requested codec      Hevc
Local decoder codecs 包含 H265/HEVC
Offer HEVC Main PT   非空
Answer HEVC Main PT  非空
Negotiated codec     Hevc
Codec fallback       NO
First RTP            YES
First frame          YES
```

然后检查：

```text
画面颜色正常
无绿色/紫色/花屏
无持续黑屏
分辨率正常
键盘鼠标仍正常
audio 与对照组一致
```

## 5. 只出现画面不算 HEVC 成功

如果 diagnostics 为：

```text
Requested codec = Hevc
Negotiated codec = H264
Codec fallback = YES
```

即使画面完全正常，也只能证明 fallback 正常，不能证明 HEVC 解码成功。

必须保留 `Codec fallback reason`。

## 6. 三类预期 fallback

### 本机没有 H265 decoder capability

```text
Local decoder codecs 不含 H265/HEVC
→ same-session H264 fallback
```

### GFN Offer 没有显式 Main

```text
Offer 有 H265 但 HEVC Main PT 为空
→ 不猜 profile
→ same-session H264 fallback
```

### createAnswer 没接受 Main

```text
Offer HEVC Main 非空
local H265 capability 有
Answer HEVC Main 为空
H264 Answer 仍可用
→ same-session H264 fallback
```

## 7. 失败需要提供的最小日志

```text
GfnLaunchProfile
GfnWebRtc / SDP Offer summary
Requested / Negotiated codec
Local decoder codecs
Offer H264 / HEVC / HEVC Main PT
Answer H264 / HEVC / HEVC Main PT
Codec fallback reason
ICE / PeerConnection state
First RTP / First frame
```

不要只提供“黑屏”一句；必须能判断失败发生在：

```text
local capability
Offer intersection
Answer intersection
ICE/transport
decoder/frame
```
