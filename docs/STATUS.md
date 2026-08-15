# 当前状态 · v5.4 Audio Foundation

## 真机已确认

```text
Auth / restart restore                         ✅
Membership / Library / Catalog                 ✅
Search / Game Detail                           ✅
CloudMatch Create / Provision                  ✅
Claim / RESUME                                 ✅
GFN WebSocket / SDP / ICE                      ✅
H.264 RTP / Decode / Surface                   ✅
Audio 有声（旧 2ch 路径）                       ✅
Wheel direction                                ✅
Fullscreen landscape / aspect fit              ✅
control_channel Session End                    ✅
Keyboard / Mouse stable baseline               ✅
Cyberpunk 2077 keyboardLayout=en-US fix        ✅
CS2 keyboard regression                        ✅
Stream settings snapshot                       ✅
1920x1080@60 H.264 / 2ch / 100 Mbps            ✅ 当前环境
same-session reconnect keeps Session ID        ✅
```

## v5.2.1 Reconnect 已知缺陷

真机已确认：断网后自动重连界面中的 Session 没有变化，same-session recovery 主约束成立。

已知未修问题：

```text
第一次 reconnect → 可能持续黑屏
再次断开 / reconnect → 可恢复画面
```

按当前开发决定继续保留为独立 backlog，不混入 v5.4 Audio。

## Keyboard soft-freeze

生产语义保持：

```text
Windows VK
+ Windows Set-1 scan
+ tracked modifiers
+ ordered input_channel_v1
```

Cyberpunk 2077 的已验证修复继续是 Session `keyboardLayout=en-US`。v5.4 不修改键盘 packet semantics。

## v5.3 Gamepad

状态：

```text
IMPLEMENTED                          ✅
OFFLINE PACKET / CONTROLLER FIXTURE  ✅
TRUE-DEVICE                          SKIPPED（当前没有可用手柄）
```

这不是失败结论。后续获得手柄时可直接按 `docs/V5_3_TEST_GUIDE.md` 补测。

## v5.4 Audio

### 2ch production path

```text
CloudMatch audioChannels=2
        ↓
GFN Offer / Opus
        ↓
Answer: Opus stereo=1（存在 Opus fmtp 时）
        ↓
JavaAudioDeviceModule.setUseStereoOutput(true)
        ↓
Android media route (USAGE_GAME / CONTENT_TYPE_MUSIC)
        ↓
ADM 2ch stereo configuration
```

v5.4 修正了一个实际架构问题：旧 `JavaAudioDeviceModule` 没有启用 `setUseStereoOutput(true)`，因此不能把旧“有声”直接等价为真正 2ch native stereo。

### 6ch experimental path

```text
PersistentStreamSettings.audioChannels=6
        ↓ immutable ResolvedLaunchProfile
CloudMatch audioMode / surroundAudioInfo = 6ch request
        ↓
GFN Offer 必须出现 multiopus/48000/6
        ↓
若 libwebrtc createAnswer 拒绝该 audio m-line：
只重建第一条 game-audio section
复用 Offer exact multiopus fmtp
复用 Answer bundle transport
        ↓
setLocalDescription / server negotiation
        ↓
Android upstream Java ADM local playout = 2ch
```

**边界：v5.4 的 6ch 是 multiopus negotiation / receive / 2ch-ADM probe，不是 native 5.1 输出。**

当前 profile 明确区分：

```text
audioChannels              = {2, 6}     # 可请求
nativeAudioOutputChannels  = {2}        # 可原样输出
```

如果要真正输出 5.1，需要后续独立实现自定义 Android AudioDeviceModule / PCM playout 路径，不能通过把 UI 写成“5.1”来伪装完成。

## Stream Settings snapshot

继续保持：

```text
PersistentStreamSettings
        ↓ resolve once
ResolvedLaunchProfile
        ↓ immutable for Session lifetime
CREATE / persist / CLAIM / WebRTC / Reconnect
```

Audio 2ch/6ch 同样属于 snapshot；活动 Session 中改设置只影响下一新 Session。

## 后续顺序

```text
v5.4 Audio true-device stereo / 6ch probe（可按设备条件补测）
        ↓
v6.0 HEVC Main SDR8
        ↓
v6.1 Main10 SDR10
        ↓
v6.2 HDR10
```

Reconnect 首次黑屏与 v5.3 Gamepad 真机验证均继续作为独立 backlog，不阻塞 v6.0。

## 构建边界

纯 Kotlin SDP/settings fixtures、Android/WebRTC API-shaped compile、keyboard/gamepad/reconnect regression 可在当前容器验证。完整 Android Gradle build 仍受 Gradle 9.5.0 未缓存且 `services.gradle.org` DNS 不可用限制；不能声称最终 APK 全工程编译通过。
