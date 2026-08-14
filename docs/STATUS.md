# 当前状态 · v5.1

## 真机已确认

```text
Auth / restart restore             ✅
Membership                         ✅
Library / Catalog                  ✅
Search / Game Detail               ✅
CloudMatch Create                  ✅
Provision / resolved server        ✅
Claim / RESUME                     ✅
GFN WebSocket signaling            ✅
Offer / Answer / NVST SDP          ✅
ICE / PeerConnection               ✅
H.264 RTP                          ✅
H.264 Decode / Surface             ✅
```

v5.0 H.264 First Frame 已成为真实设备里程碑，媒体链进入 soft-freeze。

## v5.1 已实现，待真机输入验证

```text
Fullscreen stream page             ✅ 源码
input_channel_v1 handshake gate    ✅ 源码/fixture
Keyboard mapper                    ✅ 源码/fixture
Keyboard DOWN/UP                   ✅ 源码/fixture
Mouse buttons                      ✅ 源码/fixture
Mouse wheel                        ✅ 源码/fixture
Relative mouse / Pointer Capture   ✅ 源码/类型检查
releaseAll(reason)                 ✅ 状态机 fixture
ordered input queue                ✅
input epoch stale rejection        ✅
remote UNKNOWN handling            ✅
active-disconnect local drain      ✅
JNI callback exception containment ✅

远端游戏实际响应                   ⏳ 真机
```

## v5.1 安全状态机

KeyboardActive：

```text
streamConnected
&& lifecycleResumed
&& windowFocused
&& dataChannelOpen
&& protocolReady
&& inputEnabled
&& !overlayOpen
```

MouseActive：

```text
KeyboardActive
&& pointerCaptured
```

因此 Pointer Capture 丢失不自动释放键盘。

## releaseAll 语义

全量冻结事件会同步推进 `inputEpoch`，再进入同一个 ordered executor 释放远端假定仍按下的输入。旧 epoch 的迟到事件进入队列后会被丢弃，避免：

```text
W DOWN(epoch N)
→ releaseAll / epoch N+1
→ W UP
→ 迟到的旧 W DOWN
```

重新让远端变成按下状态。

如果 transport 已关闭：

```text
clear physical state
remote assumed -> uncertain
RemoteState = UNKNOWN
```

不会伪造“release 成功”。

## protocolReady

DataChannel OPEN 后不立即放行输入。服务器 handshake 解析到 protocol version 后，在 ordered queue 中先处理 uncertain-state neutralization，最后才设置 `protocolReady=true`。

## 当前限制

- 键鼠尚未在远端游戏完成真机动作验证。
- 完整跨 PeerConnection 自动 reconnect/resync 仍属于后续 v5.3；v5.1 只保证同一输入控制器重新握手时 uncertain state 会先 neutralize。
- 没有 application-level input ACK，因此 `DataChannel.send=true` / `bufferedAmount=0` 不等于远端游戏已处理。
- 相对鼠标 Y 方向仍以真机实际游戏行为为最终证据；如方向不符，只修 Android mapper，不改 GFN packet framing。
- 当前容器没有 Android SDK，不能声明完整 APK/Compose Gradle build 通过。

## 后续路线

```text
v5.0 H.264 First Frame       ✅ soft-freeze
v5.1 Keyboard + Mouse        ← 当前
v5.2 Audio
v5.3 Reconnect / lifecycle
HEVC Main SDR8
Main10 SDR10
HDR10
```
