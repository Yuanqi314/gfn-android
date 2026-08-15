package dev.gfn.webrtc

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder

/** v6.1.1 evidence logger. It does not participate in codec selection or Session policy. */
internal object GfnHevc10BitDiagnostics {
    private const val TAG = "GfnHevc10Bit"
    private val eglRequestLogged = AtomicBoolean(false)

    fun logPinnedWebRtcEglRequest() {
        if (!eglRequestLogged.compareAndSet(false, true)) return
        val request = GfnEglConfigProbe.webRtcPlainRequest()
        Log.i(
            TAG,
            "phase=EGL_REQUEST source=WebRTC_M144_CONFIG_PLAIN " +
                "red=${request.red ?: "UNSPECIFIED"} " +
                "green=${request.green ?: "UNSPECIFIED"} " +
                "blue=${request.blue ?: "UNSPECIFIED"} " +
                "alpha=${request.alpha ?: "UNSPECIFIED"} " +
                "renderableType=${request.renderableType ?: "UNSPECIFIED"}",
        )
    }

    fun logRuntimeEglConfig(viewId: Int, result: GfnEglRuntimeProbeResult) {
        val snapshot = result.snapshot
        if (snapshot == null) {
            Log.w(TAG, "phase=EGL_CONFIG view=$viewId success=false reason=\"${result.error ?: "unknown"}\"")
            return
        }
        Log.i(
            TAG,
            "phase=EGL_CONFIG view=$viewId success=true configId=${snapshot.configId} " +
                "red=${snapshot.red} green=${snapshot.green} blue=${snapshot.blue} alpha=${snapshot.alpha} " +
                "renderableType=${snapshot.renderableType} surfaceType=${snapshot.surfaceType} " +
                "tenBitRgbTarget=${snapshot.isAtLeastTenBitRgb}",
        )
    }

    fun logBitstreamObservation(
        decoderComponent: String,
        expectedProfile: GfnHevcProfile?,
        observation: GfnHevcBitstreamProbe.Observation,
    ) {
        val sps = observation.sps
        if (sps == null) {
            Log.w(
                TAG,
                "phase=BITSTREAM_SPS_UNRESOLVED decoder=$decoderComponent " +
                    "expectedProfile=${expectedProfile?.sdpProfileId ?: "generic"} " +
                    "frames=${observation.frameNumber} packaging=${observation.packaging.logValue}",
            )
            return
        }
        Log.i(
            TAG,
            "phase=BITSTREAM_SPS decoder=$decoderComponent " +
                "expectedProfile=${expectedProfile?.sdpProfileId ?: "generic"} " +
                "frame=${observation.frameNumber} packaging=${sps.packaging.logValue} " +
                "profileSpace=${sps.generalProfileSpace} profileIdc=${sps.generalProfileIdc} " +
                "profileCompatibility=0x${sps.generalProfileCompatibilityFlags.toString(16)} " +
                "tier=${sps.generalTierFlag} levelIdc=${sps.generalLevelIdc} " +
                "chromaFormatIdc=${sps.chromaFormatIdc} coded=${sps.codedWidth}x${sps.codedHeight} " +
                "display=${sps.displayWidth}x${sps.displayHeight} " +
                "bitDepthLuma=${sps.bitDepthLuma} bitDepthChroma=${sps.bitDepthChroma} " +
                "tenBit=${sps.isTenBit}",
        )
    }
}

/**
 * Read-only Java VideoDecoder decorator. EncodedImage is inspected synchronously and then passed to
 * the exact delegate unchanged; no retain/release, byte mutation, buffer-position mutation, or
 * asynchronous ownership transfer is introduced.
 */
internal class GfnHevcBitstreamProbeVideoDecoder(
    private val delegate: VideoDecoder,
    private val decoderComponent: String,
    private val expectedProfile: GfnHevcProfile?,
) : VideoDecoder {
    private val probe = GfnHevcBitstreamProbe()

    override fun initDecode(settings: VideoDecoder.Settings, decodeCallback: VideoDecoder.Callback): VideoCodecStatus {
        probe.reset()
        return delegate.initDecode(settings, decodeCallback)
    }

    override fun release(): VideoCodecStatus = delegate.release()

    // Pinned WebRTC M144 JNI intentionally passes a null DecodeInfo local-ref into Java decode().
    // The Java interface is unannotated, so Kotlin must preserve that platform-type nullability.
    // Do not synthesize DecodeInfo: the decorator is evidence-only and must forward JNI semantics unchanged.
    override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        probe.observe(frame.buffer)?.let { observation ->
            GfnHevc10BitDiagnostics.logBitstreamObservation(
                decoderComponent = decoderComponent,
                expectedProfile = expectedProfile,
                observation = observation,
            )
        }
        return delegate.decode(frame, info)
    }

    override fun getImplementationName(): String = delegate.implementationName
}
