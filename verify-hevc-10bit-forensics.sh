#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/hevc-10bit-forensics-check"
rm -rf "$BUILD"
mkdir -p "$BUILD"

BITSTREAM="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevcBitstreamProbe.kt"
DIAG="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevc10BitDiagnostics.kt"
EGL="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnEglConfigProbe.kt"
EGL10_CONFIG="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnEgl10BitConfig.kt"
EGL10_CAP="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnEgl10BitCapabilityProbe.kt"
RENDER_DIAG="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/Gfn10BitRenderDiagnostics.kt"
SOURCE_FRAME_DIAG="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnSourceFrameDiagnostics.kt"
FACTORY="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevcProductionCapability.kt"
SURFACE="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnVideoSurfaceView.kt"
ENGINE="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt"
CLOUDMATCH="$ROOT/gfn-cloudmatch/src/main/kotlin/dev/gfn/cloudmatch/CloudMatchProtocol.kt"
CONTROLLER="$ROOT/app/src/main/java/dev/gfn/android/stream/GfnStreamingController.kt"
APP_UI="$ROOT/app/src/main/java/dev/gfn/android/ui/GfnAndroidApp.kt"
FULLSCREEN_UI="$ROOT/app/src/main/java/dev/gfn/android/ui/FullscreenStreamScreen.kt"

for file in "$BITSTREAM" "$DIAG" "$EGL" "$EGL10_CONFIG" "$EGL10_CAP" "$RENDER_DIAG" "$SOURCE_FRAME_DIAG"; do
  [ -f "$file" ] || { echo "ERROR: missing v6.1.1 forensic source: $file" >&2; exit 1; }
done

grep -Fq 'val data = buffer.duplicate().slice()' "$BITSTREAM" || { echo 'ERROR: bitstream probe must inspect a duplicate/slice' >&2; exit 1; }
if grep -Eq '\.put\(|buffer\.position\(|buffer\.limit\(' "$BITSTREAM"; then
  echo 'ERROR: bitstream probe must not mutate encoded ByteBuffer state or bytes' >&2
  exit 1
fi
grep -Fq 'for (lengthSize in listOf(4, 3, 2, 1))' "$BITSTREAM" || { echo 'ERROR: legal hvcC length sizes 1..4 are not all inspected' >&2; exit 1; }
grep -Fq 'private const val NAL_TYPE_SPS = 33' "$BITSTREAM" || { echo 'ERROR: SPS NAL type guard missing' >&2; exit 1; }
grep -Fq 'val bitDepthLuma = reader.readUnsignedExpGolomb() + 8' "$BITSTREAM" || { echo 'ERROR: luma bit-depth extraction missing' >&2; exit 1; }
grep -Fq 'val bitDepthChroma = reader.readUnsignedExpGolomb() + 8' "$BITSTREAM" || { echo 'ERROR: chroma bit-depth extraction missing' >&2; exit 1; }
grep -Fq 'private val maxFrames: Int = 180' "$BITSTREAM" || { echo 'ERROR: SPS scan must be bounded' >&2; exit 1; }
grep -Fq 'GfnHevcBitstreamProbeVideoDecoder(' "$FACTORY" || { echo 'ERROR: H265 decoder is not decorated by the read-only SPS probe' >&2; exit 1; }
grep -Fq 'override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus' "$DIAG" || { echo 'ERROR: M144 JNI passes null DecodeInfo; decorator override must accept nullable DecodeInfo' >&2; exit 1; }
grep -Fq 'return delegate.decode(frame, info)' "$DIAG" || { echo 'ERROR: decoder decorator must forward the original EncodedImage and nullable DecodeInfo unchanged' >&2; exit 1; }
grep -Fq 'override fun getImplementationName(): String = delegate.implementationName' "$DIAG" || { echo 'ERROR: decoder implementation identity must stay delegated' >&2; exit 1; }
grep -Fq 'parseRequest(EglBase.CONFIG_PLAIN)' "$EGL" || { echo 'ERROR: pinned WebRTC CONFIG_PLAIN request is not inspected' >&2; exit 1; }
grep -Fq 'EGL14.eglGetCurrentDisplay()' "$EGL" || { echo 'ERROR: runtime EGL current-display query missing' >&2; exit 1; }
grep -Fq 'EGL14.eglGetConfigAttrib' "$EGL" || { echo 'ERROR: runtime EGL config attribute query missing' >&2; exit 1; }
grep -Fq 'addFrameListener(' "$SURFACE" || { echo 'ERROR: render-thread one-shot EGL probe hook missing' >&2; exit 1; }
grep -Fq '0f,' "$SURFACE" || { echo 'ERROR: EGL frame listener must use scale=0 to avoid bitmap capture' >&2; exit 1; }
# v6.1.1-C1: C0 is true-device PASS, so only HEVC+PreferSdr10 views may activate custom RGB10A2.
grep -Fq 'PixelFormat.RGBA_1010102' "$EGL10_CONFIG" || { echo 'ERROR: Stage C RGB10A2 Surface-format contract missing' >&2; exit 1; }
grep -Fq 'EGL14.EGL_RED_SIZE' "$EGL10_CONFIG" || { echo 'ERROR: Stage C red-size attribute missing' >&2; exit 1; }
grep -Fq 'RED_SIZE = 10' "$EGL10_CONFIG" || { echo 'ERROR: Stage C red depth must be 10' >&2; exit 1; }
grep -Fq 'ALPHA_SIZE = 2' "$EGL10_CONFIG" || { echo 'ERROR: Stage C alpha depth must be 2' >&2; exit 1; }
grep -Fq 'EGL14.EGL_CONFIG_ID' "$EGL10_CONFIG" || { echo 'ERROR: Stage C1 must pin the exact preflight EGL_CONFIG_ID' >&2; exit 1; }
grep -Fq 'GfnEgl10BitCapabilityProbe.queryDefaultDisplayEgl14()' "$SURFACE" || { echo 'ERROR: Stage C1 pre-init RGB10A2 capability gate missing' >&2; exit 1; }
grep -Fq 'GfnEgl10BitCapabilityProbe.queryCurrentDisplayEgl14()' "$SURFACE" || { echo 'ERROR: Stage C0/C1 runtime capability witness missing' >&2; exit 1; }
grep -Fq 'GfnEgl10BitConfig.rendererAttributes(selected!!.configId)' "$SURFACE" || { echo 'ERROR: Stage C1 custom renderer is not pinned to the preflight config id' >&2; exit 1; }
grep -Fq 'GlRectDrawer()' "$SURFACE" || { echo 'ERROR: Stage C1 must use the pinned M144 custom-init drawer contract' >&2; exit 1; }
grep -Fq 'init(sharedContext, rendererEvents)' "$SURFACE" || { echo 'ERROR: SDR8/H264 M144 default two-argument init fallback missing' >&2; exit 1; }
grep -Fq 'nativeVisualMatchesRequestedSurfaceFormat == true' "$SURFACE" || { echo 'ERROR: Stage C1 activation must require exact native-visual match' >&2; exit 1; }
if grep -Eq 'holder\.setFormat|setFormat\(PixelFormat\.RGBA_1010102' "$SURFACE"; then
  echo 'ERROR: Stage C1 first experiment must not add SurfaceHolder.setFormat; EGLConfig remains the only render-target variable' >&2
  exit 1
fi
grep -Fq 'fun shouldUseRgb10A2RenderTarget(): Boolean' "$CONTROLLER" || { echo 'ERROR: frozen-profile Stage C1 target gate missing' >&2; exit 1; }
grep -Fq 'config.codec == VideoCodecPreference.Hevc' "$CONTROLLER" || { echo 'ERROR: RGB10A2 target must require HEVC' >&2; exit 1; }
grep -Fq 'config.colorMode == RequestedColorMode.PreferSdr10' "$CONTROLLER" || { echo 'ERROR: RGB10A2 target must require PreferSdr10' >&2; exit 1; }
grep -Fq 'GfnVideoSurfaceView(context, useRgb10A2)' "$APP_UI" || { echo 'ERROR: inline video view does not receive frozen Stage C1 target' >&2; exit 1; }
grep -Fq 'GfnVideoSurfaceView(context, useRgb10A2)' "$FULLSCREEN_UI" || { echo 'ERROR: fullscreen video view does not receive frozen Stage C1 target' >&2; exit 1; }
grep -Fq 'EGL14.eglChooseConfig' "$EGL10_CAP" || { echo 'ERROR: Stage C capability eglChooseConfig probe missing' >&2; exit 1; }
grep -Fq 'EGL14.eglGetConfigAttrib' "$EGL10_CAP" || { echo 'ERROR: Stage C candidate attribute verification missing' >&2; exit 1; }
grep -Fq 'EGL_COLOR_COMPONENT_TYPE_EXT' "$EGL10_CAP" || { echo 'ERROR: Stage C must distinguish fixed-point RGB10A2 from float configs when reported' >&2; exit 1; }
if grep -Eq 'eglCreateContext|eglCreateWindowSurface|eglMakeCurrent|eglDestroySurface|eglDestroyContext' "$EGL10_CAP"; then
  echo 'ERROR: Stage C capability/preflight probe must remain selection-only and must not create/rebind EGL state' >&2
  exit 1
fi
if grep -Eq 'RGBA_1010102|EGL_RED_SIZE[^\n]*10|EGL_GREEN_SIZE[^\n]*10|EGL_BLUE_SIZE[^\n]*10' "$EGL"; then
  echo 'ERROR: baseline runtime EGL probe must remain evidence-only and independent from Stage C target contract' >&2
  exit 1
fi
if grep -RqsE 'PreferHdr10[^[:cntrl:]]*(enabled|true)|sdrHdrMode" to 1|hdr=true' "$ENGINE" "$CLOUDMATCH"; then
  echo 'ERROR: HDR activation must remain OFF during v6.1.1 SDR10 forensics' >&2
  exit 1
fi
# v6.1.1-C2.0: observe the actual Java VideoFrame.Buffer without conversion or ownership changes.
grep -Fq 'buffer as? VideoFrame.TextureBuffer' "$SOURCE_FRAME_DIAG" || { echo 'ERROR: Stage C2.0 TextureBuffer classifier missing' >&2; exit 1; }
grep -Fq 'texture?.type?.name' "$SOURCE_FRAME_DIAG" || { echo 'ERROR: Stage C2.0 texture type witness missing' >&2; exit 1; }
grep -Fq 'texture?.textureId' "$SOURCE_FRAME_DIAG" || { echo 'ERROR: Stage C2.0 texture id witness missing' >&2; exit 1; }
grep -Fq 'texture?.type?.glTarget' "$SOURCE_FRAME_DIAG" || { echo 'ERROR: Stage C2.0 GL target witness missing' >&2; exit 1; }
if grep -Eq '\.(toI420|retain|release|cropAndScale)\(' "$SOURCE_FRAME_DIAG"; then
  echo 'ERROR: Stage C2.0 source-frame witness must not convert, retain, release, crop or scale the live frame' >&2
  exit 1
fi
grep -Fq 'requestRgb10A2 && sourceFrameLogged.compareAndSet(false, true)' "$SURFACE" || { echo 'ERROR: Stage C2.0 witness must be one-shot and scoped to the SDR10/RGB10A2 view' >&2; exit 1; }
grep -Fq 'GfnSourceFrameDiagnostics.logObservedFrame(forensicViewId, frame)' "$SURFACE" || { echo 'ERROR: Stage C2.0 live source-frame hook missing' >&2; exit 1; }
grep -Fq 'super.onFrame(frame)' "$SURFACE" || { echo 'ERROR: Stage C2.0 must forward the original VideoFrame unchanged' >&2; exit 1; }
printf '%s\n' 'V611_HEVC_10BIT_SOURCE_GUARDS=PASS'
printf '%s\n' 'V611_STAGE_C1_RENDER_TARGET_SOURCE_GUARDS=PASS'
printf '%s\n' 'V611_STAGE_C2_SOURCE_FRAME_SOURCE_GUARDS=PASS'

cat > "$BUILD/ParserFixture.kt" <<'KT'
package dev.gfn.webrtc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

private class BitWriter {
    private val bits = mutableListOf<Int>()
    fun bits(value: Long, count: Int) {
        for (index in count - 1 downTo 0) bits += ((value ushr index) and 1L).toInt()
    }
    fun ue(value: Int) {
        val codeNum = value + 1
        val width = 32 - Integer.numberOfLeadingZeros(codeNum)
        repeat(width - 1) { bits += 0 }
        bits(codeNum.toLong(), width)
    }
    fun bytes(): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        bits.forEachIndexed { index, bit ->
            if (bit != 0) out[index / 8] = (out[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
        }
        return out
    }
}

private fun fakeSpsRbsp(
    profileIdc: Int,
    tier: Int,
    levelIdc: Int,
    bitDepthMinus8: Int,
    maxSubLayersMinus1: Int = 0,
): ByteArray {
    val b = BitWriter()
    b.bits(0, 4)
    b.bits(maxSubLayersMinus1.toLong(), 3)
    b.bits(1, 1)
    b.bits(0, 2)
    b.bits(tier.toLong(), 1)
    b.bits(profileIdc.toLong(), 5)
    b.bits(0, 32)
    b.bits(1, 1)
    b.bits(0, 1)
    b.bits(0, 1)
    b.bits(1, 1)
    b.bits(0, 7)
    b.bits(0, 1)
    b.bits(0, 35)
    b.bits(0, 1)
    b.bits(levelIdc.toLong(), 8)
    for (index in 0 until maxSubLayersMinus1) {
        b.bits(0, 1)
        b.bits(0, 1)
    }
    if (maxSubLayersMinus1 > 0) {
        for (index in maxSubLayersMinus1 until 8) b.bits(0, 2)
    }
    b.ue(0)
    b.ue(1)
    b.ue(1920)
    b.ue(1088)
    b.bits(1, 1)
    b.ue(0)
    b.ue(0)
    b.ue(0)
    b.ue(4)
    b.ue(bitDepthMinus8)
    b.ue(bitDepthMinus8)
    return b.bytes()
}

private fun rbspToEbsp(rbsp: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var zeros = 0
    rbsp.forEach { raw ->
        val value = raw.toInt() and 0xFF
        if (zeros >= 2 && value <= 3) {
            out.write(3)
            zeros = 0
        }
        out.write(value)
        zeros = if (value == 0) zeros + 1 else 0
    }
    return out.toByteArray()
}

private fun spsNal(depthMinus8: Int, maxSubLayersMinus1: Int = 0): ByteArray =
    byteArrayOf(0x42, 0x01) + rbspToEbsp(
        fakeSpsRbsp(
            profileIdc = 2,
            tier = 1,
            levelIdc = 153,
            bitDepthMinus8 = depthMinus8,
            maxSubLayersMinus1 = maxSubLayersMinus1,
        ),
    )

private fun annexB(depthMinus8: Int, fourByteStartCode: Boolean = true): ByteArray =
    (if (fourByteStartCode) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)) + spsNal(depthMinus8)

private fun lengthPrefixed(depthMinus8: Int, lengthSize: Int): ByteArray {
    val nal = spsNal(depthMinus8)
    val prefix = ByteArray(lengthSize)
    var value = nal.size
    for (index in lengthSize - 1 downTo 0) {
        prefix[index] = (value and 0xFF).toByte()
        value = value ushr 8
    }
    check(value == 0)
    return prefix + nal
}

fun main() {
    val annex4 = GfnHevcBitstreamParser.inspect(ByteBuffer.wrap(annexB(2, true)))
    val sps = requireNotNull(annex4.sps)
    check(annex4.packaging == GfnHevcNalPackaging.AnnexB)
    check(sps.generalProfileIdc == 2)
    check(sps.generalTierFlag == 1)
    check(sps.generalLevelIdc == 153)
    check(sps.chromaFormatIdc == 1)
    check(sps.codedWidth == 1920 && sps.codedHeight == 1088)
    check(sps.displayWidth == 1920 && sps.displayHeight == 1080)
    check(sps.bitDepthLuma == 10 && sps.bitDepthChroma == 10 && sps.isTenBit)

    check(GfnHevcBitstreamParser.inspect(ByteBuffer.wrap(annexB(2, false))).sps?.isTenBit == true)
    val expectedPackaging = listOf(
        GfnHevcNalPackaging.LengthPrefixed1,
        GfnHevcNalPackaging.LengthPrefixed2,
        GfnHevcNalPackaging.LengthPrefixed3,
        GfnHevcNalPackaging.LengthPrefixed4,
    )
    for (lengthSize in 1..4) {
        val parsed = GfnHevcBitstreamParser.inspect(ByteBuffer.wrap(lengthPrefixed(2, lengthSize)))
        check(parsed.packaging == expectedPackaging[lengthSize - 1]) { parsed }
        check(parsed.sps?.isTenBit == true) { parsed }
    }

    val eightBit = requireNotNull(GfnHevcBitstreamParser.inspect(ByteBuffer.wrap(annexB(0))).sps)
    check(eightBit.bitDepthLuma == 8 && eightBit.bitDepthChroma == 8 && !eightBit.isTenBit)

    val withLeadingSentinel = byteArrayOf(0x55) + annexB(2)
    val positioned = ByteBuffer.wrap(withLeadingSentinel)
    positioned.position(1)
    val positionBefore = positioned.position()
    val limitBefore = positioned.limit()
    check(GfnHevcBitstreamParser.inspect(positioned).sps?.isTenBit == true)
    check(positioned.position() == positionBefore && positioned.limit() == limitBefore)

    val subLayer = GfnHevcBitstreamParser.inspect(
        ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + spsNal(2, maxSubLayersMinus1 = 2)),
    )
    check(subLayer.sps?.isTenBit == true) { subLayer }

    val bounded = GfnHevcBitstreamProbe(maxFrames = 2)
    check(bounded.observe(ByteBuffer.wrap(byteArrayOf(1, 2))) == null)
    val exhausted = requireNotNull(bounded.observe(ByteBuffer.wrap(byteArrayOf(3, 4))))
    check(exhausted.exhausted && exhausted.sps == null && exhausted.frameNumber == 2)
    check(bounded.observe(ByteBuffer.wrap(annexB(2))) == null)

    println("V611_HEVC_SPS_PARSER_FIXTURE=PASS")
    println("SPS profile=${sps.generalProfileIdc} tier=${sps.generalTierFlag} level=${sps.generalLevelIdc} " +
        "coded=${sps.codedWidth}x${sps.codedHeight} display=${sps.displayWidth}x${sps.displayHeight} " +
        "bitDepth=${sps.bitDepthLuma}/${sps.bitDepthChroma}")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 "$BITSTREAM" "$BUILD/ParserFixture.kt" -include-runtime -d "$BUILD/parser.jar"
java -jar "$BUILD/parser.jar"

mkdir -p "$BUILD/java/android/opengl" "$BUILD/java/android/graphics" "$BUILD/java/android/util" "$BUILD/java/org/webrtc" "$BUILD/classes"
cat > "$BUILD/java/android/graphics/PixelFormat.java" <<'JAVA'
package android.graphics; public class PixelFormat { public static final int RGBA_1010102=43; }
JAVA
cat > "$BUILD/java/android/opengl/EGLDisplay.java" <<'JAVA'
package android.opengl; public class EGLDisplay {}
JAVA
cat > "$BUILD/java/android/opengl/EGLContext.java" <<'JAVA'
package android.opengl; public class EGLContext {}
JAVA
cat > "$BUILD/java/android/opengl/EGLSurface.java" <<'JAVA'
package android.opengl; public class EGLSurface {}
JAVA
cat > "$BUILD/java/android/opengl/EGLConfig.java" <<'JAVA'
package android.opengl; public class EGLConfig {}
JAVA
cat > "$BUILD/java/android/opengl/EGL14.java" <<'JAVA'
package android.opengl;
public class EGL14 {
  public static final int EGL_NONE=12344, EGL_CONFIG_ID=12328, EGL_DRAW=12377,
      EGL_RED_SIZE=12324, EGL_GREEN_SIZE=12323, EGL_BLUE_SIZE=12322, EGL_ALPHA_SIZE=12321,
      EGL_RENDERABLE_TYPE=12352, EGL_SURFACE_TYPE=12339, EGL_NATIVE_VISUAL_ID=12334, EGL_EXTENSIONS=12373,
      EGL_WINDOW_BIT=4, EGL_OPENGL_ES2_BIT=4;
  public static final Object EGL_DEFAULT_DISPLAY=new Object();
  public static final EGLDisplay EGL_NO_DISPLAY=new EGLDisplay();
  public static final EGLContext EGL_NO_CONTEXT=new EGLContext();
  public static final EGLSurface EGL_NO_SURFACE=new EGLSurface();
  public static int red=8, green=8, blue=8, alpha=0, renderableType=4, surfaceType=4, nativeVisualId=1, colorComponentType=0x333A;
  public static int returnedCount=1; public static String extensions="EGL_EXT_pixel_format_float";
  public static EGLDisplay eglGetCurrentDisplay(){ return new EGLDisplay(); }
  public static EGLDisplay eglGetDisplay(Object displayId){ return new EGLDisplay(); }
  public static EGLContext eglGetCurrentContext(){ return new EGLContext(); }
  public static EGLSurface eglGetCurrentSurface(int which){ return new EGLSurface(); }
  public static boolean eglQueryContext(EGLDisplay d,EGLContext c,int attr,int[] value,int offset){ value[offset]=7; return true; }
  public static boolean eglQuerySurface(EGLDisplay d,EGLSurface s,int attr,int[] value,int offset){ value[offset]=7; return true; }
  public static int eglGetError(){ return 0; }
  public static String eglQueryString(EGLDisplay d,int name){ return extensions; }
  public static boolean eglChooseConfig(EGLDisplay d,int[] attrs,int attrsOffset,EGLConfig[] configs,int configsOffset,int size,int[] count,int countOffset){
    count[countOffset]=returnedCount;
    if(configs==null || size==0) return true;
    int n=Math.min(Math.min(size, returnedCount), configs.length-configsOffset);
    for(int i=0;i<n;i++) configs[configsOffset+i]=new EGLConfig();
    count[countOffset]=n;
    return true;
  }
  public static boolean eglGetConfigAttrib(EGLDisplay d,EGLConfig c,int attr,int[] value,int offset){
    switch(attr){
      case EGL_CONFIG_ID: value[offset]=7; break;
      case EGL_RED_SIZE: value[offset]=red; break;
      case EGL_GREEN_SIZE: value[offset]=green; break;
      case EGL_BLUE_SIZE: value[offset]=blue; break;
      case EGL_ALPHA_SIZE: value[offset]=alpha; break;
      case EGL_RENDERABLE_TYPE: value[offset]=renderableType; break;
      case EGL_SURFACE_TYPE: value[offset]=surfaceType; break;
      case EGL_NATIVE_VISUAL_ID: value[offset]=nativeVisualId; break;
      case 0x3339: value[offset]=colorComponentType; break;
      default: return false;
    }
    return true;
  }
}
JAVA
cat > "$BUILD/java/android/util/Log.java" <<'JAVA'
package android.util; public class Log { public static int i(String t,String m){return 0;} public static int w(String t,String m){return 0;} public static int e(String t,String m){return 0;} }
JAVA
cat > "$BUILD/java/org/webrtc/EglBase.java" <<'JAVA'
package org.webrtc; public interface EglBase { int[] CONFIG_PLAIN = new int[]{12324,8,12323,8,12322,8,12352,4,12344}; }
JAVA
cat > "$BUILD/java/org/webrtc/VideoCodecStatus.java" <<'JAVA'
package org.webrtc; public enum VideoCodecStatus { OK, ERROR }
JAVA
cat > "$BUILD/java/org/webrtc/EncodedImage.java" <<'JAVA'
package org.webrtc; import java.nio.ByteBuffer; public class EncodedImage { public final ByteBuffer buffer; public EncodedImage(ByteBuffer value){buffer=value;} }
JAVA
cat > "$BUILD/java/org/webrtc/VideoDecoder.java" <<'JAVA'
package org.webrtc; public interface VideoDecoder { class Settings{} class DecodeInfo{} interface Callback{} VideoCodecStatus initDecode(Settings s, Callback c); VideoCodecStatus release(); VideoCodecStatus decode(EncodedImage f, DecodeInfo i); String getImplementationName(); }
JAVA
cat > "$BUILD/java/org/webrtc/VideoFrame.java" <<'JAVA'
package org.webrtc;
public class VideoFrame {
  public interface Buffer {
    default int getBufferType(){ return 0; }
    int getWidth(); int getHeight();
  }
  public interface TextureBuffer extends Buffer {
    enum Type {
      OES(36197), RGB(3553);
      private final int glTarget; Type(int value){glTarget=value;} public int getGlTarget(){return glTarget;}
    }
    Type getType(); int getTextureId();
    default int getUnscaledWidth(){ return getWidth(); }
    default int getUnscaledHeight(){ return getHeight(); }
  }
  private final Buffer buffer; private final int rotation; private final long timestampNs;
  public VideoFrame(Buffer buffer,int rotation,long timestampNs){this.buffer=buffer;this.rotation=rotation;this.timestampNs=timestampNs;}
  public Buffer getBuffer(){return buffer;} public int getRotation(){return rotation;} public long getTimestampNs(){return timestampNs;}
}
JAVA
javac -d "$BUILD/classes" $(find "$BUILD/java" -name '*.java')

cat > "$BUILD/Profile.kt" <<'KT'
package dev.gfn.webrtc
internal enum class GfnHevcProfile(val sdpProfileId: String) { Main("1"), Main10("2") }
KT
cat > "$BUILD/ApiFixture.kt" <<'KT'
package dev.gfn.webrtc

import android.opengl.EGL14
import java.nio.ByteBuffer
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder

private class Delegate : VideoDecoder {
    var seenFrame: EncodedImage? = null
    override fun initDecode(settings: VideoDecoder.Settings, decodeCallback: VideoDecoder.Callback) = VideoCodecStatus.OK
    override fun release() = VideoCodecStatus.OK
    var seenInfo: VideoDecoder.DecodeInfo? = VideoDecoder.DecodeInfo()
    override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        seenFrame = frame
        seenInfo = info
        return VideoCodecStatus.OK
    }
    override fun getImplementationName() = "delegate"
}

private fun List<Int>.windowedValue(key: Int): Int? {
    var index = 0
    while (index + 1 < size) {
        if (this[index] == EGL14.EGL_NONE) return null
        if (this[index] == key) return this[index + 1]
        index += 2
    }
    return null
}

fun main() {
    val request = GfnEglConfigProbe.webRtcPlainRequest()
    check(request.red == 8 && request.green == 8 && request.blue == 8)
    check(request.alpha == null)

    val target = GfnEgl10BitConfig.rendererAttributes().toList()
    check(GfnEgl10BitConfig.surfacePixelFormat == 43)
    check(target.windowedValue(EGL14.EGL_RED_SIZE) == 10)
    check(target.windowedValue(EGL14.EGL_GREEN_SIZE) == 10)
    check(target.windowedValue(EGL14.EGL_BLUE_SIZE) == 10)
    check(target.windowedValue(EGL14.EGL_ALPHA_SIZE) == 2)
    check(target.windowedValue(EGL14.EGL_SURFACE_TYPE) == EGL14.EGL_WINDOW_BIT)
    check(target.windowedValue(EGL14.EGL_RENDERABLE_TYPE) == EGL14.EGL_OPENGL_ES2_BIT)
    val pinnedTarget = GfnEgl10BitConfig.rendererAttributes(65).toList()
    check(pinnedTarget.windowedValue(EGL14.EGL_CONFIG_ID) == 65)

    EGL14.red = 10; EGL14.green = 10; EGL14.blue = 10; EGL14.alpha = 2
    EGL14.surfaceType = EGL14.EGL_WINDOW_BIT; EGL14.renderableType = EGL14.EGL_OPENGL_ES2_BIT
    EGL14.nativeVisualId = 43; EGL14.colorComponentType = GfnEgl10BitCapabilityProbe.EGL_COLOR_COMPONENT_TYPE_FIXED_EXT; EGL14.returnedCount = 1
    val stageC0 = GfnEgl10BitCapabilityProbe.queryCurrentDisplayEgl14()
    check(stageC0.supported) { stageC0 }
    val selected = requireNotNull(stageC0.selected)
    check(selected.isExactRgb10A2)
    check(selected.nativeVisualMatchesRequestedSurfaceFormat == true)
    check(!selected.isExplicitlyFloatColor)
    val stageC1Preflight = GfnEgl10BitCapabilityProbe.queryDefaultDisplayEgl14()
    check(stageC1Preflight.supported) { stageC1Preflight }
    check(stageC1Preflight.selected?.nativeVisualMatchesRequestedSurfaceFormat == true)

    EGL14.colorComponentType = GfnEgl10BitCapabilityProbe.EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT
    val floatExact = GfnEgl10BitCapabilityProbe.queryCurrentDisplayEgl14()
    check(!floatExact.supported && floatExact.selected == null)

    EGL14.colorComponentType = GfnEgl10BitCapabilityProbe.EGL_COLOR_COMPONENT_TYPE_FIXED_EXT
    EGL14.red = 16; EGL14.green = 16; EGL14.blue = 16; EGL14.alpha = 16
    EGL14.nativeVisualId = 22
    val nonExact = GfnEgl10BitCapabilityProbe.queryCurrentDisplayEgl14()
    check(!nonExact.supported && nonExact.selected == null)

    EGL14.red = 8; EGL14.green = 8; EGL14.blue = 8; EGL14.alpha = 0
    EGL14.nativeVisualId = 1; EGL14.returnedCount = 1
    val eight = requireNotNull(GfnEglConfigProbe.queryCurrentEgl14().snapshot)
    check(!eight.isAtLeastTenBitRgb)
    EGL14.red = 10; EGL14.green = 10; EGL14.blue = 10; EGL14.alpha = 2
    val ten = requireNotNull(GfnEglConfigProbe.queryCurrentEgl14().snapshot)
    check(ten.isAtLeastTenBitRgb)
    check(ten.isExactRgb10A2)

    val delegate = Delegate()
    val wrapper = GfnHevcBitstreamProbeVideoDecoder(delegate, "fixture.hevc", GfnHevcProfile.Main10)
    wrapper.initDecode(VideoDecoder.Settings(), object : VideoDecoder.Callback {})
    val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
    buffer.position(1)
    val frame = EncodedImage(buffer)
    val beforePosition = buffer.position()
    val beforeLimit = buffer.limit()
    check(wrapper.decode(frame, null) == VideoCodecStatus.OK)
    check(delegate.seenFrame === frame)
    check(delegate.seenInfo == null)
    check(buffer.position() == beforePosition && buffer.limit() == beforeLimit)
    check(wrapper.implementationName == "delegate")

    println("V611_EGL_API_SHAPED_COMPILE=PASS")
    println("V611_STAGE_C0_RGB10A2_CONTRACT_FIXTURE=PASS")
    println("V611_STAGE_C0_CAPABILITY_PROBE_FIXTURE=PASS")
    println("V611_STAGE_C1_CONFIG_ID_PINNING_FIXTURE=PASS")
    println("V611_STAGE_C1_PREFLIGHT_FIXTURE=PASS")
    println("V611_DECODER_DECORATOR_NON_INTRUSIVE_FIXTURE=PASS")
    println("V611_DECODEINFO_NULL_JNI_FIXTURE=PASS")
    println("EGL_REQUEST=${request.red}/${request.green}/${request.blue} actual8=${eight.red}/${eight.green}/${eight.blue} actual10=${ten.red}/${ten.green}/${ten.blue}")
}
KT

cat > "$BUILD/SourceFrameFixture.kt" <<'KT'
package dev.gfn.webrtc

import org.webrtc.VideoFrame

private class FakeTextureBuffer(
    private val textureType: VideoFrame.TextureBuffer.Type,
) : VideoFrame.TextureBuffer {
    override fun getBufferType(): Int = 0
    override fun getWidth(): Int = 1920
    override fun getHeight(): Int = 1080
    override fun getType(): VideoFrame.TextureBuffer.Type = textureType
    override fun getTextureId(): Int = 77
    override fun getUnscaledWidth(): Int = 1920
    override fun getUnscaledHeight(): Int = 1088
}

private class FakeMemoryBuffer : VideoFrame.Buffer {
    override fun getBufferType(): Int = 1
    override fun getWidth(): Int = 640
    override fun getHeight(): Int = 480
}

fun sourceFrameFixture() {
    val oesFrame = VideoFrame(FakeTextureBuffer(VideoFrame.TextureBuffer.Type.OES), 0, 123456789L)
    val oes = GfnSourceFrameDiagnostics.inspect(oesFrame)
    check(oes.texture)
    check(oes.textureType == "OES")
    check(oes.isOesTexture)
    check(oes.textureId == 77)
    check(oes.glTarget == 36197)
    check(oes.width == 1920 && oes.height == 1080)
    check(oes.unscaledWidth == 1920 && oes.unscaledHeight == 1088)
    check(oes.rotation == 0 && oes.timestampNs == 123456789L)

    val memoryFrame = VideoFrame(FakeMemoryBuffer(), 90, 9L)
    val memory = GfnSourceFrameDiagnostics.inspect(memoryFrame)
    check(!memory.texture)
    check(memory.textureType == null)
    check(!memory.isOesTexture)
    check(memory.textureId == null && memory.glTarget == null)
    println("V611_STAGE_C2_SOURCE_FRAME_FIXTURE=PASS")
}
KT
# Call the C2 fixture from the same executable after API checks.
sed -i '0,/fun main() {/s//fun main() {\n    sourceFrameFixture()/' "$BUILD/ApiFixture.kt"
kotlinc -J-Dfile.encoding=UTF-8 \
  "$BITSTREAM" "$EGL" "$EGL10_CONFIG" "$EGL10_CAP" "$RENDER_DIAG" "$SOURCE_FRAME_DIAG" "$DIAG" "$BUILD/Profile.kt" "$BUILD/SourceFrameFixture.kt" "$BUILD/ApiFixture.kt" \
  -cp "$BUILD/classes" -include-runtime -d "$BUILD/api.jar"
java -cp "$BUILD/api.jar:$BUILD/classes" dev.gfn.webrtc.ApiFixtureKt

printf '%s\n' 'V611_HEVC_10BIT_FORENSICS_VERIFY=PASS'

# Compile the actual GfnVideoSurfaceView against the exact API shape used by the new one-shot hook.
# This catches Kotlin/SAM/overload mistakes in addFrameListener without pretending to be a full Android build.
SURFACE_BUILD="$BUILD/surface-api"
mkdir -p "$SURFACE_BUILD/java/android/content" "$SURFACE_BUILD/java/android/view" "$SURFACE_BUILD/java/org/webrtc" "$SURFACE_BUILD/classes"
cat > "$SURFACE_BUILD/java/android/content/Context.java" <<'JAVA'
package android.content; public class Context {}
JAVA
cat > "$SURFACE_BUILD/java/android/view/InputDevice.java" <<'JAVA'
package android.view;
public class InputDevice {
  public static final int SOURCE_GAMEPAD=0x401; public static final int SOURCE_JOYSTICK=0x1000010;
  public int getSources(){ return 0; }
}
JAVA
cat > "$SURFACE_BUILD/java/android/view/KeyEvent.java" <<'JAVA'
package android.view;
public class KeyEvent {
  public int getRepeatCount(){return 0;} public InputDevice getDevice(){return null;} public int getSource(){return 0;}
}
JAVA
cat > "$SURFACE_BUILD/java/android/view/SurfaceHolder.java" <<'JAVA'
package android.view; public interface SurfaceHolder {}
JAVA
cat > "$SURFACE_BUILD/java/android/view/MotionEvent.java" <<'JAVA'
package android.view;
public class MotionEvent {
  public static final int ACTION_MOVE=2, ACTION_BUTTON_PRESS=11, ACTION_BUTTON_RELEASE=12, ACTION_SCROLL=8;
  public static final int BUTTON_PRIMARY=1, BUTTON_SECONDARY=2, BUTTON_TERTIARY=4;
  public static final int AXIS_RELATIVE_X=27, AXIS_RELATIVE_Y=28, AXIS_VSCROLL=9;
  public int getActionMasked(){return 0;} public int getActionButton(){return 0;} public int getHistorySize(){return 0;}
  public float getHistoricalAxisValue(int axis,int pos){return 0;} public float getAxisValue(int axis){return 0;}
  public InputDevice getDevice(){return null;} public int getSource(){return 0;}
}
JAVA
cat > "$SURFACE_BUILD/java/org/webrtc/EglBase.java" <<'JAVA'
package org.webrtc; public interface EglBase { interface Context {} }
JAVA
cat > "$SURFACE_BUILD/java/org/webrtc/EglRenderer.java" <<'JAVA'
package org.webrtc; public class EglRenderer { public interface FrameListener { void onFrame(Object frame); } }
JAVA
cat > "$SURFACE_BUILD/java/org/webrtc/GlRectDrawer.java" <<'JAVA'
package org.webrtc; public class GlRectDrawer implements RendererCommon.GlDrawer {}
JAVA
mkdir -p "$SURFACE_BUILD/java/android/util"
cat > "$SURFACE_BUILD/java/android/util/Log.java" <<'JAVA'
package android.util; public class Log { public static int i(String t,String m){return 0;} public static int w(String t,String m){return 0;} }
JAVA
cat > "$SURFACE_BUILD/java/org/webrtc/VideoFrame.java" <<'JAVA'
package org.webrtc;
public class VideoFrame {
  public interface Buffer { default int getBufferType(){return 0;} int getWidth(); int getHeight(); }
  public interface TextureBuffer extends Buffer {
    enum Type { OES(36197), RGB(3553); private final int glTarget; Type(int v){glTarget=v;} public int getGlTarget(){return glTarget;} }
    Type getType(); int getTextureId(); default int getUnscaledWidth(){return getWidth();} default int getUnscaledHeight(){return getHeight();}
  }
  public Buffer getBuffer(){return null;} public int getRotation(){return 0;} public long getTimestampNs(){return 0;}
}
JAVA
cat > "$SURFACE_BUILD/java/org/webrtc/RendererCommon.java" <<'JAVA'
package org.webrtc;
public class RendererCommon {
  public interface RendererEvents { void onFirstFrameRendered(); void onFrameResolutionChanged(int w,int h,int rotation); }
  public interface GlDrawer {}
  public enum ScalingType { SCALE_ASPECT_FIT }
}
JAVA
cat > "$SURFACE_BUILD/java/org/webrtc/SurfaceViewRenderer.java" <<'JAVA'
package org.webrtc;
import android.content.Context; import android.view.KeyEvent; import android.view.MotionEvent; import android.view.SurfaceHolder;
public class SurfaceViewRenderer {
  private boolean pointerCapture; public SurfaceViewRenderer(Context context) {}
  public void init(EglBase.Context c, RendererCommon.RendererEvents e) {}
  public void init(EglBase.Context c, RendererCommon.RendererEvents e, int[] attrs, RendererCommon.GlDrawer drawer) {}
  public void addFrameListener(EglRenderer.FrameListener listener, float scale) { listener.onFrame(null); }
  public void setEnableHardwareScaler(boolean enabled) {} public void setMirror(boolean mirror) {}
  public void setScalingType(RendererCommon.ScalingType type) {}
  public void setFocusable(boolean value) {} public boolean isFocusable(){return true;}
  public void setFocusableInTouchMode(boolean value) {} public boolean isFocusableInTouchMode(){return true;}
  public void setClickable(boolean value) {} public boolean isClickable(){return true;}
  public void setOnClickListener(OnClickListener listener) {} public interface OnClickListener { void onClick(Object v); }
  public boolean requestFocus(){return true;} public boolean hasWindowFocus(){return true;}
  public boolean hasPointerCapture(){return pointerCapture;} public void requestPointerCapture(){pointerCapture=true;} public void releasePointerCapture(){pointerCapture=false;}
  public void onWindowFocusChanged(boolean focused) {} public void onPointerCaptureChange(boolean captured) {}
  public boolean onKeyDown(int keyCode, KeyEvent event){return false;} public boolean onKeyUp(int keyCode, KeyEvent event){return false;}
  public boolean onGenericMotionEvent(MotionEvent event){return false;} public boolean onCapturedPointerEvent(MotionEvent event){return false;}
  public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {}
  public void onFrame(VideoFrame frame) {}
  public void release() {}
}
JAVA
javac -d "$SURFACE_BUILD/classes" $(find "$SURFACE_BUILD/java" -name '*.java')
cat > "$SURFACE_BUILD/Stubs.kt" <<'KT'
package dev.gfn.webrtc

import android.view.KeyEvent
import org.webrtc.EglBase

internal object GfnWebRtcRuntime { fun eglContext(): EglBase.Context = object : EglBase.Context {} }
internal data class GfnEglRuntimeConfigSnapshot(val isExactRgb10A2: Boolean = true)
internal data class GfnEglRuntimeProbeResult(val snapshot: GfnEglRuntimeConfigSnapshot? = GfnEglRuntimeConfigSnapshot(), val error: String? = null)
internal object GfnEglConfigProbe { fun queryCurrentEgl14() = GfnEglRuntimeProbeResult() }
internal object GfnHevc10BitDiagnostics {
    fun logPinnedWebRtcEglRequest() {}
    fun logRuntimeEglConfig(viewId: Int, result: GfnEglRuntimeProbeResult) { viewId.hashCode(); result.hashCode() }
}
internal enum class GfnEgl10BitCapabilityStatus { Supported, Unsupported, Unresolved }
internal data class GfnEgl10BitConfigCandidate(
    val configId: Int = 65,
    val nativeVisualMatchesRequestedSurfaceFormat: Boolean? = true,
    val isExplicitlyFloatColor: Boolean = false,
)
internal data class GfnEgl10BitCapabilityResult(
    val status: GfnEgl10BitCapabilityStatus = GfnEgl10BitCapabilityStatus.Supported,
    val selected: GfnEgl10BitConfigCandidate? = GfnEgl10BitConfigCandidate(),
    val error: String? = null,
)
internal object GfnEgl10BitCapabilityProbe {
    fun queryDefaultDisplayEgl14() = GfnEgl10BitCapabilityResult()
    fun queryCurrentDisplayEgl14() = GfnEgl10BitCapabilityResult()
}
internal object GfnEgl10BitConfig {
    val surfacePixelFormat: Int = 43
    fun rendererAttributes(configId: Int? = null) = intArrayOf(configId ?: 0)
}
internal object Gfn10BitRenderDiagnostics {
    fun logEgl10BitCapability(viewId: Int, result: GfnEgl10BitCapabilityResult, phase: String = "EGL10_CAPABILITY") { viewId.hashCode(); result.hashCode(); phase.hashCode() }
    fun logRendererTargetRequest(viewId: Int, requestedRgb10A2: Boolean, activeRgb10A2: Boolean, selectedConfigId: Int?, fallbackReason: String?) { viewId.hashCode(); requestedRgb10A2.hashCode(); activeRgb10A2.hashCode(); selectedConfigId.hashCode(); fallbackReason.hashCode() }
    fun logRuntimeTargetVerdict(viewId: Int, requestedRgb10A2: Boolean, result: GfnEglRuntimeProbeResult) { viewId.hashCode(); requestedRgb10A2.hashCode(); result.hashCode() }
    fun logSurfaceFormat(viewId: Int, requestedRgb10A2: Boolean, activeRgb10A2: Boolean, actualFormat: Int, width: Int, height: Int) { viewId.hashCode(); requestedRgb10A2.hashCode(); activeRgb10A2.hashCode(); actualFormat.hashCode(); width.hashCode(); height.hashCode() }
}
object GfnInputForensics {
    class KeyTrace
    fun traceForSurface(event: KeyEvent) = KeyTrace().also { event.hashCode() }
    fun markSurfaceHandled(trace: KeyTrace, handled: Boolean) { trace.hashCode(); handled.hashCode() }
}
KT
kotlinc -J-Dfile.encoding=UTF-8 "$SURFACE" "$SOURCE_FRAME_DIAG" "$SURFACE_BUILD/Stubs.kt" -cp "$SURFACE_BUILD/classes" -d "$SURFACE_BUILD/surface.jar"
printf '%s\n' 'V611_SURFACE_EGL_HOOK_API_SHAPED_COMPILE=PASS'
printf '%s\n' 'V611_STAGE_C2_SOURCE_FRAME_SURFACE_API_SHAPED_COMPILE=PASS'
