# v5.1.1 真机测试指南

## 1. 构建

Android Studio：

```text
Sync
→ :app assembleDebug
→ 安装真机
```

当前交付环境没有 Android SDK，因此如果 Android Studio 有编译错误，只保留**第一条真实 Kotlin/Java compiler error**即可。

## 2. Audio

进入真实串流后先验证：

```text
画面继续正常
Audio Track = present/enabled
Audio RTP = yes（有数据后）
游戏有声音
```

如果仍无声，请提供 Diagnostics Audio 行和 logcat 中 `WebRtcAudioTrackExternal / AudioTrack`；不要先改 SDP/codec。

## 3. Wheel

同一游戏菜单/列表中测试：

```text
滚轮向上 → 内容向预期方向移动
滚轮向下 → 反方向
```

v5.1.1 只改变 Android sign，倍率仍为 3。

## 4. Fullscreen / landscape

从正常串流点击进入全屏键鼠：

期望：

```text
自动 best-effort 横屏
system bars 隐藏
视频保持比例（允许黑边，不允许拉伸）
Session/WebRTC 不重建
```

随后手动旋转 portrait ↔ landscape 一次。期望仍留在当前 Session/stream UI，不回 Home；视频可通过新的 Surface 重新 attach。

若再次回 Home，请抓完整 logcat，重点看：

```text
GfnActivity Activity#
GfnNav route/fullscreen
GfnStream state/ice/pc
GfnSession
GfnAuth
```

## 5. releaseAll 回归

```text
W DOWN → 打开 Overlay
W DOWN → Home/后台
W DOWN → Window focus lost
Mouse Left DOWN → Pointer Capture lost
```

不得出现卡 W/卡 Ctrl/卡鼠标键。Pointer Capture lost 单独发生时只释放鼠标，不应无条件 W UP。

## 6. 游戏主动退出 / Session End

测试两种真实退出方式之一并说明是哪种：

```text
游戏菜单 → Exit/Quit
Steam → Exit Game
```

期望：

```text
server opens control_channel
→ exitMessage
→ StreamState SessionEnded
→ releaseAll(SessionEnd)
→ SessionUiState Ended
→ 本地 resume record 清理
→ 全屏退出
```

不应再需要手动点 Claim 才发现会话结束。

如果没有自动结束，请保留退出前 20 秒到退出后 20 秒完整 logcat；尤其需要 DataChannel label/state、control RX、ICE/PC state、Session reconcile。

## 7. Auth persistence

本轮不修登录偶发丢失。如果再次发生，只需保留：

```text
GfnCredential CredentialRestore:...
GfnCredential CredentialCleanup:reason=...
GfnAuth ...
```

日志不应包含 token/ciphertext/key；不要上传凭据本身。

## 8. 成功标准

```text
H.264 视频                   ✅ 保持
键鼠                         ✅ 保持
滚轮方向                     ✅
声音                         ✅
全屏自动横屏/比例             ✅
旋转不回主页                 ✅
游戏退出自动 Session End      ✅
releaseAll 无卡键             ✅
```
