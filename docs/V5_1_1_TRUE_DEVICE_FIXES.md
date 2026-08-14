# v5.1.1 五项真机问题 + Audio 修复说明

## 1. Wheel direction

真机 v5.1 证明 Android 侧原有：

```text
GFN delta = -AXIS_VSCROLL * 3
```

方向错误。v5.1.1 改为：

```text
GFN delta = AXIS_VSCROLL * 3
```

只改 sign，倍率/packet framing/coalescing 不变。

## 2. Fullscreen / landscape / recreation

- Runtime Controllers 从 Composable `remember` 提升到 `GfnAppRuntimeViewModel`，并显式依赖 `androidx.lifecycle:lifecycle-viewmodel:2.11.0`。
- tab/fullscreen route 使用 `rememberSaveable`。
- Fullscreen best-effort `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`。
- layout 继续 `fillMaxSize` +真实 Window bounds；SurfaceRenderer 保持 `SCALE_ASPECT_FIT`。
- config recreation 中旧 Activity dispose 不恢复 unspecified orientation。
- video output unbind 按 View identity，旧 Surface 不能解绑新 Surface。

## 3. Unexpected Home

本轮不把所有“回主页”归因于 orientation。只确认上传日志中的手动横屏 case。新增：

```text
GfnActivity: Activity# / lifecycle / orientation / requestedOrientation
GfnNav: route / fullscreen / SessionUiState / StreamState
GfnStream: stream / ICE / PC state
GfnInput: release reason / epoch（现有 diagnostics）
```

未来再次突发即可按 correlation 判断。

## 4. Auth record disappearance

不改变 restore/clear 策略。只增加：

```text
CredentialRestore:BLOB_MISSING
CredentialRestore:OK
CredentialRestore:FAILED error=<exception class>
CredentialCleanup:reason=RESTORE_FAILED
CredentialCleanup:reason=EXPLICIT_CLEAR
```

不打印 access token、refresh token、client token、ciphertext、Keystore key。

## 5. Server game/session exit

补：

```text
PeerConnection.onDataChannel
→ label == control_channel
→ current generation + channel identity
→ copy DataChannel bytes in callback
→ JSON exitMessage
→ terminal idempotence
→ release input
→ StreamState.SessionEnded
→ GfnSessionController.onServerSessionEnded
→ clear local resume record
```

若 control transport 异常关闭，触发保守 session reconcile：poll 当前已知 Session，只把 HTTP 404/410 当作“Session 已不存在”；其他状态仅记录，不猜。

## 6. Audio

删除 v5.0 遗留的 remote `AudioTrack.setEnabled(false)`。收到 remote audio receiver 后启用 track，并增加 Audio diagnostics。完整音频质量/多声道仍留给后续版本。
