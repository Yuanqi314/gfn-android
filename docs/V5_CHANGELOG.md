# v5.1.1 — 真机问题修正版

- Android 真机 wheel direction：取消 v5.1 对 `AXIS_VSCROLL` 的额外负号；保持倍率/accumulator/packet framing 不变。
- 新增 `GfnAppRuntimeViewModel`，把 Auth/Content/Session/WebRTC runtime 提升到 Activity configuration recreation 之外。
- `MainActivity` 增加 Activity instance/orientation 生命周期日志。
- 全屏使用 `SENSOR_LANDSCAPE` best-effort、immersive system bars、真实窗口边界与 first-frame aspect ratio 居中显示。
- 恢复 remote WebRTC `AudioTrack`：从旧版 `setEnabled(false)` 改为 `setEnabled(true)`；不引入新 ADM/5.1/麦克风。
- 实现 server-created `control_channel`，解析 top-level `exitMessage`。
- `exitMessage` 加 generation + channel identity + terminal idempotence，先 releaseAll/drain 再进入 `SessionEnded`。
- 服务端终态会 `detachOwnedSession()` 并清除本地 resume record，避免对已结束 Session 再执行错误 DELETE。
- ICE/PeerConnection/control channel 异常关闭增加 CloudMatch reconcile；当前仅 HTTP 404/410 判终态，其他状态只记录。
- Auth persistence 仅增加 restore/cleanup reason diagnostics，不修改存储/清理策略。
- 不修改 CloudMatch Create/Claim/RESUME、GFN WSS、SDP、ICE、H.264。

## v5.0.1 — GFN WebSocket Upgrade 修复

- 修复真实真机 `Expected HTTP 101 response but was 400 Bad Request`。
- `GfnSignalingClient` 新增 `Sec-WebSocket-Protocol: x-nv-sessionid.<sessionId>`。
- 新增 `Origin: https://play.geforcenow.com`。
- 新增统一的 GFN-PC User-Agent，复用 `gfn-identity/GfnProtocolDefaults`，避免常量漂移。
- `stream-webrtc` 显式依赖 `:gfn-identity`。
- 失败信息附带 HTTP code/message，但不输出 token/credential。
- 新增纯 JVM fixture：验证 session subprotocol 精确格式。
- 未修改 CloudMatch Create/Poll/Claim/DELETE、SDP/ICE/H.264 逻辑。

# v5.0 变更说明

## 新增

- `stream-signaling`：纯 JVM GFN WebSocket envelope、SDP、NVST SDP。
- `stream-webrtc`：Android OkHttp WebSocket + direct libwebrtc。
- Claimed Session → “连接 WebRTC H.264”入口。
- `SurfaceViewRenderer` 视频输出。
- Signaling / SDP / ICE / Video 分层 Diagnostics。
- First RTP / First Surface Frame 标志。

## 协议策略

- v4 CloudMatch/Create/Claim soft-freeze。
- WSS 使用 `/nvst/sign_in` + pairing/session 参数。
- 发送 `peer_info`、ACK、heartbeat。
- 接收 server Offer/ICE。
- Answer 只允许 H.264。
- NVST SDP 固定 bitDepth=8、1920x1080、60 FPS。
- Server ICE=0 不等于失败；不自动启用公共 STUN。
- 远端 host candidate 从真实 ConnectionInfo / video m-line 推导。

## 本轮编译问题修正

验证过程中已发现并纠正：

1. `SessionInfo.signalingUrl` 跨模块 nullable property：改用局部稳定快照。
2. `flushRemoteIce()` 重复声明 `val pc`：删除重复声明。
3. `GfnSignalingClient` trailing lambda 误绑定最后一个 `OkHttpClient` 参数：改成显式 `listener = {}`。
4. `protocol-cli` 漏声明 `:stream-signaling` Gradle 依赖：已补齐。
5. decoder Diagnostics 不再预先宣称 MediaCodec 硬解。
6. partial-reliable DataChannel lifetime 改为从真实 Offer 的 `ri.partialReliableThresholdMs` 读取，而不是固定猜测。
7. media ConnectionInfo 选择改为 `usage 2 → 17 → usage 14 最高有效端口`，避免把任意 control/signaling port 全部当作 media candidate。

## 未改变

- Auth / KeyStore
- Account/MES
- Library/Catalog/Search/Detail
- CloudMatch Create body
- Queue/Ready semantics
- Claim/RESUME body
- Session identity

## v5.0.2 — WebRTC JNI / NetworkMonitor crash fix

- `io.github.webrtc-sdk:android:144.7559.09` 从 `implementation` 改为 `api`，因为公开的 `GfnVideoSurfaceView` 继承 `SurfaceViewRenderer`。
- `app` 与 `stream-webrtc` manifest 增加 `ACCESS_NETWORK_STATE` / `CHANGE_NETWORK_STATE`。
- `GfnWebRtcRuntime` 在进入 native WebRTC 前校验最终安装包是否实际获得两项 normal permission，避免 WebRTC JNI `HandleException()` 把 Java 权限异常升级成 `SIGABRT`。
- 根据真机 `tombstone_08`：崩溃线程为 `network_thread`，native fatal 位于 `sdk/android/src/jni/jvm.cc:81`，并出现 `android_network_monitor.cc` 证据。
- 未修改 CloudMatch、Claim/RESUME、GFN signaling envelope、SDP、ICE 注入、H.264 codec 策略。

## v5.1 — 全屏键鼠输入

- 新增 `:stream-input` 纯 Kotlin 模块，隔离 GFN keyboard/mouse packet framing、input handshake parser、held-state/release plan 与 epoch gate。
- 新增 `FullscreenStreamScreen`，只在全屏串流页启用键鼠捕获；系统栏隐藏，Android Back 控制本地 Overlay，普通 Esc 继续发给远端。
- 新增 Android `KeyEvent.KEYCODE_* → Windows VK + Set-1 scan code` 映射，不直接复用 Android `scanCode`。
- 新增键盘 DOWN/UP、modifier、鼠标左右/中键、滚轮、Pointer Capture 相对鼠标。
- 相对鼠标消费 `MotionEvent` historical batched samples，减少高 polling mouse delta 丢失。
- `input_channel_v1` OPEN 后等待 server handshake；协议版本解析完成、uncertain state neutralize 后才 `protocolReady=true`。
- 新增统一 `releaseAll(reason)`：ordered queue + input epoch + remote UNKNOWN + deterministic UP ordering。
- Pointer Capture lost 只释放鼠标，不推进全局 epoch，不破坏仍有焦点的键盘。
- 主动 disconnect/Session End 先提交 release packet，再经过 queue barrier 和有界 `bufferedAmount` drain 后关闭 transport。
- DataChannel 已关闭时不伪造远端已 neutral；本地清状态、远端标 UNKNOWN。
- DataChannel native callback 增加异常隔离，避免 Kotlin 异常穿出 JNI observer。
- 保持 `api("io.github.webrtc-sdk:android:144.7559.09")`，不回归成 `implementation`。
- 未修改 CloudMatch、Claim/RESUME、GFN WSS envelope、SDP、ICE、H.264 解码/Surface 行为。

## v5.1.1 — 真机问题修正版

- 根据真机结果修复 Android wheel sign：`-AXIS_VSCROLL * 3` → `AXIS_VSCROLL * 3`，GFN packet framing 不变。
- 修复 v5.1 无声音：删除 remote `AudioTrack.setEnabled(false)` 视频-only 冻结开关，收到 audio receiver 后启用 track，并增加 Audio RTP/track diagnostics。
- 根据上传 logcat 确认“手动横屏→回主页”这一例发生 Activity recreation；新增 `GfnAppRuntimeViewModel`，将 Auth/Content/Session/Streaming runtime owner 从 Composable `remember` 提升到 configuration-surviving ViewModel。
- `tabName/fullscreenStream` 改为 `rememberSaveable`，rotation 后 UI 重新 attach 到现存 runtime；显式加入 `androidx.lifecycle:lifecycle-viewmodel:2.11.0`。
- Fullscreen 新增 `SENSOR_LANDSCAPE` best-effort；实际 layout 继续以 Window bounds 为准，视频继续 `SCALE_ASPECT_FIT`。
- old/new fullscreen Surface 切换使用 identity-safe unbind，旧 View 不能误解绑新 View。
- 新增 `control_channel` server-created DataChannel 处理；检测顶层 `exitMessage`，使用 connection generation + channel identity + terminal idempotence 防旧连接污染。
- server exit 后执行 input SessionEnd release，Stream/Session 进入 Ended，清理本地 resume record，不再次 DELETE 已由服务器结束的 Session。
- transport 异常时增加保守 Session reconcile：复用现有 `pollSession`，只把 HTTP 404/410 作为 Session 已不存在的终态证据，其他 status/API code 不推断。
- #4 登录记录偶发消失暂不改变存储策略，只新增 `CredentialRestore/CredentialCleanup` reason-only logs。
- 新增 Activity/Nav/Stream correlation logs，便于以后捕捉未复现的突发 Home-return。
- 保持 WebRTC 依赖 `api("io.github.webrtc-sdk:android:144.7559.09")`。
- 未修改 CloudMatch wire body、GFN WSS envelope、SDP H.264、ICE host candidate 或 H.264 renderer。


## v5.1.2 — Android 音频路由 / 键盘 modifier 真值

- 真机确认 v5.1.1：声音、滚轮、自动横屏/适配、`control_channel exitMessage` 自动 Session End 均成功。
- 根据 `31.log`：Android `AudioTrack` 停止日志显示 `stream 0`，与系统通话音量/听筒现象一致。
- `GfnWebRtcRuntime` 改为显式 `JavaAudioDeviceModule`，AudioAttributes 使用 `USAGE_GAME + CONTENT_TYPE_MUSIC`；不强制 built-in speaker，保留有线/蓝牙正常系统路由。
- `MainActivity.volumeControlStream = STREAM_MUSIC`，前台硬件音量键控制媒体音量。
- 键盘 wire packet 的 VK / Set-1 scan code / GFN framing 不变。
- 普通键的 modifier mask 不再直接信任 `KeyEvent.metaState`；只使用 `InputStateTracker` 实际收到并持有的 Shift/Ctrl/Alt/Meta。
- Android `metaState` 仍保留为 diagnostics；若 Android-reported mask 与 tracked mask 不同，记录 `GfnInput modifier mismatch`。
- 新增 Input diagnostics：raw keyCode/metaState、Android modifier mask、tracked modifier mask、mismatch count。
- 未修改 CloudMatch、GFN WSS、SDP、ICE、H.264、mouse packet framing、control_channel、releaseAll/epoch/ordered queue。
- 自定义 `JavaAudioDeviceModule` 在 `PeerConnectionFactory` 创建后立即 `adm.release()` 释放调用方持有的 native ref；Factory 保留自己的引用，避免 ADM 生命周期泄漏。
