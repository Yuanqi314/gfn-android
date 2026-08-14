# v4.0.1 Kotlin 跨模块 Smart Cast 编译修复

## 问题

Android/Gradle 分模块编译时报错：

```text
CloudMatchProtocol.kt:234:32
Smart cast to 'String' is impossible,
because 'serverIp' is a public API property declared in different module.
```

原代码：

```kotlin
if (session.serverIp.isNullOrBlank() &&
    parsed.isReadyStatus &&
    !parsed.serverIp.isNullOrBlank()
) {
    val currentHost = hostOf(effectiveBase)
    val resolvedHost = parsed.serverIp.lowercase()
    if (currentHost != resolvedHost) {
        return pollSession(parsed, token)
    }
}
```

`parsed.serverIp` 定义在 `core-model` 模块的 public API 中。即使上一行已经判断非空，Kotlin 也不保证下一次 public property getter 调用仍返回同一个值，因此不能跨模块自动 smart cast。

## 修复

先把跨模块 nullable property 读取一次，保存成本模块稳定局部值：

```kotlin
val resolvedServerIp = parsed.serverIp?.takeIf { it.isNotBlank() }
if (session.serverIp.isNullOrBlank() && parsed.isReadyStatus && resolvedServerIp != null) {
    val currentHost = hostOf(effectiveBase)
    val resolvedHost = resolvedServerIp.lowercase()
    if (currentHost != resolvedHost) {
        return pollSession(parsed, token)
    }
}
```

没有使用 `!!`，也没有改变 Session 路由逻辑。

## 验证

本次新增真实模块边界验证：

```text
core-model.jar
core-network.jar
gfn-identity.jar
gfn-session.jar
       ↓ classpath
gfn-cloudmatch.jar
```

结果：

```text
MODULE_BOUNDARY_COMPILE=PASS
```

随后完整 v4 fixture 回归继续通过：

```text
Create → Queue → Preparing → 双 Ready → Claim/Resume → End
Cancel while Creating → late create → DELETE cleanup
Device Flow / re-bind
登录态恢复
serverInfo → MES → Library → Catalog → Game Detail
```

## 防回归

`verify-core.sh` 已永久加入关键模块分开编译步骤，后续会在交付前直接发现：

- 跨模块 public nullable property smart-cast
- API visibility
- 缺失 module dependency/classpath
- `core-model → gfn-cloudmatch` 类型边界错误

## 变更边界

v4.0.1 不修改 CloudMatch 协议字段、SessionRequest、Queue/Ready 状态机、Claim/Resume、End、Auth、Content 或 UI 行为。

这是纯编译兼容性修复。

**已验证无误。**
