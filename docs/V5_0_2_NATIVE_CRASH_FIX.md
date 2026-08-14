# v5.0.2 WebRTC native crash 修复

## 真机证据

`tombstone_08`：

- `SIGABRT`
- 崩溃线程：`network_thread`
- native：`libjingle_peerconnection_so.so`
- fatal：`sdk/android/src/jni/jvm.cc, line 81 / Check failed: false`
- tombstone 内存同时出现 `android_network_monitor.cc`

WebRTC 当前 `jvm.cc` 的该 fatal 路径是 `HandleException()`：native 调用 Java/JNI 后检测到未处理 Java 异常，主动 abort。

## 根因修复

v5.0.1 最终 App Manifest 只有 `INTERNET`。WebRTC NetworkMonitor 使用 `ConnectivityManager.registerNetworkCallback()`，需要 `ACCESS_NETWORK_STATE`；同时移动网络 request 路径需要 `CHANGE_NETWORK_STATE`。

v5.0.2 增加：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

并在 `GfnWebRtcRuntime` 进入 native WebRTC 初始化前校验两个 normal permission，避免 Manifest 合并回归时再次变成 JNI SIGABRT。

## WebRTC 公共 ABI 修复

`GfnVideoSurfaceView` 是公共类：

```kotlin
class GfnVideoSurfaceView(context: Context) : SurfaceViewRenderer(context)
```

因此 `org.webrtc.SurfaceViewRenderer` 已进入 `stream-webrtc` 公共 API/ABI，依赖必须为：

```kotlin
api("io.github.webrtc-sdk:android:144.7559.09")
```

不能再使用 `implementation(...)`。

## 不确定性

`tombstone` 本身没有包含 `ExceptionDescribe()` 输出的 Java 异常类名，因此无法只凭 tombstone 100% 声称异常一定是 `SecurityException`。但崩溃线程、`jvm.cc HandleException`、`android_network_monitor.cc` 字符串以及 v5.0.1 Manifest 缺失 WebRTC 所需网络权限形成了直接且一致的证据链。

如果 v5.0.2 仍发生同一 `jvm.cc:81`，下一步必须取崩溃前 3-5 秒 logcat 的 `System.err` / `rtc` 行，以读取 WebRTC `ExceptionDescribe()` 打出的原始 Java exception。
