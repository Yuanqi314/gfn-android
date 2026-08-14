# 第二版真机测试说明

## 目标

第二版只验收登录，不验收串流。

## 构建

1. 使用 JDK 17。
2. Android Studio 安装 Android SDK 37。
3. 打开工程根目录。
4. 等待 Gradle 同步。
5. 构建并安装 `app` debug variant。

命令行也可以：

```bash
./gradlew :app:assembleDebug
```

## 登录测试

1. 打开应用。
2. 首页点击“登录 GeForce NOW”。
3. 应出现 NVIDIA Device Flow 登录码。
4. 点击“打开 NVIDIA 登录页面”。
5. 在浏览器完成自己的 GFN 账号授权。
6. 返回应用等待自动轮询。
7. 成功后首页应显示账号名称/邮箱/会员等级（服务端有对应字段时）。
8. 强制停止应用并重新打开，应恢复登录态。
9. 点击“退出登录”，再次重启后应保持未登录。

## 需要记录的日志

```bash
adb logcat -s GfnAuth
```

日志只记录阶段与异常类型，不应包含 access token、refresh token、device code 或账号 ID。

## 已知边界

- 游戏库仍是 fixture；
- Catalog / Library 未接真实 API；
- CloudMatch 未接真实 HTTP；
- WebRTC/串流未实现；
- 当前 transport 是 `HttpURLConnection`，请求中途取消可能需要等连接/读取超时返回；
- `display_name` 暂时沿用 CloudNow 当前成功参数 `Apple TV`，后续需要单独验证 Android 自定义名称。

## 失败时优先提供

- `adb logcat -s GfnAuth`；
- UI 上显示的错误文本；
- 失败阶段：获取登录码 / 浏览器授权 / 等待 token / 恢复登录态。

不要上传 access token、refresh token、device code 或完整授权请求。
