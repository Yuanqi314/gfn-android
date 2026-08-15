package dev.gfn.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.audio.JavaAudioDeviceModule

internal data class GfnVideoCodecCapabilitySnapshot(
    val source: String,
    val index: Int,
    val preferredPayloadType: Int? = null,
    val name: String,
    val mimeType: String? = null,
    val clockRate: Int? = null,
    val parameters: Map<String, String> = emptyMap(),
) {
    val normalizedName: String
        get() = when (name.trim().uppercase()) {
            "HEVC" -> "H265"
            else -> name.trim().uppercase()
        }
}

internal object GfnWebRtcRuntime {
    private val lock = Any()
    private val eglBase: EglBase by lazy { EglBase.create() }
    private var initialized = false
    private var factory: PeerConnectionFactory? = null
    private var decoderCodecNames: Set<String> = emptySet()
    private var decoderCodecCapabilities: List<GfnVideoCodecCapabilitySnapshot> = emptyList()
    private var receiverCodecCapabilities: List<GfnVideoCodecCapabilitySnapshot> = emptyList()
    private var hevcProbeResult: GfnHevcDecoderProbeResult = GfnHevcDecoderProbeResult(emptyList(), null, emptyList())
    private var hevcProductionCapability: GfnHevcDecoderCapability? = null
    private var hevcMain10ProductionCapability: GfnHevcDecoderCapability? = null
    private var hevcAdvertisementReason: String = "WebRTC runtime not initialized"
    private var hevcMain10AdvertisementReason: String = "WebRTC runtime not initialized"

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
        val videoDecoderFactory = GfnHevcAwareVideoDecoderFactory(eglContext())
        hevcProbeResult = videoDecoderFactory.probeResult
        hevcProductionCapability = videoDecoderFactory.productionCapability
        hevcMain10ProductionCapability = videoDecoderFactory.main10ProductionCapability
        hevcAdvertisementReason = videoDecoderFactory.advertisementReason
        hevcMain10AdvertisementReason = videoDecoderFactory.main10AdvertisementReason
        val supportedDecoderCodecs = videoDecoderFactory.getSupportedCodecs().toList()
        decoderCodecNames = supportedDecoderCodecs
            .map { it.name.uppercase() }
            .toSortedSet()
        decoderCodecCapabilities = supportedDecoderCodecs.mapIndexed { index, codec ->
            GfnVideoCodecCapabilitySnapshot(
                source = "GfnHevcAwareVideoDecoderFactory",
                index = index,
                name = codec.name,
                mimeType = "video/${codec.name}",
                clockRate = 90_000,
                parameters = codec.params.orEmpty().toSortedMap(),
            )
        }
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
        receiverCodecCapabilities = created
            .getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            .codecs
            .mapIndexed { index, codec -> codec.toSnapshot("PeerConnectionFactory.receiver", index) }
        factory = created
        created
    }

    fun decoderCodecNames(context: Context): Set<String> {
        factory(context)
        return synchronized(lock) { decoderCodecNames.toSet() }
    }

    fun decoderCodecCapabilities(context: Context): List<GfnVideoCodecCapabilitySnapshot> {
        factory(context)
        return synchronized(lock) { decoderCodecCapabilities.toList() }
    }

    fun receiverCodecCapabilities(context: Context): List<GfnVideoCodecCapabilitySnapshot> {
        factory(context)
        return synchronized(lock) { receiverCodecCapabilities.toList() }
    }

    fun liveVideoReceiverCodecCapabilities(context: Context): List<RtpCapabilities.CodecCapability> =
        factory(context)
            .getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            .codecs
            .toList()

    fun hevcDecoderProbeResult(context: Context): GfnHevcDecoderProbeResult {
        factory(context)
        return synchronized(lock) { hevcProbeResult.copy(candidates = hevcProbeResult.candidates.toList(), errors = hevcProbeResult.errors.toList()) }
    }

    fun hevcProductionCapability(context: Context): GfnHevcDecoderCapability? {
        factory(context)
        return synchronized(lock) { hevcProductionCapability }
    }

    fun hevcMain10ProductionCapability(context: Context): GfnHevcDecoderCapability? {
        factory(context)
        return synchronized(lock) { hevcMain10ProductionCapability }
    }

    fun hevcProductionCapability(context: Context, profile: GfnHevcProfile): GfnHevcDecoderCapability? {
        factory(context)
        return synchronized(lock) {
            when (profile) {
                GfnHevcProfile.Main -> hevcProductionCapability
                GfnHevcProfile.Main10 -> hevcMain10ProductionCapability
            }
        }
    }

    fun hevcAdvertisementReason(context: Context): String {
        factory(context)
        return synchronized(lock) { hevcAdvertisementReason }
    }

    fun hevcMain10AdvertisementReason(context: Context): String {
        factory(context)
        return synchronized(lock) { hevcMain10AdvertisementReason }
    }

    fun hevcAdvertisementReason(context: Context, profile: GfnHevcProfile): String {
        factory(context)
        return synchronized(lock) {
            when (profile) {
                GfnHevcProfile.Main -> hevcAdvertisementReason
                GfnHevcProfile.Main10 -> hevcMain10AdvertisementReason
            }
        }
    }

    fun hevcStreamSupport(
        context: Context,
        profile: GfnHevcProfile = GfnHevcProfile.Main,
        width: Int,
        height: Int,
        fps: Int,
        maxBitrateKbps: Int,
    ): GfnHevcStreamSupport {
        factory(context)
        val capability = synchronized(lock) {
            when (profile) {
                GfnHevcProfile.Main -> hevcProductionCapability
                GfnHevcProfile.Main10 -> hevcMain10ProductionCapability
            }
        } ?: return GfnHevcStreamSupport(
            supported = false,
            sizeAndRateSupported = false,
            bitrateSupported = false,
            bitrateRangeKbps = null,
            reason = synchronized(lock) {
                when (profile) {
                    GfnHevcProfile.Main -> hevcAdvertisementReason
                    GfnHevcProfile.Main10 -> hevcMain10AdvertisementReason
                }
            },
        )
        return GfnHevcDecoderCapabilityProbe.evaluateStream(
            capability = capability,
            width = width,
            height = height,
            fps = fps,
            maxBitrateKbps = maxBitrateKbps,
        )
    }

    private fun RtpCapabilities.CodecCapability.toSnapshot(
        source: String,
        index: Int,
    ): GfnVideoCodecCapabilitySnapshot = GfnVideoCodecCapabilitySnapshot(
        source = source,
        index = index,
        preferredPayloadType = preferredPayloadType,
        name = name,
        mimeType = mimeType,
        clockRate = clockRate,
        parameters = parameters.orEmpty().toSortedMap(),
    )

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
