# v6.1.1 — WebRTC M144 DecodeInfo null JNI crash fix

## True-device failure

`47.log` / `tombstone_12` captured a SIGABRT on the WebRTC `DecodingQueue` immediately after
`AndroidVideoDecoder: initDecodeInternal done`.

The Java-side exception immediately preceding the native abort was:

```text
java.lang.NullPointerException: Parameter specified as non-null is null:
method dev.gfn.webrtc.GfnHevcBitstreamProbeVideoDecoder.decode, parameter info
```

WebRTC then reported:

```text
Fatal error in: ../../../sdk/android/src/jni/jvm.cc, line 81
Check failed: false
SIGABRT
```

## Exact M144 contract

The pinned WebRTC source at commit `b1800a61db8320af5c14456c13622d8b85b1ed39`,
`sdk/android/src/jni/video_decoder_wrapper.cc`, intentionally creates an empty Java local reference:

```cpp
ScopedJavaLocalRef<jobject> decode_info;
Java_VideoDecoder_decode(env, decoder_, jinput_image, decode_info);
```

Therefore Java `VideoDecoder.decode()` receives `null` for `DecodeInfo` in this path. The Java API is
unannotated, so Kotlin sees a platform type; a Kotlin decorator must not strengthen it to non-null.

## Fix

`GfnHevcBitstreamProbeVideoDecoder.decode()` now declares:

```kotlin
override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus
```

The wrapper still forwards the exact same `EncodedImage` and exact nullable `DecodeInfo` to the bound
decoder. It does not synthesize a replacement `DecodeInfo`, because v6.1.1 Stage A is evidence-only and
must preserve upstream JNI semantics.

## Verification

```text
V611_HEVC_10BIT_SOURCE_GUARDS=PASS
V611_HEVC_SPS_PARSER_FIXTURE=PASS
V611_EGL_API_SHAPED_COMPILE=PASS
V611_DECODER_DECORATOR_NON_INTRUSIVE_FIXTURE=PASS
V611_DECODEINFO_NULL_JNI_FIXTURE=PASS
V611_HEVC_10BIT_FORENSICS_VERIFY=PASS
V611_SURFACE_EGL_HOOK_API_SHAPED_COMPILE=PASS

V604_HEVC_PRODUCTION_VERIFY=PASS
V610_MAIN10_PRODUCTION_VERIFY=PASS
V604_HEVC_ANSWER_LINEAGE_VERIFY=PASS
V610_MAIN10_ANSWER_LINEAGE_VERIFY=PASS
V610_MAIN10_VERIFY=PASS
V610_WEBRTC_ENGINE_MAIN10_API_SHAPED_COMPILE=PASS
```

Full Android Gradle build is not claimed locally when the Gradle distribution cannot be downloaded.
A new true-device run must confirm the first decoder `decode()` call no longer aborts and then continue
collecting `BITSTREAM_SPS` and `EGL_CONFIG` evidence.
