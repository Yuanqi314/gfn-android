# GFN Android v6.1.1 — First reconnect black-screen root cause and fix

## 1. True-device trigger (`51.log`)

`51.log` first proves that Stage C0 itself is non-destructive and supported on the tested device:

```text
line 621  BITSTREAM_SPS Main10 / 10-bit luma / 10-bit chroma
line 629  FIRST_FRAME
line 636  current renderer EGLConfig = R8 G8 B8 A0
line 637  EGL10_CAPABILITY Supported, configId=65, R10 G10 B10 A2,
          nativeVisualId=43, nativeVisualMatchesSurface=true, explicitFloat=false
```

The stream then renders normally at approximately 60 fps before the transport event:

```text
line 788  c2.qti.hevc.decoder inputFps=61 outputFps=60 renderFps=59
```

Therefore the later black screen is not attributed to the read-only Stage C0 `eglChooseConfig` probe.

## 2. Exact failure chain

At the first transient disconnect:

```text
line 804  engineState=FirstFrame ice=DISCONNECTED pc=CONNECTED reconnect=IDLE/0
line 807  GfnReconnect grace ... delayMs=7000
line 808  engineState=FirstFrame ice=DISCONNECTED pc=DISCONNECTED reconnect=GRACE/0
line 809  GfnReconnect transient recovery without reclaim
```

The same false recovery happens again immediately for the PC callback:

```text
line 811  grace source=pc.DISCONNECTED
line 812  engineState=FirstFrame ice=DISCONNECTED pc=DISCONNECTED reconnect=GRACE/0
line 813  transient recovery without reclaim
```

This is a deterministic controller bug. The old recovery predicate used only the logical `StreamState`:

```text
Connected || FirstFrame
```

`GfnWebRtcEngine` deliberately keeps the last logical media state while updating ICE/PC diagnostics. Therefore a stale `FirstFrame` or `Connected` value can coexist with `ICE=DISCONNECTED` / `PC=DISCONNECTED`. The controller incorrectly treated that stale state as proof of recovery and canceled the 7-second grace timer.

A second occurrence is visible at lines 1472-1482 with the same sequence.

## 3. Why the visible failure is media starvation, not an EGL black frame

After ICE/PC later return to `CONNECTED` (lines 867-869), media does not remain healthy:

```text
line 904  input/output/render = 19/19/18
line 925  input/output/render = 0/0/0
line 947  input/output/render = 0/0/0
line 969  input/output/render = 0/0/0
line 990  input/output/render = 20/20/4
line 1014 input/output/render = 4/4/2
line 1027 EglRenderer received=5 rendered=5 over ~4s, render fps=1.2
```

The decisive signal is decoder **inputFps falling to zero**. The renderer is not receiving a normal 60-fps decoded stream and then painting it black; the media path itself has stalled after the transport interruption.

The underlying network event is observable, but this document does not claim a specific carrier/Wi-Fi cause for the ICE disruption. The client defect is independent and deterministic: reconnect recovery was canceled while ICE/PC were still disconnected.

## 4. Fix: transport health must use diagnostics, not stale logical state

A transient grace recovery may now complete only when all three are true:

```text
logical state = Connected or FirstFrame
ICE           = CONNECTED or COMPLETED
PC            = CONNECTED
```

A stale `FirstFrame` while ICE/PC are disconnected can no longer cancel grace.

Hard `FAILED` behavior remains unchanged: it still skips/accelerates grace into the bounded same-session rebuild path.

## 5. Fix: transport CONNECTED is not enough; require fresh media

`51.log` also proves that ICE/PC can return to CONNECTED while video subsequently stalls. Therefore the grace path has a second gate.

After transport becomes genuinely healthy, the controller starts a fresh recovery-media window. It requires:

```text
fresh VideoSink frame arrivals in a rolling 2-second window
framesInWindow >= requested FPS
last incoming frame age <= 1000 ms
at least one generation-scoped frame that reached the existing renderer path
```

For a 60-fps profile, `framesInWindow >= 60` in a 2-second window is a deliberate client recovery policy. It proves sustained activity without requiring a perfect 60 fps immediately after a network disturbance. It is not claimed to be an NVIDIA protocol requirement.

The render witness uses the pinned M144 `SurfaceViewRenderer.addFrameListener(..., scale=0)` path. M144 removes a frame listener after invoking it, and a `No surface` return occurs before callback notification. The witness is therefore one-shot and does not allocate a bitmap at scale zero.

Incoming-frame liveness and render-path liveness are both required. This avoids accepting either of these incomplete signals alone:

```text
ICE/PC connected but no media                 -> not recovered
incoming frames but no usable render surface  -> not recovered
one isolated rendered frame but media stalls  -> not recovered
```

## 6. Grace expiry behavior

If the 7-second grace expires without both transport and media evidence:

```text
GfnReconnect: grace expired without verified media; rebuilding same Session ...
```

The existing v5.2.1 architecture then performs:

```text
old transport/input drain
same Session ID reclaim / RESUME
same frozen ResolvedLaunchProfile
new signaling / PeerConnection / DataChannels
FIRST_FRAME + input protocolReady success gate
```

No replacement CloudMatch Session is created.

## 7. Stage C boundary

`51.log` closes Stage C0 capability on the tested device:

```text
Stage C0 RGB10A2 capability  TRUE-DEVICE PASS
configId=65
R10 G10 B10 A2
WINDOW/GLES2 compatible
nativeVisualId=43
nativeVisualMatchesSurface=true
explicitFloat=false
```

The user subsequently performed a manual true-device WebRTC disconnect/reconnect test and reported that recovery is normal. No new reconnect log was attached with that report, so this closeout is recorded as **TRUE-DEVICE MANUAL PASS**, not as line-addressable log proof.

This removes the reconnect blocker for the next isolated experiment. Stage C1 may now activate the final RGB10A2 EGL target while keeping the reconnect implementation frozen. HDR remains OFF.

## 8. Verification boundary

Offline/source verification covers:

```text
stale FirstFrame/Connected cannot cancel DISCONNECTED grace
transport recovery requires ICE + PC health
fresh media arrival gate
one-shot render-path witness
black/stalled media causes same-session rebuild after grace
same Session ID + frozen profile invariants
bounded retry behavior
M144/API-shaped engine and SurfaceView compilation
HEVC/Main10/Stage C0 regressions
```

Offline verification plus the user's manual true-device disconnect/reconnect result close the blocker for Stage C1. A future reconnect log can still strengthen the forensic record, but C1 no longer co-varies with reconnect code.
