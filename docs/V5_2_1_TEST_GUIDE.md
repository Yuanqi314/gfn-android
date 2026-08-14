# v5.2.1 True-Device Reconnect Test Guide

Keep the already verified profile unchanged for the first reconnect test:

```text
1920x1080 @ 60 FPS
H.264 / SDR8
Audio 2ch
Max bitrate 100 Mbps
Keyboard en-US
Game language zh_CN
```

## A. Baseline

1. Start a new game Session.
2. Claim and connect WebRTC.
3. Confirm video, audio, keyboard, mouse and wheel work.
4. Confirm `GfnLaunchProfile WEBRTC` matches the Session snapshot.

## B. Recoverable network interruption

Use an interruption that does not intentionally End the GFN Session, for example temporarily disabling/re-enabling the active network path long enough to break ICE, then restoring it.

Expected log chain:

```text
GfnReconnect grace ...                 (for DISCONNECTED)
GfnReconnect ATTEMPT 1/3 ...
GfnLaunchProfile RECONNECT_CLAIM ...
GfnReconnect RECOVERED ... sameSession=true
GfnReconnect CLAIM_OK sameSession=true frozenProfile=true
new WSS / SDP / ICE
new GfnInputHandshake
FIRST FRAME
GfnReconnect SUCCESS ... firstFrame=true inputHandshake=true
```

Critical assertions:

- Session ID before and after is identical.
- There is no second CloudMatch CREATE.
- keyboard remains `en-US` even if Settings are modified while the Session is active.
- video returns.
- audio returns.
- keyboard/mouse returns without re-entering the page.

## C. Transient disconnect

If ICE/PC returns healthy within the 7 second grace period, expected:

```text
GfnReconnect transient recovery without reclaim
```

and there should be no reconnect Claim.

## D. Terminal Session

Exit the game through a server-recognized game exit path. `control_channel.exitMessage` must remain terminal. The client must not reconnect after that event.

## Evidence boundary

`DataChannel.send=true`, ICE connected, or Claim success alone is insufficient. The reconnect is marked successful only after a new rendered frame and a new input protocol handshake.
