package dev.gfn.webrtc

import android.content.Context
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
}
