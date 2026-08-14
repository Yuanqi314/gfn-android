# v5.1.3 Input Forensics：键盘全链路取证实现

## 目标

本版只增加键盘取证，不修改键盘协议语义。

冻结项：

```text
Windows VK mapping
Windows Set-1 scan mapping
modifier semantics
keyboard endian
keyboard packet size
v2/v3 framing
input_channel_v1 label
DataChannel ordered/negotiated semantics
releaseAll(reason)
input epoch
mouse / wheel
CloudMatch / WSS / SDP / ICE / H.264 / Audio
```

当前 K 仍然是：

```text
VK   = 0x004B
scan = 0x0025
```

## eventSeq 链

每个 Activity KeyEvent 在 `MainActivity.dispatchKeyEvent()` PRE 阶段生成唯一 `eventSeq`。
同一 UI dispatch 栈内，`GfnVideoSurfaceView` 复用这个 seq，不重新生成。
随后 seq 被复制进 ordered input queue 的 immutable trace，最终进入 keyboard Tx metadata。

```text
GfnKeyDispatch PRE seq=N
        ↓
GfnInputKey seq=N
        ↓
GfnInputTx seq=N
        ↓
GfnKeyDispatch POST seq=N
```

注意：Input controller 使用单线程 ordered executor，因此 `GfnInputKey/GfnInputTx` 可能在 Activity POST 之后才输出；关联依据是 `seq`，不是日志行的相邻关系。

## Activity dispatch

`GfnKeyDispatch PRE` 记录：

```text
seq / activity
action
keyCode / keyName
Android raw scanCode
metaState
repeat / flags
deviceId / source
eventTime / downTime
focusedView
```

`GfnKeyDispatch POST` 记录：

```text
appHandled
superHandled
```

这里 `appHandled` 表示当前 `GfnVideoSurfaceView` input listener 是否消费；`superHandled` 是 `ComponentActivity.super.dispatchKeyEvent()` 的最终返回值。
因此若再次出现 framework 的 `will call onBackPressed`，可以按 seq/时间判断它是否来自同一 KeyEvent。

## Mapper / state tracker

`GfnInputKey` 记录：

```text
activity / connectionGen / epoch / seq
event
keyCode / androidScan / repeat / deviceId / source
rawMeta
androidMods
trackedMods
mappedVK
mappedScan
protocol
keyboardActive
consumed
disposition
```

`androidMods` 仍只是诊断；远端 packet modifier 继续使用 v5.1.2 的 `InputStateTracker` held modifier 真值。

未映射键保持原行为：`consumed=false`，交还 Android 默认 dispatch；只增加 `UNMAPPED` 诊断。

## DataChannel / handshake

`GfnInputChannel` 记录 `input_channel_v1` 的创建和状态变化。

当前配置保持：

```text
ordered = true
negotiated = false（当前 Init 默认语义；本版没有修改 Init 配置）
```

`GfnInputHandshake` 记录服务器实际 callback bytes：

```text
raw
firstWord
parseRule
negotiatedVersion
protocolReady
```

parse rule 仍使用原有 `GfnInputHandshake.parseProtocolVersion()`，没有修改 parser。

## 最终 Tx ByteBuffer

Keyboard encoder 返回原有 `ByteArray` 后，WebRTC 边界只创建一次：

```text
val finalBuffer = ByteBuffer.wrap(packet)
```

取证代码对 `finalBuffer.asReadOnlyBuffer()` 做 dump；然后真正发送：

```text
DataChannel.Buffer(finalBuffer, true)
```

因此日志中的 bytes 来自真正交给 DataChannel 的同一个 ByteBuffer backing data，不是按字段重新构造的第二份诊断 buffer。

`GfnInputTx` 记录：

```text
activity / connectionGen / epoch / seq
type
protocol / payloadOffset / length
vk / mods / scan
binary
channel / channelState
position / limit / remaining
bufferedAmountBefore
exact bytes
sendAccepted
bufferedAmountAfter
```

`sendAccepted=true` 只表示本地 libwebrtc/DataChannel 接受发送请求，不代表远端 Windows 已处理。

## Debug 开关

App debug build：

```text
BuildConfig.DEBUG
&& BuildConfig.INPUT_FORENSICS_ENABLED
```

才启用逐键日志。

release build 的 `INPUT_FORENSICS_ENABLED=false`。

Input Forensics 不打印 OAuth token、Authorization、Cookie、TURN credential、ICE password、device code 或 client_token。

但日志本身会泄露用户按过的键，因此测试日志仍应视为敏感调试资料，不建议公开上传到公共仓库。

## correlation ID 边界

当前可靠记录：

```text
activityInstanceId
connectionGeneration
inputEpoch
eventSeq
```

参考方案还建议 `sessionGeneration`。当前 Android 代码没有一个能与 `SessionOrchestrator` generation 语义严格等价、同时可安全暴露到 WebRTC Input 层的 session generation，因此本版没有人为制造一个数字冒充它。

## 成功标准

v5.1.3 的成功不是“K 已修复”，而是下一次 A/W/K/1/Space/Esc 单键测试能够把故障明确归类到：

```text
Android dispatch
View consume
Mapper
Modifier tracker
Protocol handshake
Encoder/framing
ByteBuffer handoff
DataChannel send
Remote GFN/Windows interpretation
```
