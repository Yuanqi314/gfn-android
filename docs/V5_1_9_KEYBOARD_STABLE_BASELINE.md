# v5.1.9 Keyboard Stable Baseline

## 目标

v5.1.9 将已完成真机验证的 `Session keyboardLayout` 修复正式化，并撤销为定位 Cyberpunk 2077 字母键问题而加入的 C2/C3 实验语义。

正式生产路径固定为：

```text
Android KeyEvent
    ↓
AndroidKeyboardMapper
    ↓
Windows VK
+ Windows Set-1 scan
+ InputStateTracker tracked modifiers
    ↓
GfnInputPacketEncoder.keyboard()
    ↓
ordered input_channel_v1
```

CapsLock 只走普通键路径：

```text
VK_CAPITAL DOWN  (VK=0x14, scan=0x3A)
VK_CAPITAL UP    (VK=0x14, scan=0x3A)
```

## 已删除的实验行为

```text
C3 固定 scan=0
INPUT_LOCK_KEYS_SYNC / type19
CapsLock synthetic VK_LSHIFT
C2/C3 probe logs
GfnCapsCompat
GfnLockState
GfnKeyboardWireMode / GfnKeyboardWirePolicy
setKeyboardWireMode()
Wire A/B Overlay UI
```

这些行为曾用于单变量取证，但真机最终有效变量是 `Session keyboardLayout=en-US`，因此不进入 production baseline。

## 正式保留

- `串流键盘布局`持久化设置，默认 `English (US)`。
- Auto 与其他 GFN keyboard layout 选择。
- Create 时解析一次 layout，并冻结到当前 Session。
- Claim/Resume 使用当前 Session 创建时的同一 keyboardLayout。
- `GfnInputForensics` 通用诊断：VK、Set-1 scan、mods、protocol、sendAccepted、generation、input epoch、release reason。
- ordered input queue、tracked modifiers、releaseAll/epoch/uncertain-state neutralization、mouse/wheel 生命周期保持不变。

## 双参考仓库结论

参考基线：

- CloudNow `f9292868369b0fe41a2d559d0c8f3805193f4389`
- OpenNOW `9299ac5109916c1c1f4b41f7fe7fd944acdb7acb`

CloudNow 使用 Windows VK + Set-1 scan，未依赖 type19；OpenNOW 使用 scan=0 + type19 + Caps synthetic Shift。两套独立实现都可工作，因此这些互相冲突的单边语义不能被当作 GFN 协议硬性要求。

本项目真机 A/B 已证明：Cyberpunk 2077 的有效变量是 Session `keyboardLayout=en-US`。因此 v5.1.9 选择更小、更传统且与本项目 v5.1 初始正常键盘架构一致的 Set-1 路径。

## Soft-freeze

v5.1.9 完成 Cyberpunk 2077 + CS2 真机回归后，Keyboard 模块进入 soft-freeze：

允许继续修改：

```text
UI
settings
layout selector
diagnostics
lifecycle bug fix
```

没有新的可重复真机证据时，不再修改：

```text
VK
scan
modifier
packet framing
input type
Caps compatibility semantics
```
