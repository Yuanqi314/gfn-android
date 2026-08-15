package dev.gfn.webrtc

import android.util.Log
import org.webrtc.VideoFrame

/**
 * Stage C2.0 read-only witness for the actual Java VideoFrame.Buffer delivered to the renderer.
 *
 * This intentionally does not call toI420(), retain(), release(), cropAndScale(), or any GL API.
 * It only reads the public M144 buffer metadata already carried by the live frame.
 */
internal object GfnSourceFrameDiagnostics {
    private const val TAG = "GfnHevc10Bit"

    data class Snapshot(
        val bufferClass: String,
        val bufferType: Int,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val timestampNs: Long,
        val texture: Boolean,
        val textureType: String?,
        val textureId: Int?,
        val glTarget: Int?,
        val unscaledWidth: Int?,
        val unscaledHeight: Int?,
    ) {
        val isOesTexture: Boolean
            get() = textureType == VideoFrame.TextureBuffer.Type.OES.name
    }

    fun inspect(frame: VideoFrame): Snapshot {
        val buffer = frame.buffer
        val texture = buffer as? VideoFrame.TextureBuffer
        return Snapshot(
            bufferClass = buffer.javaClass.name,
            bufferType = buffer.bufferType,
            width = buffer.width,
            height = buffer.height,
            rotation = frame.rotation,
            timestampNs = frame.timestampNs,
            texture = texture != null,
            textureType = texture?.type?.name,
            textureId = texture?.textureId,
            glTarget = texture?.type?.glTarget,
            unscaledWidth = texture?.unscaledWidth,
            unscaledHeight = texture?.unscaledHeight,
        )
    }

    fun logObservedFrame(viewId: Int, frame: VideoFrame) {
        val snapshot = try {
            inspect(frame)
        } catch (error: RuntimeException) {
            Log.w(
                TAG,
                "phase=SOURCE_FRAME_UNRESOLVED view=$viewId error=${error.javaClass.simpleName} " +
                    "message=\"${error.message ?: "buffer metadata query failed"}\"",
            )
            return
        }

        Log.i(
            TAG,
            "phase=SOURCE_FRAME view=$viewId bufferClass=${snapshot.bufferClass} " +
                "bufferType=${snapshot.bufferType} size=${snapshot.width}x${snapshot.height} " +
                "rotation=${snapshot.rotation} timestampNs=${snapshot.timestampNs} " +
                "texture=${snapshot.texture} textureType=${snapshot.textureType ?: "NONE"} " +
                "isOes=${snapshot.isOesTexture} textureId=${snapshot.textureId ?: -1} " +
                "glTarget=${snapshot.glTarget ?: -1} " +
                "unscaled=${snapshot.unscaledWidth ?: -1}x${snapshot.unscaledHeight ?: -1} " +
                "toI420Called=false",
        )
    }
}
