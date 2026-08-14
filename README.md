# GFN Android Lab · v5.1.1 真机修正版

这是一个独立 Android GeForce NOW 客户端实验工程。当前真实 Android 设备已确认：**CloudMatch / Claim → GFN WSS → SDP → ICE → H.264 RTP → Decode → Surface 画面**成立；v5.1 已加入全屏键鼠与 `releaseAll(reason)` 状态机。v5.1.1 根据最新真机日志与实测问题，只修复当前层的 5 项可定位问题，不重写已经成功的媒体协议链。

> 仅使用用户自己的合法 GeForce NOW 账号；不修改订阅等级、账号 entitlement 或服务端授权。

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

完整单脚本回归曾因验证脚本运行环境里的 staged-output 异常失败，因此不把该次执行计为 PASS；同一批模块已逐模块重新编译并实际运行 protocol-cli 成功，详见 `docs/V5_1_1_SMOKE_OUTPUT.txt`。

## 下一次真机重点

```text
A. 音频：有声 / Audio track enabled / first audio RTP
B. 滚轮：上下方向是否正确
C. 进入全屏：自动横屏、保持视频比例、旋转不回 Home
D. 游戏内 Exit/Quit：是否自动显示 Session Ended
E. W DOWN + Overlay/失焦/旋转：不得卡键
```
