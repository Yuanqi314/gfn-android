package dev.gfn.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

internal object GfnWebRtcRuntime {
    private val lock = Any()
    private val eglBase: EglBase by lazy { EglBase.create() }
    private var initialized = false
    private var factory: PeerConnectionFactory? = null

    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    fun factory(context: Context): PeerConnectionFactory = synchronized(lock) {
        factory?.let { return@synchronized it }
        requireNetworkPermissions(context.applicationContext)
        if (!initialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            initialized = true
        }
        val created = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglContext(), true, false),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext()))
            .createPeerConnectionFactory()
        factory = created
        created
    }

    /**
     * WebRTC NetworkMonitor 会通过 ConnectivityManager 注册网络回调。
     * 这些都是 normal permission，不需要运行时弹窗，但必须出现在最终 merged manifest。
     * 在进入 native/JNI 前主动校验，避免 Java SecurityException 被 WebRTC HandleException
     * 转换为不可捕获的 SIGABRT。
     */
    private fun requireNetworkPermissions(context: Context) {
        val required = listOf(
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
        )
        val missing = required.filter { permission ->
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        check(missing.isEmpty()) {
            "WebRTC 网络权限缺失：${missing.joinToString()}。请检查最终 merged manifest。"
        }
    }
}
