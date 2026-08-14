# GFN Android Lab · v5.1.4 Keyboard Wire A/B 实验版

这是一个独立 Android GeForce NOW 客户端实验工程。当前真实 Android 设备已确认：**CloudMatch / Claim → GFN WSS → SDP → ICE → H.264 RTP → Decode → Surface 画面**成立；v5.1 已加入全屏键鼠与 `releaseAll(reason)` 状态机。v5.1.1 根据最新真机日志与实测问题，只修复当前层的 5 项可定位问题，不重写已经成功的媒体协议链。

> 仅使用用户自己的合法 GeForce NOW 账号；不修改订阅等级、账号 entitlement 或服务端授权。


## v5.1.4 本轮新增

v5.1.3 真机取证已把 W / N / K / G 的 Android dispatch、modifier、VK/Set-1 mapping、protocol=3、28-byte framing、ByteBuffer 和 binary DataChannel send 基本确认正常；四个字母仍会让远端全屏游戏窗口最小化。

v5.1.4 因此只做单变量 Keyboard Wire A/B：

```text
A / SCAN_SET1
wire scan = mapped Windows Set-1 scan

B / VK_ONLY_SCAN_ZERO
wire scan = 0x0000
```

基础 `stream-input/GfnInputPacketEncoder` 与 `AndroidKeyboardMapper` 保持 v5.1.3 SHA 不变；B 只在最终 wire packet 上清零 scan 两字节。默认仍为 A。切换只允许在全屏 Overlay 打开且 held keys 清零后执行，并继续保留 v5.1.3 `eventSeq` 全链路日志。

详细见：

```text
docs/V5_1_4_KEYBOARD_WIRE_AB.md
docs/V5_1_4_TEST_GUIDE.md
docs/V5_1_4_REFERENCE_ADOPTION.md
```

## v5.1.3 本轮新增

当前真机已确认：音频输出恢复、滚轮方向、自动横屏/画面适配、游戏退出自动 Session End 均已通过；v5.1.2 的扬声器/媒体音量路由尚未收到本轮真机复测结果。当前键盘问题是：普通字母（最新明确为 K，且疑似所有字母）会让远端全屏游戏窗口最小化。

v5.1.3 明确定义为：

```text
Input Forensics Only
```

只增加：

```text
eventSeq
Activity dispatch PRE/POST
raw Android KeyEvent
Mapper / modifier / active gate diagnostics
input_channel_v1 state
raw server handshake / parse rule / protocolVersion
final DataChannel ByteBuffer exact hex
position / limit / remaining / binary / sendAccepted
```

本版禁止修改 VK/scanCode、modifier 语义、endianness、packet size、v2/v3 framing、DataChannel 配置、releaseAll/epoch、mouse、CloudMatch/WSS/SDP/ICE/H.264。K 仍为 `VK=0x004B / scan=0x0025`。

详细见：

```text
docs/V5_1_3_INPUT_FORENSICS.md
docs/V5_1_3_TEST_GUIDE.md
docs/V5_1_3_REFERENCE_ADOPTION.md
```

## v5.1.1 本轮范围

```text
1. Android 鼠标滚轮方向：按真机结果修正 sign
2. Fullscreen：recreation-safe ownership + landscape best-effort + immersive
3. 手动横屏回主页：ViewModel 持有 Session/WebRTC owner，UI route 使用 rememberSaveable
4. 游戏退出识别：control_channel / exitMessage + generation/idempotence guard
5. 无声音：启用已经存在的 remote WebRTC AudioTrack
6. 登录记录偶发消失：只加 reason-only diagnostics，不改存储/清理策略
```

参考文档作为可靠性检查清单使用，但项目自己的层次保持不变：

```text
Auth / Content
CloudMatch / Session
Signaling
WebRTC
Input
UI
```

## 真机证据对应的修复

### Audio

最新 logcat 已证明 Android WebRTC playout 设备实际成功：48 kHz AudioTrack 创建并 `startPlayout`，退出时 `underrun count=0` 且已有大量 frames delivered。源码同时确认 v5.1 在收到 remote `AudioTrack` 后主动 `setEnabled(false)`。v5.1.1 删除这个视频-only 冻结开关，改为 `setEnabled(true)`，并增加 Audio diagnostics。

这只是“恢复服务器已经协商出的远端音轨播放”，不是 v5.2 的音频质量/多声道重构。

### Orientation / Activity recreation

最新 logcat 在手动横屏时出现：旧 MainActivity Window dying，随后同一进程创建新的横屏 MainActivity/DecorView，并重新触发本地登录恢复。v5.1 的 Controller 和 `fullscreenStream` 原先属于 Composable `remember`，因此会随 Activity recreation 丢失 UI ownership。

v5.1.1 改为：

```text
GfnAppRuntimeViewModel
├── AuthController
├── GfnContentController
├── GfnSessionController
└── GfnStreamingController
```

ViewModel 在 configuration recreation 中继续持有 Session/WebRTC；app 显式依赖 `androidx.lifecycle:lifecycle-viewmodel:2.11.0`；`tab/fullscreen` 使用 `rememberSaveable` 恢复 UI route。Fullscreen 进入时 best-effort 请求 `SENSOR_LANDSCAPE`，最终布局仍以实际 Window bounds 为准，视频继续 `SCALE_ASPECT_FIT`，不拉伸画面。

### Server session end

v5.1 忽略 server-created DataChannel。v5.1.1 只处理已确认的 `control_channel`：复制 callback 数据后解析 JSON；出现 `exitMessage` 时，经过 connection generation + channel identity + terminal idempotence 校验后，将 Stream/Session 转为 Ended、执行输入释放并清本地 resume record。

异常 transport 关闭时还会调用现有 Session `pollSession()` 做保守 reconcile：只把 HTTP 404/410 当作 Session 已不存在的终态证据；其他 status/API code 不猜 NVIDIA 语义。

## 继续冻结

本轮没有修改：

```text
GFN identity
CloudMatch Create / Poll / Claim / RESUME / DELETE wire body
GFN WSS handshake envelope
SDP H.264 policy
ICE host-candidate injection
H.264 decoder / renderer
GFN keyboard/mouse packet framing
releaseAll ordered queue / epoch architecture
```

## WebRTC 依赖

继续保持：

```kotlin
api("io.github.webrtc-sdk:android:144.7559.09")
```

不能回归成 `implementation(...)`，因为公开 `GfnVideoSurfaceView` 继承 `SurfaceViewRenderer`。

## 当前验证边界

当前容器没有 Android SDK，不能声称完整 `assembleDebug` 通过。已完成：

```text
stream-input / keyboard-mouse controller compile      PASS
current GfnWebRtcEngine API-shaped compile            PASS
GfnSessionController targeted compile                 PASS
GfnStreamingController targeted compile               PASS
Runtime ViewModel + MainActivity wiring compile       PASS
UI Kotlin parse scan                                  PASS (0 syntax parse errors)
wheel direction fixture                               PASS (+1 axis -> +3 GFN delta)
core protocol staged compile + protocol-cli           PASS
static safety guards                                  PASS
```

完整 `verify-core.sh` 单次执行在当前容器中于 `diagnostics` 编译开始处达到执行超时；超时前没有 Kotlin compiler error，因此不把这次单脚本执行计为完整 PASS。v5.1.1 实际变更链已逐模块重新编译，`protocol-cli` 也已实际运行成功，详见 `docs/V5_1_1_SMOKE_OUTPUT.txt`。

## 下一次真机重点

```text
A. 音频：有声 / Audio track enabled / first audio RTP
B. 滚轮：上下方向是否正确
C. 进入全屏：自动横屏、保持视频比例、旋转不回 Home
D. 游戏内 Exit/Quit：是否自动显示 Session Ended
E. W DOWN + Overlay/失焦/旋转：不得卡键
```
