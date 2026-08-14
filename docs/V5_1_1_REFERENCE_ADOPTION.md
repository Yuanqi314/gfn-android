# v5.1.1 参考方案吸收记录

用户提供的《GFN Android v5.1.1：5 项真机问题定位、日志取证与修复优先级》仅作为**风险清单与验证顺序参考**，没有覆盖本项目自己的模块设计。

## 吸收

- fullscreen 前先解决 recreation-safe ownership。
- `requestedOrientation` 只是 policy，真实 Window bounds 才是布局依据。
- Activity / navigation / Session / WebRTC / Input correlation logging。
- control_channel exitMessage 的 generation guard 与 terminal idempotence。
- transport event + server reconcile 双路径；reconcile 必须保守解释。
- Auth persistence 当前只做 reason-code 诊断。

## 保留本项目自己的实现

- Session、Signaling、WebRTC、Input、UI 继续独立。
- runtime ownership 使用 AndroidViewModel，而不是把 WebRTC/Session 搬进 Compose SavedState。
- Session secondary reconcile 复用已有 `pollSession(currentSession)`，只识别 HTTP 404/410；没有凭参考文档虚构新的 active-session endpoint。
- H.264/ICE/SDP 继续冻结。
- Audio 只做 remote track enable 热修，不提前引入自定义音频 pipeline。
