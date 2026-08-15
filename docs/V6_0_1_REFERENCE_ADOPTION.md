# v6.0.1 — Reference Adoption

## CloudNow

采用的证据：H265 Main 与 Main10/HDR 是独立能力；SDR HEVC 以 `profile-id=1` 为目标；存在 H264 fallback 与 H265 fmtp compatibility handling。

本版没有复制 CloudNow 的 Main10/custom HDR decoder，也没有提前复制 tier/level rewrite。

## OpenNOW

采用的证据：codec negotiation 支持 createAnswer 前 hook；receiver codec preference 可把 requested codec 放前、保留 fallback；H265 profile preference 与 fallback 是独立步骤。

本版只采用与当前真机失败层对应的最小行为：`pre-createAnswer RtpTransceiver.setCodecPreferences`。不采用 AV1/Main10/HDR，也不一次性加入 tier/level rewrite。

## Android WebRTC API

当前工程依赖 `io.github.webrtc-sdk:android:144.7559.09`。对应 m144 Java API 具备：

```text
PeerConnectionFactory.getRtpReceiverCapabilities(MediaType)
PeerConnection.getTransceivers()
RtpTransceiver.setCodecPreferences(List<RtpCapabilities.CodecCapability>)
RtcError.isError()/error()
```

本地 capability PT 只是该 receiver capability snapshot 的诊断字段，绝不与远端 Offer dynamic PT 做数值兼容判断。

## Reference boundary

```text
reference repo = witness
actual gfn-android Logcat + raw Answer + true-device result = verdict
```
