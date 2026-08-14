# v5.2 Stream Settings Foundation — True Device Test Guide

## 0. Upgrade rule

如果 App 启动后检测到 v5.1.9 或更早的可恢复 Session：

```text
不要让 v5.2 猜旧 Session 的 media profile。
先 End / Cleanup
再创建全新 Session。
```

预期 UI 会明确提示 legacy Session 缺少 `ResolvedLaunchProfile`。

---

## 1. Baseline regression

设置：

```text
Keyboard Layout = English (US)
Resolution      = Auto
FPS             = Auto
Max Bitrate     = 20 Mbps
Audio           = Stereo
```

新建 Cyberpunk 2077 Session。

必须确认：

```text
Caps OFF + A/W/S/D/L 正常
无左上角 completion-string 输入框
全屏不最小化
鼠标正常
滚轮正常
音频正常
画面 1080p60 稳定
```

再对 CS2 做 A/W/S/D 基本回归。

---

## 2. Launch snapshot log

同一 Session 中应看到语义一致的日志：

```text
GfnLaunchProfile RESOLVED ... 1920x1080@60 20Mbps audio=2ch keyboard=en-US ...
GfnLaunchProfile CREATE   ... same profile ...
GfnLaunchProfile WEBRTC   ... same profile ...
```

如果执行 Claim/Resume：

```text
GfnLaunchProfile CLAIM ... same profile ...
```

禁止出现：

```text
CREATE 20Mbps
WEBRTC 另一组配置
```

或：

```text
CREATE keyboard=en-US
CLAIM keyboard=zh-CN
```

---

## 3. Settings immutability test

建立 Session A：

```text
20 Mbps
en-US
```

Session A 已创建后，到设置把“下一 Session”改成：

```text
35 Mbps
zh-CN
```

如果保留/Resume Session A：

```text
CLAIM / WEBRTC 仍必须使用 Session A 原 snapshot：20 Mbps + en-US
```

不能读取当前新 Settings。

Session A 完全结束后，新建 Session B 才应采用新设置。

> 注意：为了避免重新触发已知 Cyberpunk 中文 layout 问题，真正游戏回归建议继续用 `en-US`。`zh-CN` 只适合在可控场景验证 snapshot 不变性，或直接只看持久化/日志。

---

## 4. Bitrate A/B

该实验只改变一个变量：Max Bitrate。

### A

```text
20 Mbps
```

记录：

```text
GfnLaunchProfile
SDP answer bandwidth
NVST video.initialPeakBitrateKbps
vqos.bw.maximumBitrateKbps
vqos.bw.peakBitrateKbps
实际画面稳定性 / stats
```

### B

结束 Session A，设置：

```text
35 Mbps
```

创建全新 Session B，记录同样信息。

判断：

```text
如果 wire 字段切到 35000 且 Session 稳定：
  证明 client → signaling/NVST 参数链生效。

如果服务器忽略/回退：
  记录实际行为，不把 35 Mbps 标为 VERIFIED。

如果出现失败：
  回退 20 Mbps，保留日志定位具体 negotiation 层。
```

不要一次跳到 100 Mbps；从 20 → 35 做单变量验证。

---

## 5. Resolution / FPS / Audio UI test

当前 capability 只公开：

```text
1920x1080
60 FPS
Stereo 2ch
```

所以 UI 的意义主要是证明 settings foundation 和 future expansion seam：

```text
Resolution: Auto / 1920x1080
FPS:        Auto / 60
Audio:      Stereo
```

如果 UI 出现 HEVC、HDR、5.1、120 FPS，则属于 v5.2 回归失败。

---

## 6. Pass criteria

v5.2 通过条件：

```text
[ ] Cyberpunk en-US keyboard regression PASS
[ ] CS2 keyboard regression PASS
[ ] 1080p60 H264 SDR8 Stereo stream PASS
[ ] CREATE and WEBRTC use exact same snapshot
[ ] Claim/Resume uses original snapshot
[ ] Settings changes only affect next new Session
[ ] 20 → 35 Mbps A/B produces expected wire change, or clearly records server rejection/fallback
[ ] no HEVC/Main10/HDR/5.1/120 fake UI
```

通过后进入 v5.2.1 Reconnect。
