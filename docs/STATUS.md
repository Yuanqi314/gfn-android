# 当前状态 · v5.1.1

## 真机已确认（本轮前）

```text
Auth / restart restore             ✅
Membership                         ✅
Library / Catalog                  ✅
Search / Game Detail               ✅
CloudMatch Create                  ✅
Provision / resolved server        ✅
Claim / RESUME                     ✅
GFN WebSocket signaling            ✅
Offer / Answer / NVST SDP          ✅
ICE / PeerConnection               ✅
H.264 RTP                          ✅
H.264 Decode / Surface             ✅
Keyboard / Mouse 基础输入          ✅ 用户已进入真实串流测试阶段
```

## 最新真机问题与 v5.1.1 状态

```text
鼠标滚轮方向反                  ✅ 源码已修；待复测
全屏未自动横屏/旋转回主页        ✅ ownership/orientation 已修；待复测
偶发无故回主页                   ⚠️ 本次日志只证明“手动旋转”发生 Activity recreation；其他突发情况仍需日志
游戏退出不自动感知               ✅ control_channel/exitMessage 已补；待真机
无声音                           ✅ remote AudioTrack disable 根因已修；待真机
登录记录偶发消失                 ⏸ 暂缓，仅加 diagnostics
```

## 日志已确认

手动旋转这一例：旧 MainActivity Window 被销毁，新的横屏 MainActivity/DecorView 在同一进程创建，随后再次执行 auth restore。因此“手动横屏后回主页”的 captured case 与 Activity recreation + v5.1 UI/Controller ownership 不持久高度吻合。

音频这一例：WebRTC Android AudioTrack 已成功 `initPlayout/startPlayout`，48 kHz，退出时 underrun=0 且已经 delivery 多帧；与此同时 v5.1 源码对 remote `AudioTrack` 调用 `setEnabled(false)`。本轮只把该 track 恢复 enabled。

## v5.1.1 不变量

- Activity/Compose recreation 不再拥有/销毁 Session 与 WebRTC runtime；owner 提升到 `GfnAppRuntimeViewModel`。
- `fullscreenStream/tab` 使用 saveable UI state；rotation 后重新 attach 到现存 runtime。
- Fullscreen 请求 landscape 只是 UX policy；实际 Window bounds 是布局真值；视频仍 aspect-fit。
- `control_channel` 的 observer callback 不允许异常穿出 JNI。
- `exitMessage` 只有当前 generation + 当前 channel 才可触发 terminal transition；重复 terminal event no-op。
- transport reconcile 只把 404/410 当 terminal；其他 response 不推测。
- #4 Auth persistence 不改清理行为，只记录 restore/cleanup reason，不记录 token/ciphertext/key。

## 暂未宣称

```text
v5.1.1 真机声音修复               ⏳
v5.1.1 自动横屏/rotation survival ⏳
v5.1.1 server exitMessage          ⏳
HEVC / Main10 / HDR                ⏳
Gamepad / Touch                    ⏳
```
