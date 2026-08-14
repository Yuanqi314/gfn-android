# v5.1.3 参考方案吸收记录

本版参考 `GFN_Android_v5.1.3_Input_Forensics键盘全链路取证方案.md`，但不以参考方案覆盖现有实现。

吸收：

```text
eventSeq 全链路
Activity dispatch PRE/POST
raw KeyEvent fields
androidMods / trackedMods 分离
raw handshake + parse rule + negotiated version
protocolReady 独立日志
final ByteBuffer exact dump
position/limit/remaining/binary/sendAccepted
DEBUG-only forensics
A/W/K/1/Space/Esc golden packets
freeze guards
```

保留自己的实现：

```text
:stream-input 继续拥有纯 Kotlin GFN wire protocol
GfnKeyboardMouseInputController 继续拥有 ordered queue/epoch/releaseAll
GfnWebRtcEngine 只在 DataChannel 边界 dump final ByteBuffer
GfnVideoSurfaceView 只负责 Android event capture
MainActivity 只负责最外层 dispatch correlation
```

没有实现参考方案中的 `sessionGeneration` 字段，因为当前工程没有一个可与 SessionOrchestrator generation 严格等价并安全跨层使用的值；本版拒绝人为制造无证据的 generation。
