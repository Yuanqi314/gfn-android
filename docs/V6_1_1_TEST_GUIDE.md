# GFN Android v6.1.1 — True-device 10-bit Forensics Test Guide

## 1. Test profile

Use the already proven v6.1.0 Session intent:

```text
codec = HEVC
color = SDR10 / PreferSdr10
1920x1080@60
HDR = OFF
```

Do not change HDR or renderer settings for this test.

## 2. First verify v6.1.0 did not regress

The log must still show the existing chain:

```text
RESOLVED codec=Hevc color=PreferSdr10
CREATE cloudMatchBitDepth=1 sdrHdrMode=0 hdr=false
HEVC_MAIN10_ADVERTISEMENT enabled=true profile=2
OFFER_HEVC_COMPATIBLE targetProfile=2 compatible=true
RAW_ANSWER hevcMain10 != []
FINAL_ANSWER hevcMain10 != []
fallback=false
NVST_CONFIG bitDepth=10 hdr=false
FIRST_VIDEO_RTP effective=Hevc targetProfile=2
bound H265 decoder
FIRST_FRAME effective=Hevc
```

If this chain regresses, stop: v6.1.1 diagnostics must not be used to hide a negotiation regression.

## 3. Stage A — SPS

Search for:

```text
GfnHevc10Bit phase=BITSTREAM_SPS
```

Main10/SDR10 positive evidence:

```text
profileIdc=2
bitDepthLuma=10
bitDepthChroma=10
tenBit=true
```

Also record:

```text
packaging
levelIdc
chromaFormatIdc
coded width/height
display width/height
```

If the only result is:

```text
phase=BITSTREAM_SPS_UNRESOLVED
```

then there is no bit-depth verdict. Preserve the log and inspect framing/frame assembly/parser coverage.

If the parsed SPS says 8/8-bit, do not modify EGL; investigate the Session/server stream first.

## 4. Stage B — static request log

Expect once per process:

```text
GfnHevc10Bit phase=EGL_REQUEST
source=WebRTC_M144_CONFIG_PLAIN
red=8 green=8 blue=8
```

This confirms the app is still using the frozen default WebRTC renderer request and has not silently switched to a custom config.

## 5. Stage B — runtime selected config

After rendered frames begin, expect one-shot:

```text
GfnHevc10Bit phase=EGL_CONFIG
success=true
configId=...
red=...
green=...
blue=...
alpha=...
tenBitRgbTarget=...
```

Do not infer the runtime value from the static request; use the actual log.

## 6. Decision table

```text
SPS 8/8
-> incoming stream not proven 10-bit
-> Session/server investigation

SPS 10/10 + EGL 8/8/8
-> incoming stream is 10-bit
-> final default EGL target is 8bpc
-> next candidate: custom 10-bit EGL target

SPS 10/10 + EGL >=10/10/10
-> keep current renderer
-> continue source texture/buffer preservation forensics

SPS unresolved
-> parser/framing investigation

EGL_CONFIG query failure
-> renderer/EGL query instrumentation investigation
```

## 7. What is still not a PASS

These are insufficient by themselves:

```text
profile-id=2
CloudMatch bitDepth=1
NVST bitDepth=10
FIRST_FRAME
hdr-static-info=<buffer>
```

They do not prove final 10-bit render fidelity.

## 8. HDR / Surface boundaries

HDR remains OFF throughout this test. `EglRenderer: Dropping frame - No surface` stays a separate lifecycle backlog unless it prevents the one-shot EGL query or stable rendering.
