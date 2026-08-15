# v5.3 Reference Adoption

Reference rule: CloudNow and OpenNOW are witnesses, not specification. Only behavior independently supported by both repositories or independently fixture-verified in this project is admitted to the production path.

Reference revisions used:

```text
CloudNow  f9292868369b0fe41a2d559d0c8f3805193f4389
OpenNOW   9299ac5109916c1c1f4b41f7fe7fd944acdb7acb
```

## Adopted from agreement

Both implementations agree on:

- input type `12`;
- 38-byte raw body;
- XInput button flags;
- `u8` LT/RT;
- signed `i16 LE` stick fields;
- connected-controller bitmap model;
- `0x55` marker and little-endian body timestamp;
- radial deadzone around 15% in their current controller mapping;
- neutral/bitmap update when a controller disappears.

The v5.3 packet fixture checks exact offsets, lengths, endianness, reserved bytes and timestamp placement.

## Intentional divergence / unresolved reference difference

### Protocol-v3 transport wrapper

OpenNOW explicitly implements both:

```text
Reliable:
[0x23][timestamp][0x21][size][body]

Partially reliable:
[0x23][timestamp][0x26][index][sequence][0x21][size][body]
```

CloudNow's current encoder uses the per-controller `0x26` sequence wrapper for its v3 gamepad path.

Current gfn-android NVST answer still advertises:

```text
a=ri.enablePartiallyReliableTransferGamepad:0
```

Therefore v5.3 chooses the reliable framing. It does not copy CloudNow's PR wrapper while simultaneously advertising PR disabled.

### Physical controller identity

OpenNOW conditionally sets the high XInput-style bitmap bit based on browser controller identity. v5.3 instead exposes exactly one **normalized XInput-style virtual controller**, so slot 0 uses `0x0101` regardless of physical brand. This is an explicit client abstraction, not an inference about the device vendor.

### Timestamp clock origin

The references are not identical here: CloudNow's current encoder default timestamp provider is wall-clock microseconds, while OpenNOW uses its input session clock. The existing gfn-android input encoder already uses its established timestamp source for keyboard/mouse and that path is true-device verified. v5.3 therefore reuses the existing project input clock instead of changing the whole input timestamp model as part of Gamepad. Only the type-12 **field endianness and placement** are treated as cross-reference agreement.

## Not adopted yet

Both references contain more advanced controller behavior, including rumble/haptics and multiple controller slots. Those are deliberately excluded until v5.3 type-12 input is true-device verified.
