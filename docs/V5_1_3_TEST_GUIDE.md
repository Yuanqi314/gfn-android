# v5.1.3 真机 Input Forensics 测试指南

## 1. 构建

使用 debug build。v5.1.3 逐键取证由：

```text
BuildConfig.DEBUG && BuildConfig.INPUT_FORENSICS_ENABLED
```

控制；release build 默认关闭。

## 2. 抓日志

建议第一次保留完整日志：

```bash
adb logcat -c
adb logcat -v threadtime > gfn-v513-input-forensics.log
```

如果只抓本版取证 tag：

```bash
adb logcat -v threadtime \
  GfnInputForensics:I \
  GfnKeyDispatch:I \
  GfnInputKey:I \
  GfnInputTx:I \
  GfnInputHandshake:I \
  GfnInputChannel:I \
  GfnInput:W \
  Activity:I \
  '*:S' > gfn-v513-input-focused.log
```

## 3. 测试顺序

进入真实 H.264 全屏串流，确认画面和键鼠 channel ready 后，依次按：

```text
A
W
K
1
Space
Esc
```

每个键只：

```text
按下 1 次
松开 1 次
```

第一轮不要长按、快速连按、组合键。

## 4. 必须看到的启动证据

```text
GfnInputChannel ... label=input_channel_v1 ... state=OPEN
GfnInputHandshake ... raw=...
GfnInputHandshake ... negotiatedVersion=2/3 protocolReady=true
```

如果没有这些行，不要分析 keyboard packet；先定位 DataChannel/handshake。

## 5. K 的期望链

Activity：

```text
GfnKeyDispatch PRE seq=N ... keyName=KEYCODE_K ...
```

Mapper：

```text
GfnInputKey seq=N ...
mappedVK=0x004B
mappedScan=0x0025
trackedMods=0x0000
consumed=true
```

Tx protocol v2：

```text
GfnInputTx seq=N
type=KEY_DOWN
protocol=2
payloadOffset=0
length=18
vk=0x004B
mods=0x0000
scan=0x0025
binary=true
position=0
limit=18
remaining=18
bytes=03 00 00 00 00 4B 00 00 00 25 ...
sendAccepted=true
```

protocol v3 则必须：

```text
protocol=3
payloadOffset=10
length=28
bytes=23 <8-byte ts> 22 03 00 00 00 00 4B 00 00 00 25 ...
```

## 6. 如何判根因

### A. Android K 正确 + packet 全正确，但远端仍最小化

停止修改 Android VK/scan/endian/framing。故障域转为：

```text
GFN Session HID negotiation
GFN remote input interpretation
virtual HID / remote Windows injection
```

### B. 同一时间出现真实 KEYCODE_BACK

看 `GfnKeyDispatch PRE/POST` 的 seq。若 K seq 正常，又有另一个 BACK seq，故障域转向 Android/OEM/HID dispatch。

### C. K appHandled=false

优先修 View/Activity consume，不改 GFN packet。

### D. mappedVK/mappedScan 错

问题在 AndroidKeyboardMapper；只有这时才允许修改 mapping。

### E. Mapper 正确、GfnInputTx bytes 错

问题在 encoder/framing/handoff。

### F. protocolVersion 与 raw handshake 不一致

问题在 handshake parser / protocolReady 时序。

## 7. 上传内容

复现后可直接上传整份 logcat。若只截取，至少保留：

```text
GfnInputChannel OPEN
GfnInputHandshake raw/version/ready
A/W/K/1/Space/Esc 各自 PRE/InputKey/InputTx/POST
问题发生前后约 5 秒
```

日志会包含真实按键行为，上传前按敏感调试资料处理。
