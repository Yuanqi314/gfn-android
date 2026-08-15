# v5.3 True-Device Test Guide

## Preconditions

Use a new v5.3 build while retaining the already verified stream profile. Do not mix codec/HDR/audio experiments into the same test.

Recommended baseline:

```text
1920x1080
60 FPS
H.264
Stereo 2ch
Keyboard en-US
Game language zh_CN
known-good bitrate (20 or the already verified 100 Mbps)
```

Connect one Bluetooth or USB gamepad that Android exposes with `SOURCE_GAMEPAD` and/or `SOURCE_JOYSTICK`.

## A. Connection and protocol

Open the local stream Overlay and verify:

```text
Gamepad: <device name>
active=true
protocol=<negotiated input protocol>
```

Logcat should contain `GfnGamepad` sends. For protocol v3, changed snapshots should report `bytes=50` and slot-0 bitmap `0x0101`.

## B. Button matrix

Test every item independently:

```text
A B X Y
D-Pad Up Down Left Right
LB RB
LT RT
Start Back
L3 R3
```

For LT/RT also verify partial travel if the test game exposes analog trigger values.

Guide/Home is diagnostic only: if Android does not dispatch `KEYCODE_BUTTON_MODE` to the app, absence is not a protocol failure.

## C. Sticks

Test:

```text
left X left/right
left Y up/down
right X left/right
right Y up/down
center rest
small movement inside deadzone
full-scale movement
```

Reject the build if either Y axis is reversed remotely or if a centered stick continuously drifts outside the expected deadzone.

## D. Device removal/reconnect

During the stream:

```text
hold/move a control
↓
disconnect controller
↓
remote state must neutralize
↓
reconnect controller
↓
controller should be rediscovered and resume after current input transport is ready
```

Check the overlay returns bitmap/buttons/axes to neutral after removal.

## E. Overlay and focus lifecycle

While holding a gamepad input:

```text
open local Overlay
close local Overlay
leave/re-enter fullscreen
background/foreground the Activity
```

No button or axis may remain stuck remotely.

## F. Same-session reconnect regression

v5.2.1 has a known deferred true-device issue: the first network recovery can remain black-screen and a second disconnect/reconnect can restore video. Do not treat that pre-existing video defect as a v5.3 gamepad regression.

When a reconnect reaches visible video again, verify the still-connected gamepad works without physically re-pairing it. This checks that the new WebRTC generation re-scanned and rebound the gamepad controller.

## G. Keyboard/mouse regression

Because `GfnVideoSurfaceView` gained gamepad routing, recheck:

```text
Cyberpunk 2077 keyboardLayout=en-US + Caps OFF A/W/S/D
CS2 A/W/S/D
mouse movement/buttons/wheel
```

Keyboard packets themselves were not intentionally changed.
