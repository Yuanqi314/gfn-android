# 当前状态 · v5.1.3 Input Forensics

## 真机已确认

```text
Auth / restart restore             ✅
Membership / Library / Catalog     ✅
Search / Game Detail               ✅
CloudMatch Create / Provision      ✅
Claim / RESUME                     ✅
GFN WebSocket / SDP / ICE          ✅
H.264 RTP / Decode / Surface       ✅
Keyboard / Mouse 基础链            ✅
Audio 有声                         ✅
Audio 扬声器 / 媒体音量             ⏳ v5.1.2 待真机复测
Wheel direction                    ✅
Fullscreen landscape / aspect fit  ✅
control_channel Session End        ✅
```

## 当前唯一重点输入问题

```text
普通字母键（最新明确 K，用户观察疑似所有字母）
→ 远端全屏游戏窗口最小化
```

v5.1.2 的 tracked modifier 修复没有解决该问题，因此 phantom Meta 已从主要假设降级。

## v5.1.3 定义

```text
Input Forensics Only
```

本版不宣称修复 K；只要求下一份真机日志能够回答：

```text
Android 收到什么 KeyEvent？
View 是否 consume？
mapper 输出什么 VK/scan？
trackedMods 是什么？
server handshake 原始字节是什么？
protocolVersion 是 v2 还是 v3？
最终 ByteBuffer 是 18 还是 28 bytes？
position/limit/remaining 是否正确？
binary 是否 true？
sendAccepted 是否 true？
同一时刻是否出现独立 KEYCODE_BACK？
```

## 冻结

`GfnInputProtocol.kt` 与 v5.1.2 SHA-256 相同：

```text
bc4cc1600fe664f077a2848e8009093dd58f8c233010fc3e5d80c2ce290509f2
```

`AndroidKeyboardMapper.kt` 与 v5.1.2 SHA-256 相同：

```text
f767dddb02734193545e56e5b817cc82bd7d2baa53df34429a1e1402a366b69d
```

因此本版没有修改 keyboard wire protocol 或 mapping。

## 暂缓

```text
登录记录偶发消失        ⏸ 继续只保留 diagnostics
偶发非旋转 Home-return  ⏳ 未复现
Gamepad / Touch         ⏳
HEVC / Main10 / HDR     ⏳
```

## 环境边界

当前容器没有 Android SDK/android.jar，所以完整 AGP `assembleDebug` 未执行。v5.1.3 已完成 pure Kotlin golden fixtures 与 Android/WebRTC API-shaped compile；最终仍以真机 Android Studio build 为准。
