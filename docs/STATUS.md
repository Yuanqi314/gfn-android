# 当前状态 · v6.0.2 HEVC Main Tier-Flag A/B

## 真机已确认

```text
Auth / restart restore                         ✅
Membership / Library / Catalog                 ✅
Search / Game Detail                           ✅
CloudMatch Create / Provision                  ✅
Claim / RESUME                                 ✅
GFN WebSocket / SDP / ICE                      ✅
H.264 RTP / Decode / Surface                   ✅
Audio playback                                 ✅
Wheel direction                                ✅
Fullscreen landscape / aspect fit              ✅
control_channel Session End                    ✅
Keyboard / Mouse stable baseline               ✅
Cyberpunk 2077 keyboardLayout=en-US fix        ✅
CS2 keyboard regression                        ✅
Stream settings snapshot                       ✅
1920x1080@60 H.264 / 2ch / 100 Mbps            ✅ 当前环境
experimental 6ch mode audio playback           ✅ 当前环境
same-session reconnect keeps Session ID        ✅
```

`6ch mode audio playback ✅` 只表示开启 6ch 后串流音频可正常播放；**没有做离散 5.1 声道素材验证，因此不能标记为 native/discrete 5.1 verified。**

## v5.2.1 Reconnect 已知缺陷

真机已确认：断网后 recovery 保持同一个 Session ID。

仍保留独立 backlog：

```text
第一次 reconnect → 可能持续黑屏
再次断开 / reconnect → 可恢复画面
```

用户已决定暂不处理；v6.0 不修改该视频 reconnect 生命周期。

## Keyboard soft-freeze

生产语义继续保持：

```text
Windows VK
+ Windows Set-1 scan
+ tracked modifiers
+ ordered input_channel_v1
```

Cyberpunk 2077 已验证修复是新 Session `keyboardLayout=en-US`。v6.0 不修改 keyboard packet semantics。

## v5.3 Gamepad

```text
IMPLEMENTED                          ✅
OFFLINE PACKET / CONTROLLER FIXTURE  ✅
TRUE-DEVICE                          SKIPPED（当前没有可用手柄）
```

这不是失败结论。

## v5.4 Audio

### 2ch

```text
Opus stereo=1
+ JavaAudioDeviceModule.setUseStereoOutput(true)
```

### 6ch

```text
CloudMatch 6ch request
→ GFN multiopus/48000/6
→ Answer repair when required
→ current Android ADM configured 2ch
```

真机结果：**开启 6ch 后音频播放正常。**

尚未验证：

```text
discrete 5.1 channel separation
native 6-channel Android playout
```


## v6.0.1 真机裁决 / v6.0.2 当前目标

真机已经确认：

```text
Requested codec = Hevc
ResolvedLaunchProfile = Hevc
GFN Offer H265 Main = profile-id=1;tier-flag=1;level-id=153
Local receiver H265 = generic params={}
setCodecPreferences = applied=true
RAW_ANSWER HEVC = empty
Negotiated codec = H264
Codec fallback = YES
Reason = libwebrtc createAnswer 未接受 HEVC Main；同 Session 回退 H264
Actual decoder = H264
```

因此 H264 fallback PASS，但 HEVC Main negotiated/decoded/rendered 尚未 PASS。v6.0.2 只做首个 video m-line 的 H265 Main `tier-flag=1→0` 单字段 A/B，并且 rewrite 位于 `setRemoteDescription()` 之前。第一验收点是 RAW_ANSWER 是否出现 H265 Main，不是“有没有画面”。

本版仍禁止同时修改：

```text
level-id
profile-id
Main10/HDR/AV1
H264 fallback
decoder factory
CloudMatch / NVST
```

统一 Logcat tag：`GfnHevcCompat`。实验成功后，rewrite 必须在 production 方案中移除，后续改为 Android `MediaCodecInfo.CodecProfileLevel` 真能力探测 + 准确 capability advertisement。

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
