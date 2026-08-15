# v5.3 — Single-Controller Gamepad

## Goal

v5.3 adds the first production Android gamepad path without changing the soft-frozen keyboard semantics or the v5.2/v5.2.1 Session snapshot/reconnect contract.

```text
Android InputDevice / KeyEvent / MotionEvent
        ↓
GfnGamepadInputController
        ↓
normalized XInput-style slot 0
        ↓
GFN type-12 snapshot
        ↓
reliable input_channel_v1
```

Scope is intentionally limited to one controller:

```text
ABXY
D-Pad
LB / RB
LT / RT
Start / Back
L3 / R3
left stick
right stick
```

`KEYCODE_BUTTON_MODE` is mapped to the XInput Guide bit when Android actually dispatches it. Some devices/system builds can consume that button before the application, so Guide behavior is not claimed as verified before true-device testing.

## Protocol body

Both reference repositories independently use the same 38-byte type-12 body:

```text
0x00  u32 LE  type = 12
0x04  u16 LE  outer body length = 26
0x06  u16 LE  controller index
0x08  u16 LE  connected/style bitmap
0x0A  u16 LE  state length = 20
0x0C  u16 LE  XInput button flags
0x0E  u8      LT
0x0F  u8      RT
0x10  i16 LE  LX
0x12  i16 LE  LY
0x14  i16 LE  RX
0x16  i16 LE  RY
0x18  u16 LE  reserved = 0
0x1A  u16 LE  marker = 0x55
0x1C  u16 LE  reserved = 0
0x1E  u64 LE  timestamp
```

v5.3 uses protocol-v3 **reliable** framing:

```text
[0x23][u64 timestamp BE][0x21][u16 size=38 BE][38-byte body]
```

Total: 50 bytes.

The project currently advertises:

```text
a=ri.enablePartiallyReliableTransferGamepad:0
```

Therefore this version does not send the 0x26 partially-reliable gamepad sequence wrapper. PR capability negotiation is a separate future experiment.

## Bitmap

v5.3 normalizes the one supported controller into virtual slot 0:

```text
bit 0   = controller slot 0 connected
bit 8   = XInput-style virtual controller
bitmap  = 0x0101
```

This is a wire-format normalization choice. It does not assert that the physical Android controller is Xbox-branded.

On device removal a neutral type-12 snapshot with bitmap `0x0000` is sent when the input channel is still available.

## Buttons

```text
D-Pad Up     0x0001
D-Pad Down   0x0002
D-Pad Left   0x0004
D-Pad Right  0x0008
Start        0x0010
Back         0x0020
L3           0x0040
R3           0x0080
LB           0x0100
RB           0x0200
Guide        0x0400
A            0x1000
B            0x2000
X            0x4000
Y            0x8000
```

LT/RT are not encoded as button bits. They are encoded as unsigned 8-bit analog values. Android `BUTTON_L2/R2` is retained only as a digital fallback when a controller reports trigger buttons but no useful analog movement at that instant.

## Axes

- Sticks use a 15% radial deadzone.
- Values outside the deadzone are rescaled to the full range.
- Negative full scale maps to `-32768`; positive full scale maps to `32767`.
- Android Y axes are inverted before XInput wire encoding.
- Trigger MotionRange is normalized from its reported min/max to `0..255`.
- Right stick prefers Android `Z/RZ`, then falls back to `RX/RY`.
- Triggers prefer `LTRIGGER/RTRIGGER`, then fall back to `BRAKE/GAS`.
- Hat X/Y is merged into the D-Pad button bitmap.

## Sending policy

The controller owns a single ordered executor.

```text
state changed               → send current snapshot
no change for 100 ms        → send presence refresh snapshot
bufferedAmount > 64 KiB     → skip replaceable non-forced snapshot
release/teardown            → forced neutral snapshot where transport permits
```

The existing keyboard controller remains responsible for the global raw type-2 input heartbeat. v5.3 does not create a second heartbeat source.

## Lifecycle

Gamepad state participates in the same high-level input lifecycle as keyboard/mouse:

```text
Activity pause/destroy
window focus lost
Overlay open
fullscreen exit
WebRTC disconnect/reconnect
DataChannel close
Session end
```

Before transport teardown, gamepad and keyboard controllers are both drained. Reconnect creates a fresh gamepad controller generation and re-scans currently connected Android input devices after the new input channel handshake.

## Deferred

Not part of v5.3:

```text
multi-controller
rumble/haptics/type13
partially reliable gamepad transport
DualSense-specific reports/touchpad/gyro
controller UI navigation outside the stream
```
