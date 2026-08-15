# GFN Android Lab · v6.1.1 Main10 / SDR10 Stage C2 Source Precision

这是一个独立 Android GeForce NOW 客户端实验工程。`45.log` 已关闭 v6.0.4 HEVC Main/SDR8；`46.log` 已关闭 Main10/SDR10 capability + negotiation；`50.log` 已关闭 Stage A/B；`51.log` 已关闭 Stage C0；`53.log` 已关闭 Stage C1：实际 Main10 10-bit 输入在两个 renderer 生命周期中都使用 runtime `R10/G10/B10/A2` final EGL target，并保持稳定约 60fps。

> 仅使用用户自己的合法 GeForce NOW 账号；不修改订阅等级、账号 entitlement 或服务端授权。

## v6.1.1 当前阶段：Stage C2.0 actual source-frame type witness

Stage C1 已经证明：

```text
actual HEVC SPS = Main10 / 10-bit luma / 10-bit chroma
+ runtime final EGL = exact RGB10A2
+ FIRST_FRAME
+ stable ~60fps
```

剩余核心未知量是 decoder 输出进入 WebRTC shader 前的 source texture/buffer precision。Stage C2.0 只读取实际送到 `GfnVideoSurfaceView.onFrame()` 的 M144 `VideoFrame.Buffer` 元数据：

```text
buffer class / bufferType
TextureBuffer yes/no
TextureBuffer.Type OES/RGB
textureId / GL target
scaled + unscaled dimensions
```

本轮明确禁止 `toI420()`，不 retain/release/crop/scale live frame，不做 GL readback，不访问未暴露的 decoder private `SurfaceTextureHelper`，原 `VideoFrame` 仍直接交给既有 `SurfaceViewRenderer`。C1 RGB10A2 target、Main10 negotiation、reconnect、Surface lifecycle 与 HDR policy 全部冻结。

Pinned WebRTC M144 source confirms its Android hardware decoder creates a `SurfaceTextureHelper`, renders MediaCodec output to its `Surface`, then creates `TextureBufferImpl(..., TextureBuffer.Type.OES, oesTextureId, ...)`. C2.0 still observes the actual downstream Java sink because native WebRTC may mediate the frame before it reaches the app.

真机首先搜索：

```text
GfnHevc10Bit phase=SOURCE_FRAME
```

理想 C2.0 witness：

```text
texture=true
textureType=OES
isOes=true
glTarget=36197
toI420Called=false
```

若不是 OES，则先修正链路模型，不继续假设 SurfaceTexture/OES。即使得到 OES，也只关闭“frame path 类型”，不等于 10-bit precision PASS。下一步才是单独的 native-window/producer metadata witness 与受控数值实验。

验证入口：

```text
sh ./verify-hevc-10bit-forensics.sh
sh ./verify-reconnect-engine.sh
sh ./verify-hevc-production.sh
sh ./verify-hevc-answer-lineage.sh
sh ./verify-main10.sh
sh ./verify-stream-settings.sh
sh ./verify-audio.sh
sh ./verify-hevc.sh
```

详细见：

```text
docs/STATUS.md
docs/V6_1_1_10BIT_FORENSICS.md
docs/V6_1_1_STAGE_C_RGB10A2.md
docs/V6_1_1_STAGE_C2_SOURCE_TEXTURE_PRECISION.md
docs/V6_1_1_TEST_GUIDE.md
```

## v6.0 历史基线

v6.0 按 CloudNow + OpenNOW 双参考仓库交叉取证，只吸收两边共同支持且适合当前 Android 架构的最小 HEVC 语义：

```text
Settings: H.264 / HEVC Main SDR8
        ↓ immutable ResolvedLaunchProfile
DefaultVideoDecoderFactory.supportedCodecs
+ actual GFN Offer
        ↓
explicit H265 profile-id=1
        ↓ createAnswer
actual Answer intersection
        ↓
HEVC Main or same-session H264 fallback
```

严格不启用：

```text
Main10/profile-id=2
HDR10
10-bit
AV1
120 FPS
H265 tier/level forced rewrite
```

CloudMatch 视频请求字段保持 v5.4 byte-identical，codec 选择只进入 frozen stream profile + SDP/WebRTC，保证真机 A/B 只有一个新视频变量。

验证入口：

```text
./verify-hevc.sh
./verify-reconnect-engine.sh
./verify-audio.sh
./verify-stream-settings.sh
./verify-keyboard-stable.sh
./verify-gamepad.sh
```

详细见：

```text
docs/V6_0_HEVC_MAIN_SDR.md
docs/V6_0_REFERENCE_ADOPTION.md
docs/V6_0_TEST_GUIDE.md
docs/REFERENCE_MATRIX.md
```

v6.0 当时尚待真机裁决；该阶段的判据要求同时确认 `Negotiated codec=Hevc`、Offer/Answer HEVC Main PT 非空且 `Codec fallback=false`。

## v5.4 本轮新增

v5.3 Gamepad 已实现并完成离线 packet/controller 验证；由于当前没有可用手柄，真机验证按开发决定标记为 **SKIPPED（非失败）**，不阻塞下一里程碑。

v5.4 聚焦 Audio：

```text
2ch
CloudMatch audioChannels=2
→ Opus Answer stereo=1
→ JavaAudioDeviceModule.setUseStereoOutput(true)
→ ADM stereo playout

6ch experimental
CloudMatch audioChannels=6
→ require GFN multiopus/48000/6 Offer
→ repair rejected game-audio Answer when necessary
→ negotiate/receive probe
→ current Android Java ADM still 2ch local output
```

因此本版 UI 明确区分：

```text
Stereo 2ch                         ADM output configuration
5.1 Surround 6ch                  experimental negotiation/2ch-ADM probe
Native 5.1                        NOT IMPLEMENTED
```

新增 `GfnAndroidAudioRouteProbe` 只记录 Android public audio APIs 暴露的候选输出能力，字段统一标记 `likelyRoute*`，不把它伪装成 libwebrtc 最终实际 route。

参考链：CloudNow 提供 multiopus Answer repair + 自定义 multichannel audio-device witness；OpenNOW 提供 Opus `stereo=1`/audio bandwidth witness；upstream Android WebRTC 的 Java ADM 能力边界用于阻止我们误报 native 5.1。

验证入口：

```text
./verify-audio.sh
./verify-stream-settings.sh
./verify-reconnect-engine.sh
./verify-keyboard-stable.sh
./verify-gamepad.sh
```

完整 Android Gradle build 仍取决于本地是否已有 Gradle 9.5.0；当前受限环境无法从 `services.gradle.org` 下载，因此不能把 API-shaped compile 写成完整 APK build PASS。

## v5.3 本轮新增

v5.2.1 真机已确认断网后的自动恢复仍保持同一个 GFN Session ID。当前已知但按本轮范围暂缓的问题：**第一次 reconnect 可能保持黑屏，第二次断开/重连可恢复画面**。该问题记录但不混入 v5.3。

v5.3 新增独立 `GfnGamepadInputController`，只做第一阶段单手柄 Xbox/XInput 风格输入：

```text
Android InputDevice / KeyEvent / MotionEvent
        ↓
slot 0 XInput-style normalization
        ↓
GFN type 12 (38-byte body)
        ↓
protocol v3 reliable wrapper
        ↓
input_channel_v1
```

支持范围：ABXY、D-Pad、LB/RB、LT/RT、Start/Back、L3/R3、双摇杆。15% radial deadzone，模拟摇杆输出 signed i16，扳机输出 0–255。当前只支持一个 controller slot；rumble/type13、多手柄、DualSense 专用特性和 partially-reliable gamepad transport 延后。

当前 NVST 明确发送 `a=ri.enablePartiallyReliableTransferGamepad:0`，因此 v5.3 使用可靠 `input_channel_v1`，不在没有协商证据时复制 PR gamepad wrapper。

Keyboard packet semantics 继续 soft-freeze；v5.3 只在共享 protocol encoder 增加 type12，并在 `GfnVideoSurfaceView` 增加 gamepad event routing。

详细见：

```text
docs/V5_3_GAMEPAD.md
docs/V5_3_REFERENCE_ADOPTION.md
docs/V5_3_TEST_GUIDE.md
docs/REFERENCE_MATRIX.md
verify-gamepad.sh
```

### v5.3 当前验证边界

```text
38-byte type12 body exact-offset fixture       PASS
protocol v3 reliable 50-byte wrapper           PASS
XInput button/trigger/stick mapping fixture     PASS
Android Y-axis inversion fixture                PASS
device removal -> neutral bitmap=0 fixture      PASS
keyboard stable packet regression               PASS
WebRTC engine API-shaped compile                PASS
true-device controller behavior                 PENDING
```


## v5.2.1 本轮新增

v5.2 真机已确认以下冻结 profile 功能正常：

```text
1920x1080 @ 60 FPS
Max bitrate 100 Mbps
Audio 2ch
Codec H.264
Keyboard en-US
Game language zh_CN
CREATE / CLAIM / WebRTC snapshot 一致
```

100 Mbps 只记录为当前真机环境已通过，不外推为 NVIDIA 全局服务端上限。

v5.2.1 在此基础上实现真正的 **same-session reconnect**：

```text
WebRTC / ICE transport failure
        ↓
releaseAll + bounded drain
        ↓
保留原 Session ID
+ 原 ResolvedLaunchProfile
        ↓
same-session RESUME / Claim
        ↓
刷新 signaling / connection info
        ↓
new signaling
new PeerConnection
new DataChannels
new input_channel_v1 handshake
        ↓
FIRST FRAME + protocolReady
```

硬约束：Reconnect 路径不调用 `createSession`；服务端若返回不同 Session ID 立即拒绝；活动 Session 中修改 Settings 不会改变 reconnect profile。`control_channel.exitMessage` 与 CloudMatch 404/410 保持 terminal，不进入自动重连。

`DISCONNECTED` 先给 7 秒 grace，瞬时网络抖动恢复时不做 CloudMatch Claim；硬 `FAILED` 立即进入 bounded recovery。当前本地策略最多 3 次，失败后的 backoff 为 1s / 3s，这些时间值是客户端策略，不声称是 NVIDIA 协议要求。

另外修复 reconnect 专属 Surface 生命周期边界：旧 transport teardown 会清空 `GfnVideoSurfaceView.inputListener`，v5.2.1 在新输入 controller generation 建立时自动对仍挂载的视频 Surface 重装动态 input listener，避免“画面恢复但键鼠失效”。

详细见：

```text
docs/V5_2_1_RECONNECT.md
docs/V5_2_1_REFERENCE_ADOPTION.md
docs/V5_2_1_TEST_GUIDE.md
docs/REFERENCE_MATRIX.md
verify-reconnect.sh
```

### v5.2.1 当前验证边界

```text
same-session reclaim fixture                 PASS
reconnect CREATE count remains 1             PASS
frozen profile resolve count remains 1       PASS
transient DISCONNECTED grace self-heal        PASS
hard failure -> reclaim -> new transport      PASS
FIRST FRAME + input handshake success gate    PASS
keyboard packet semantics soft-freeze         PASS
production reconnect static guards            PASS
```

真机后续已确认：断网恢复过程中 Session ID 保持不变，same-session 主链成立；但第一次 reconnect 可能保持黑屏，第二次断开/重连可恢复画面。该缺陷已记录并按当前开发决定暂缓。完整 Android Gradle build 仍受容器无法下载 Gradle 9.5.0 限制。

## v5.2 本轮新增

v5.1.9 已完成 Cyberpunk 2077 + CS2 真机回归，Keyboard packet semantics 进入 soft-freeze。v5.2 不再修改 VK / Set-1 scan / modifier / framing / CapsLock，而是建立后续 Reconnect、Audio、HEVC 共用的 Session 设置基础。

```text
Persistent StreamSettings
        ↓
resolve against entitlement + current engine capability
        ↓
ResolvedLaunchProfile (immutable)
        ↓
CREATE / persist / CLAIM / WebRTC
```

当前只开放现有 production path 已经具备的维度：

```text
Keyboard Layout：默认 en-US，保留 Auto 和其他布局
Resolution：Auto / 1920x1080
FPS：Auto / 60
Max Bitrate：20 Mbps 默认，可按 5 Mbps 步进做下一 Session A/B
Audio：Stereo 2ch
Codec：H.264 固定
Color：SDR8 固定
```

5–100 Mbps 是客户端 bitrate guard；后续 v5.2 真机已验证 100 Mbps 在当前设备/账号/节点环境功能正常，但不外推为 NVIDIA 全局服务端上限。HEVC/Main10/HDR/5.1/120 FPS 不在 v5.2 UI 中提前伪装为可用。

`ResolvedLaunchProfile` 会随 Session 持久化。Claim/Resume 和 WebRTC 必须复用创建时的 snapshot，活动 Session 期间修改 Settings 只影响下一新 Session。v5.1.9 及更早的 legacy resume record 没有完整 profile，因此 v5.2 不猜参数，要求先 End/Cleanup 后重新创建。

详细见：

```text
docs/V5_2_STREAM_SETTINGS_FOUNDATION.md
docs/V5_2_REFERENCE_ADOPTION.md
docs/V5_2_TEST_GUIDE.md
docs/REFERENCE_MATRIX.md
verify-stream-settings.sh
```

### v5.2 当前验证边界

```text
settings resolver / capability fixture        PASS
SharedPreferences migration / round-trip      PASS
Session ResolvedLaunchProfile persistence     PASS
legacy Resume cleanup DELETE behavior         PASS
active Session profile preservation           PASS
GfnStreamSettingsController targeted compile  PASS
GfnSessionController targeted compile         PASS
GfnStreamingController targeted compile       PASS
v5.1.9 keyboard regression fixtures           PASS
keyboard production files byte-identical      PASS
```

完整 Android Gradle build 当前仍在 wrapper 下载 Gradle 9.5.0 阶段被容器 DNS 阻断；因此上述结果不冒充最终 APK 全工程编译。真机重点见 `docs/V5_2_TEST_GUIDE.md`。

## v5.1.9 本轮新增

Cyberpunk 2077 真机已经确认：造成 A-Z 被 Windows completion/composition 输入路径截获的有效变量是 **Session `keyboardLayout`**；新 Session 固定 `en-US` 后 Caps OFF 的字母恢复正常。

v5.1.9 因此只做 production cleanup：

```text
保留：keyboardLayout setting + Session snapshot
恢复：Windows VK + Windows Set-1 scan + tracked modifiers
删除：scan=0 / type19 / synthetic LSHIFT / C2-C3 probe / Wire A-B UI
```

通用 `GfnInputForensics` 继续保留，但不再参与任何 packet 变换。Cyberpunk 2077 + CS2 回归通过后，Keyboard 模块进入 soft-freeze。

详细见：

```text
docs/V5_1_9_KEYBOARD_STABLE_BASELINE.md
docs/V5_1_9_TEST_GUIDE.md
docs/V5_1_9_REFERENCE_ADOPTION.md
docs/REFERENCE_MATRIX.md
```


## 历史：v5.1.4 Keyboard Wire A/B

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
