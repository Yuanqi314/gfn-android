# v5 参考方案吸收说明

本文件记录用户提供的《v4 真机 Claim/RESUME 通过：Session 软冻结与 v5 进入条件》如何进入 v5，而不替换项目原有架构。

## 已吸收

```text
v4 CloudMatch / Claim soft-freeze
Server ICE entries 与 Effective ICE 分离
ConnectionInfo usage/host/port/resourcePath 诊断
WSS RX/TX envelope 诊断
Offer/Answer/H264 PT 诊断
ICE / PeerConnection 状态诊断
First RTP / First Surface Frame 诊断
H264 / SDR8 / 1080p60 锁定
```

## 保持我们原有方案

```text
GFN protocol ≠ WebRTC ≠ Decoder ≠ Compose
stream-signaling 独立纯 JVM
stream-webrtc 独立 Android 模块
未来 Main10/HDR 使用 direct MediaCodec boundary
CloudNow 只做行为参考，不把 Apple 平台实现移植成 Android 假设
```

## 暂未采用为硬规则

```text
公共 STUN fallback
Apple TLS/证书 workaround
假设固定 signaling 消息先后顺序
假设 server ICE=0 一定需要 fallback
提前实现 Audio/Input/HEVC/Main10/HDR
```

上述项目都要求真实 Android v5 Diagnostics 先给出证据。
