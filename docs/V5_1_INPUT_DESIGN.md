# v5.1 键鼠输入设计

## 目标

在已经真机成功的 H.264 全屏媒体基础上，只实现 PC 键鼠控制，并把“失焦后卡键”当作一级故障处理。

## 核心不变量

```text
1. 所有 input state mutation 与 packet submission 串行化。
2. 全量 suspension 必须先推进 epoch，再排 release。
3. Pointer Capture 只决定 MouseActive，不决定 KeyboardActive。
4. DataChannel OPEN 不等于 protocolReady。
5. transport 不可用时不宣称远端已 release。
6. reconnect/重新握手先 neutralize uncertain state，再允许新输入。
```

## `releaseAll(reason)`

`releaseAll` 不是简单 `Set.clear()`：

```text
freeze stale admission
→ epoch++
→ snapshot remoteAssumed + uncertain
→ ordinary KeyUp
→ MouseButtonUp
→ modifier KeyUp
→ clear motion/wheel
→ update physical/remote state
```

如果 transport 已经不可用，只能把 remote state 标成 UNKNOWN。

## 为什么需要 epoch

单线程队列只能保证已经入队事件的顺序，不能阻止 release 之后某个旧 producer 把旧 DOWN 再入队。事件生成时捕获 epoch、发送前二次校验，可以把旧 generation 事件丢弃。

## 为什么 physical / remote 要分开

`DataChannel.send()` 返回 true 只证明 packet 被本地 WebRTC 接收；没有 GFN application ACK，不能证明远端游戏处理。因此分别维护：

```text
physical held
remote assumed held
remote uncertain
```

## Pointer Capture

捕获丢失只执行 mouse suspension：

```text
release mouse buttons
clear pending dx/dy
clear wheel
mouseActive=false
```

窗口仍聚焦时键盘继续工作。

## 主动关闭

主动 Session End / user disconnect 能控制顺序，因此：

```text
release first
→ queue barrier
→ bounded local transport drain
→ close
```

异常断线则做不到这一点，只能把远端状态标 UNKNOWN。
