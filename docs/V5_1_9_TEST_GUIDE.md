# v5.1.9 真机回归指南

## 前置

1. 设置 -> 串流键盘布局 -> `English (US)`。
2. 结束旧 Session。
3. 创建全新 Session，确认日志：

```text
GfnKeyboardLayout: CREATE keyboardLayout=en-US ...
```

## Cyberpunk 2077

保持 CapsLock OFF：

```text
A W S D L
```

期望：

```text
全部进入游戏
不出现绿色 completion/composition 输入框
全屏不最小化
```

再测试：

```text
CapsLock
Shift
Ctrl
Alt
Tab
Space
Esc
Mouse left/right/middle
Wheel up/down
```

期望无回归。

## CS2

同一个 `keyboardLayout=en-US` 新 Session：

```text
A W S D
Shift/Ctrl/Alt/Tab
Mouse/Mouse wheel
```

期望全部正常。

## 调试日志

Debug build 的 `GfnInputTx` 应显示最终 Set-1 scan，例如：

```text
A    vk=0x0041 scan=0x001E
W    vk=0x0057 scan=0x0011
Caps vk=0x0014 scan=0x003A
```

不得出现：

```text
GfnLockState
GfnCapsCompat
C2_ISO
C3_OPENNOW
VK_ONLY_SCAN_ZERO
```

## 通过条件

Cyberpunk 2077 和 CS2 均通过后：

```text
Keyboard Stable Baseline = VERIFIED
Keyboard module = SOFT-FROZEN
```
