# v3.0.1 Compose 编译修复说明

## 问题

Android/Compose 编译报错：

```text
GfnAndroidApp.kt:12:43
Cannot access 'val RowColumnParentData?.weight: Float':
it is internal in file.
```

## 根因

`GfnAndroidApp.kt` 错误显式导入：

```kotlin
import androidx.compose.foundation.layout.weight
```

当前代码真正需要的是 `Row {}` 作用域公开的 `RowScope.weight()` 成员扩展，而不是同包内部的 `RowColumnParentData?.weight` 属性。

## 修复

删除上述显式 import，保持原调用：

```kotlin
Row {
    OutlinedTextField(
        modifier = Modifier.weight(1f),
        // ...
    )
}
```

由于调用位于 `RowScope` 中，`Modifier.weight(1f)` 由作用域公开 API 正常解析。

## 影响范围

仅修复 Compose 编译符号解析，不改：

- NVIDIA Device Flow / AndroidKeyStore；
- Provider 恢复；
- Account / MES；
- Catalog / Library / Search / Game Detail；
- Windows / GFN-PC 协议身份；
- 第四版 CloudMatch / WebRTC 规划。

## 回归

`./verify-core.sh` 已通过。

全工程额外扫描：未发现 `weight / align / alignBy / alignByBaseline / matchParentSize` 的其他错误显式 import。
