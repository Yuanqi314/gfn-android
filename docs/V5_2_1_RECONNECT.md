# v5.2.1 Same-Session Reconnect

## Goal

Recover a broken WebRTC transport without creating a second CloudMatch Session.

```text
current Session ID
+ immutable ResolvedLaunchProfile
        ↓
transport failure
        ↓
release / drain input
        ↓
same-session RESUME / Claim
        ↓
refreshed connection/signaling information
        ↓
new signaling transport
        ↓
new PeerConnection
        ↓
new DataChannels
        ↓
new input_channel_v1 handshake
        ↓
FIRST FRAME + protocolReady
```

## Hard invariants

- Reconnect never calls `createSession`.
- Reclaimed `SessionInfo.sessionId` must equal the original Session ID.
- Reconnect never resolves persistent Settings again.
- Reconnect uses the existing frozen `ResolvedLaunchProfile` byte-for-byte at the Kotlin model level.
- `control_channel.exitMessage` remains terminal and must not trigger recovery.
- HTTP 404/410 during reclaim is terminal evidence; other errors stay retryable unless independently proven terminal.

## Recovery policy

`DISCONNECTED` receives a 7 second local grace window. If ICE/PC becomes healthy during the grace period, no CloudMatch request is made.

Hard `FAILED` skips the grace period. Reclaim/rebuild is bounded to three attempts. After a failed attempt, local retry delays are 1 second and then 3 seconds. These delays are **client policy**, not claimed NVIDIA protocol requirements.

## Success gate

A new transport is not considered recovered merely because ICE says connected. v5.2.1 requires:

```text
sameSessionIdVerified
frozenProfileVerified
video.firstFrameRendered
input.protocolReady
```

This proves both media and the newly created `input_channel_v1` path are usable.

## Surface/input lifecycle fix

Transport teardown intentionally clears the old `GfnVideoSurfaceView.inputListener`. The Surface itself can remain mounted across reconnect. Therefore every new WebRTC generation re-installs the dynamic input listener after creating its new `GfnKeyboardMouseInputController`; the listener resolves the current controller at event time and cannot retain the old generation.
