# CloudNow reference notes

Reference repository: `owenselles/CloudNow`
Reference commit used for this baseline: `f9292868369b0fe41a2d559d0c8f3805193f4389`

This project uses CloudNow as an architectural and behavioral reference, not as a source file to translate line-by-line.

## Auth boundary

CloudNow's `CloudNow/Auth/NVIDIAAuthAPI.swift` separates provider discovery, OAuth/device-flow token work, token refresh, user info, stable device identity, and secure token storage. Android should preserve the same separation, replacing Keychain with Android Keystore-backed storage.

## CloudMatch boundary

CloudNow's `CloudNow/Session/CloudMatchClient.swift` centralizes:

- Windows/GFN-PC request headers;
- session create/resume request construction;
- queue/session response parsing;
- color/HDR/bit-depth request semantics.

Android must not scatter these fields through UI/ViewModels.

## Session lifecycle

CloudNow's `CloudNow/Session/SessionOrchestrator.swift` uses an attempt generation token, cancellation-safe create/poll/teardown behavior, indefinite queue polling, bounded setup timeout after queue exit, and multiple consecutive ready observations before streaming starts.

`gfn-session` starts with the same lifecycle invariants, implemented independently in Kotlin.

## Main10 decoder advertisement

CloudNow's `CloudNow/Streaming/GFNVideoDecoderFactory.swift` explicitly preserves H.265 Main10 (`profile-id=2`) in codec advertisement because losing that payload can silently negotiate an 8-bit stream.

Android's future `GfnVideoDecoderFactory` must verify actual upstream WebRTC behavior before adding Main10 advertisement. No assumption is made in the current baseline.

## HDR output

The planned Android HDR path is intentionally separate from the standard renderer:

```text
WebRTC RTP / jitter / ordering
        ↓
encoded HEVC frame
        ↓
MediaCodec Main10
        ↓
SurfaceView
        ↓
Android HDR compositor
```

The project will not treat `bitDepth == 10` alone as proof of HDR.
