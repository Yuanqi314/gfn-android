#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/keyboard-stable-check"
rm -rf "$BUILD"
mkdir -p "$BUILD"

cat > "$BUILD/KeyboardStableFixture.kt" <<'KT'
import dev.gfn.input.GfnInputPacketEncoder
import dev.gfn.input.GfnKey

private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

private fun expectHex(name: String, actual: ByteArray, expected: String) {
    val got = actual.hex()
    check(got == expected) { "$name mismatch\nexpected=$expected\nactual=$got" }
}

fun main() {
    val ts = 0x0102030405060708L
    val encoder = GfnInputPacketEncoder(protocolVersion = 2, timestampMicros = { ts })

    val a = GfnKey(0x41, 0x1E)
    val w = GfnKey(0x57, 0x11)
    val caps = GfnKey(0x14, 0x3A)
    val shift = GfnKey(0xA0, 0x2A, 0x0001)

    expectHex(
        "v2 A down",
        encoder.keyboard(true, a, 0),
        "03 00 00 00 00 41 00 00 00 1E 01 02 03 04 05 06 07 08",
    )
    expectHex(
        "v2 Caps up",
        encoder.keyboard(false, caps, 0),
        "04 00 00 00 00 14 00 00 00 3A 01 02 03 04 05 06 07 08",
    )
    expectHex(
        "v2 Shift down",
        encoder.keyboard(true, shift, 0x0001),
        "03 00 00 00 00 A0 00 01 00 2A 01 02 03 04 05 06 07 08",
    )

    encoder.protocolVersion = 3
    expectHex(
        "v3 W down",
        encoder.keyboard(true, w, 0),
        "23 01 02 03 04 05 06 07 08 22 03 00 00 00 00 57 00 00 00 11 01 02 03 04 05 06 07 08",
    )
    expectHex(
        "v3 Caps up",
        encoder.keyboard(false, caps, 0),
        "23 01 02 03 04 05 06 07 08 22 04 00 00 00 00 14 00 00 00 3A 01 02 03 04 05 06 07 08",
    )

    println("V519_KEYBOARD_STABLE_PACKET_FIXTURE=PASS")
    println("A_SCAN=0x001E W_SCAN=0x0011 CAPS_SCAN=0x003A SHIFT_SCAN=0x002A")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt" \
  "$BUILD/KeyboardStableFixture.kt" \
  -d "$BUILD/check.jar"
kotlin -J-Dfile.encoding=UTF-8 -classpath "$BUILD/check.jar" KeyboardStableFixtureKt

# Production-path static invariants: no probe protocol or wire-mode transform remains.
test ! -e "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardWireMode.kt"
! grep -RqsE 'LOCK_KEYS_SYNC|lockKeysSync|GfnKeyboardWireMode|GfnKeyboardWirePolicy|GfnCapsCompat|GfnLockState|C2_ISO|C3_OPENNOW' \
  "$ROOT/stream-input/src/main" "$ROOT/stream-webrtc/src/main" "$ROOT/app/src/main"
grep -Fq 'KeyEvent.KEYCODE_CAPS_LOCK to k(0x14, 0x3A)' \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/AndroidKeyboardMapper.kt"
grep -Fq 'val packet = encoder.keyboard(true, key, modifiers)' \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt"
grep -Fq 'val packet = encoder.keyboard(false, key, encodedModifiers)' \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt"
grep -Fq 'send(encoder.keyboard(false, command.held.key, command.held.modifiersAtDown))' \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardMouseInputController.kt"
grep -Fq 'scan=${hex16(tx.scanCode)}' \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnInputForensics.kt"

echo 'V519_KEYBOARD_STABLE_STATIC_GUARDS=PASS'
