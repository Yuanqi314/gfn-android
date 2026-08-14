# v5.1 参考方案吸收记录

用户提供的《GFN Android v5.1：全屏键鼠输入状态机与 releaseAll(reason) 设计》作为**输入安全/状态机参考**，不替换本项目已有模块边界与媒体方案。

## 已吸收

- `releaseAll(reason)` 统一入口。
- physical held / remote assumed / uncertain 分离。
- DataChannel OPEN 与 protocolReady 分离。
- KeyboardActive 与 MouseActive 分离。
- ordered queue + input epoch stale rejection。
- transport closed 时 RemoteState=UNKNOWN。
- 主动断开前 release + barrier + bounded local drain。
- 普通键 → 鼠标按钮 → modifier 的 release 顺序。
- mouse motion / wheel accumulator 与 coalescing。
- Pointer Capture lifecycle。
- Esc 保留给远端；本地 Overlay 使用 Android Back。

## 保留的自有方案

- `GFN Session ≠ Signaling ≠ WebRTC ≠ Input ≠ UI` 的模块隔离不变。
- 新建 `:stream-input` 作为纯 Kotlin 协议层，而不是把 encoder/state 塞进 `stream-webrtc`。
- Android keyboard mapper 独立，不复制 Apple `UIKeyboardHIDUsage` 采集层。
- 不因为输入开发修改已经真机成功的 CloudMatch/WSS/SDP/ICE/H.264。
- 不提前做 Gamepad/Audio/HEVC/Main10/HDR。

## 根据真实参考实现确认后采用的协议事实

- input type：2/3/4/7/8/9/10。
- keyboard/mouse packet endian/layout。
- v3 single-event wrapper。
- `input_channel_v1` 服务器 handshake 与 protocol version 解析。
- Windows VK + Set-1 scan code 映射原则。

## 没有直接照搬

- Apple/tvOS 的 `GCController/GCMouse/UIKey` 事件获取。
- Apple DispatchQueue/UI responder 生命周期。
- 参考文档中未被真实协议证据证明的 reconnect full-state snapshot packet。

完整跨 PeerConnection reconnect/resync 仍留到 v5.3；v5.1 不虚构不存在证据的“全键盘 neutral snapshot”格式。
