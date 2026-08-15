# v6.0.1 — True-Device Test Guide

保持单变量：

```text
1920x1080
60 FPS
100 Mbps
Audio 6ch
Keyboard en-US
Game language zh_CN
Video codec HEVC Main · SDR8
```

必须结束旧 Session 后创建新 Session。

先运行：

```text
adb logcat -c
adb logcat -v time GfnHevcCompat:I '*:S'
```

进入游戏后至少等到第一帧。保存全部 `GfnHevcCompat` 行。

优先看：

```text
LOCAL_RECEIVER codec=H265 params=...
OFFER_CODEC codec=H265 profile=1 ...
PREFERENCE_APPLY attempted=true applied=true
RAW_ANSWER_CODEC codec=H265 profile=1 ...
FINAL_ANSWER_CODEC codec=H265 profile=1 ...
DECISION stage=FINAL_ANSWER effective=Hevc fallback=false
MEDIA stage=FIRST_VIDEO_RTP effective=Hevc
MEDIA stage=FIRST_FRAME effective=Hevc
```

若 `PREFERENCE_APPLY applied=true` 但 Raw Answer 仍无 H265 Main，不要改 Main10/HDR；下一步只比较 Offer H265 fmtp 与 LOCAL_RECEIVER H265 parameters。
