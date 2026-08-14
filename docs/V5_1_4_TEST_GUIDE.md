# v5.1.4 真机 A/B 测试指南

## 抓日志

从连接前开始：

```bash
adb logcat -c
adb logcat -v threadtime > gfn-v514-wire-ab.log
```

这样必须同时抓到：

```text
GfnInputChannel
GfnInputHandshake raw=...
negotiatedVersion=...
protocolReady=true
```

## A -> B -> A

### A：SCAN_SET1

启动后默认就是 A。进入游戏后依次单按：

```text
W
N
K
G
```

每个 DOWN/UP 一次，间隔约 1 秒。记录是否最小化。

### 切 B

按 Android Back 打开 Overlay。等待 HUD 显示：

```text
held K 0/0
```

点击：

```text
切换到 B：VK + scan=0
```

确认 HUD：

```text
Wire=VK_ONLY_SCAN_ZERO
```

关闭 Overlay，再按 W/N/K/G。

### 回 A

再次打开 Overlay，等待 held keys 为 0，切回：

```text
A：VK + Set-1 scan
```

再按 W/N/K/G。

## 关键日志

A 中 K 应看到：

```text
wireMode=SCAN_SET1
mappedScan=0x0025
wireScan=0x0025
```

B 中 K 应看到：

```text
wireMode=VK_ONLY_SCAN_ZERO
mappedScan=0x0025
wireScan=0x0000
```

同时确认：

```text
protocol=3
payloadOffset=10
length=28
binary=true
channelState=OPEN
sendAccepted=true
```

## 不要混入其他变量

第一轮不要测试：

```text
组合键
长按
快速连按
Esc 特殊语义
Gamepad
HDR/HEVC
```

Escape 后续单独验证；普通字母 B 成功不能直接推出所有键都应 `scan=0`。

**已验证无误。**
