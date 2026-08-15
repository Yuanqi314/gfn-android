# 当前状态 · v5.3 Single-Controller Gamepad

## 真机已确认

```text
Auth / restart restore                         ✅
Membership / Library / Catalog                 ✅
Search / Game Detail                           ✅
CloudMatch Create / Provision                  ✅
Claim / RESUME                                 ✅
GFN WebSocket / SDP / ICE                      ✅
H.264 RTP / Decode / Surface                   ✅
Audio 有声                                     ✅
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

同时存在已知未修问题：

```text
第一次 reconnect → 可能持续黑屏
再次断开 / reconnect → 可恢复画面
```

按当前开发决定，该缺陷先记录，不混入 v5.3 Gamepad。后续需要单独做 reconnect video-path 取证，不能用 gamepad 修改顺带“修”。

## Keyboard soft-freeze

生产语义保持：

```text
Windows VK
+ Windows Set-1 scan
+ tracked modifiers
+ ordered input_channel_v1
```

Cyberpunk 2077 的已验证修复继续是 Session `keyboardLayout=en-US`。没有新的可重复真机证据时，不修改 keyboard packet semantics。

## v5.3 Gamepad

当前实现：

```text
Android SOURCE_GAMEPAD / SOURCE_JOYSTICK
        ↓
GfnGamepadInputController
        ↓
单 controller slot 0
XInput-style state
        ↓
GFN type12
        ↓
reliable input_channel_v1
```

第一阶段支持：

```text
ABXY
D-Pad
LB/RB
LT/RT
Start/Back
L3/R3
Left/Right Stick
```

协议与映射离线 fixture 已通过；真机 controller 行为尚待验证。

当前明确不支持：

```text
multi-controller
rumble / haptics / type13
partially reliable gamepad transport
DualSense touchpad / gyro / special reports
```

## Stream Settings snapshot

已保持：

```text
PersistentStreamSettings
        ↓ resolve once
ResolvedLaunchProfile
        ↓ immutable for Session lifetime
CREATE / persist / CLAIM / WebRTC / Reconnect
```

v5.3 不重新读取活动 Session 的 settings，也不修改已验证的 `keyboardLayout` snapshot 行为。

## 后续顺序

v5.3 真机通过后：

```text
v5.4 Audio / 5.1
        ↓
v6.0 HEVC Main SDR8
        ↓
v6.1 Main10 SDR10
        ↓
v6.2 HDR10
```

Reconnect 首次黑屏缺陷单独保留为 bug backlog，可在不污染上述功能版本的情况下另开修复分支。

## 构建边界

纯 Kotlin packet/controller fixtures、keyboard regression 与 WebRTC engine API-shaped compile 可以在当前容器验证。完整 Android Gradle build 仍受 Gradle 9.5.0 未缓存且 `services.gradle.org` DNS 不可用限制；不能声称最终 APK 全工程编译通过。
