# 当前状态 · v5.2 Stream Settings Foundation

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
```

## Keyboard soft-freeze

Cyberpunk 2077 最终真机裁决：

```text
Session keyboardLayout=zh-CN/auto-derived Chinese
→ A-Z 进入远端 Windows completion/composition 路径

新 Session keyboardLayout=en-US
→ Caps OFF A-Z 恢复正常
```

v5.1.9 已回到 production keyboard semantics：

```text
Windows VK
+ Windows Set-1 scan
+ tracked modifiers
+ ordered input_channel_v1
```

并删除调查期的 scan=0 / type19 / Caps synthetic LSHIFT / Wire A-B。v5.2 对这些文件保持 byte-identical，Keyboard packet semantics 正式进入 soft-freeze。

## v5.2 当前重点

```text
PersistentStreamSettings
        ↓ resolve once
subscription entitlement
+ current engine capability
        ↓
ResolvedLaunchProfile
        ↓
CREATE / persist / CLAIM / WebRTC
```

当前公开设置：

```text
Keyboard Layout：Auto / en-US(default) / supported layouts
Resolution：Auto / 1920x1080
FPS：Auto / 60
Max Bitrate：20 Mbps default，5–100 Mbps client guard
Audio：Stereo 2ch
```

当前不公开：

```text
HEVC
Main10
HDR10
5.1
120 / 240 FPS
```

### 证据边界

```text
1080p60 H.264 SDR8 Stereo = 既有稳定真机路径
20 Mbps                    = 稳定默认
5–100 Mbps                 = client guard；非默认码率真机 A/B 待验证
```

## Legacy Resume

v5.1.9 及更早的 persisted Session 没有完整 `ResolvedLaunchProfile`。

v5.2 不根据当前 Settings 猜旧 Session 参数；旧记录必须 End/Cleanup 后创建新 Session。

## 下一步

v5.2 真机验证通过后：

```text
v5.2.1 Reconnect
        ↓
v5.3 Gamepad
        ↓
v5.4 Audio / 5.1
        ↓
v6.0 HEVC Main SDR8
        ↓
v6.1 Main10 SDR10
        ↓
v6.2 HDR10
```

## 构建边界

离线 resolver / persistence / controller fixtures 与 targeted Kotlin compile 已通过；完整 Android Gradle build 当前在下载 Gradle 9.5.0 前即因容器 DNS 无法解析 `services.gradle.org` 而停止，因此不能声称 APK 全工程编译通过。
