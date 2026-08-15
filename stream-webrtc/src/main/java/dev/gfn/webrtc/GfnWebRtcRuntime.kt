package dev.gfn.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

internal object GfnWebRtcRuntime {
    private val lock = Any()
    private val eglBase: EglBase by lazy { EglBase.create() }
    private var initialized = false
    private var factory: PeerConnectionFactory? = null
    private var decoderCodecNames: Set<String> = emptySet()

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
        // GFN 是游戏媒体播放，不是 VoIP。libwebrtc 默认使用
        // USAGE_VOICE_COMMUNICATION，部分 Android/OEM 会因此路由到听筒并挂到
        // STREAM_VOICE_CALL。显式使用 GAME/MUSIC 让系统按媒体播放策略选择扬声器/耳机/蓝牙。
        // 不强制指定 built-in speaker，避免覆盖用户真实连接的有线/蓝牙输出设备。
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            // Upstream Android Java ADM defaults to mono. v5.4 explicitly enables its supported
            // stereo playout mode. This API does not expose a 6-channel output configuration.
            .setUseStereoOutput(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .createAudioDeviceModule()
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglContext())
        decoderCodecNames = videoDecoderFactory.supportedCodecs
            .map { it.name.uppercase() }
            .toSortedSet()
        val created = try {
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglContext(), true, false),
                )
                .setVideoDecoderFactory(videoDecoderFactory)
                .createPeerConnectionFactory()
        } finally {
            // PeerConnectionFactory 在创建时取得 ADM 的 native 引用；调用方释放自己的引用。
            adm.release()
        }
        factory = created
        created
    }

    fun decoderCodecNames(context: Context): Set<String> {
        factory(context)
        return synchronized(lock) { decoderCodecNames.toSet() }
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
