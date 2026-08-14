# v5.1.4 Keyboard Wire A/B

本版只验证 GFN keyboard packet 的最终 `scancode` wire 语义，不宣称 `scan=0` 已是根因。

## 两个模式

```text
A / SCAN_SET1
mapped VK 不变
mapped scan 不变
wire scan = Windows Set-1 scan

B / VK_ONLY_SCAN_ZERO
mapped VK 不变
mapped scan 仍保留用于诊断
wire scan = 0x0000
```

默认仍为 A，因此启动后的行为与 v5.1.3 完全一致。只有用户在全屏 Overlay 中主动切换，才会进入 B。

## 安全切换

Wire Mode 只允许在：

```text
Overlay open
keyboardActive = false
physicalHeldKeys = 0
remoteHeldKeys = 0
```

时改变。Overlay 打开本身仍先执行既有 `releaseAll(reason=OverlayOpen)`；旧 epoch 排队事件保持原有防迟到语义。若条件不满足，切换请求被拒绝并记录：

```text
GfnInputWireMode
accepted=false
reason=REQUIRES_OVERLAY_AND_ZERO_HELD_KEYS
```

没有新增新的 release reason，也没有修改 `InputEpochGate`。

## 最终 wire 修改点

基础 `GfnInputPacketEncoder` 不改。controller 先生成 v5.1.3 完全相同的 packet，再由 `GfnKeyboardWirePolicy` 只处理最终 scan 两字节：

```text
protocol v2: offset 8..9
protocol v3: payloadOffset=10 -> offset 18..19
```

例如 K DOWN / protocol v3：

```text
A:
23 <outer-ts> 22
03 00 00 00
00 4B
00 00
00 25
<inner-ts>

B:
23 <outer-ts> 22
03 00 00 00
00 4B
00 00
00 00
<inner-ts>
```

除这两个字节外，A/B fixture 要求 byte-for-byte 相同。

## Forensics

v5.1.3 日志继续保留，`GfnInputTx` 新增：

```text
wireMode
mappedScan
wireScan
```

`mappedScan` 来自 AndroidKeyboardMapper；`wireScan` 从最终即将传给 `DataChannel.Buffer` 的 packet 再读取，避免“日志值与实际发送值不是同一数据”的证据漏洞。

## Freeze

本版未修改：

```text
stream-input / GfnInputPacketEncoder
AndroidKeyboardMapper
modifier semantics
protocol v2/v3 framing
0x23 / 0x22 wrapper
DataChannel config
releaseAll / epoch
mouse / wheel
audio
CloudMatch
WSS / SDP / ICE
H.264 / Surface
```

## 判定

推荐真机执行：

```text
A -> W/N/K/G
B -> W/N/K/G
A -> W/N/K/G
```

如果稳定得到：

```text
A bad
B good
A bad
```

则 wire scancode semantics 获得强因果支持。

如果 A/B 都 bad，应停止继续修改 VK/scan/modifier/framing，故障域下移到 Session keyboard layout / HID negotiation / NVST HID capability / server virtual HID / remote Windows injection。

**已验证无误。**
