#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/wire-ab-check"
rm -rf "$BUILD"
mkdir -p "$BUILD"
cat > "$BUILD/WireAbFixture.kt" <<'KOTLIN'
import dev.gfn.input.GfnInputPacketEncoder
import dev.gfn.input.GfnKey
import dev.gfn.webrtc.GfnKeyboardWireMode
import dev.gfn.webrtc.GfnKeyboardWirePolicy

private data class Case(val name: String, val vk: Int, val scan: Int)

private fun verify(version: Int, c: Case, down: Boolean) {
    val encoder = GfnInputPacketEncoder(version) { 0x0102030405060708L }
    val key = GfnKey(c.vk, c.scan)
    val base = encoder.keyboard(down, key, 0)
    val a = base.copyOf()
    val b = base.copyOf()
    check(GfnKeyboardWirePolicy.applyInPlace(a, version, GfnKeyboardWireMode.SCAN_SET1) == c.scan)
    check(GfnKeyboardWirePolicy.applyInPlace(b, version, GfnKeyboardWireMode.VK_ONLY_SCAN_ZERO) == 0)
    val payloadOffset = if (version >= 3) 10 else 0
    for (i in a.indices) {
        if (i == payloadOffset + 8 || i == payloadOffset + 9) continue
        check(a[i] == b[i]) { "${c.name} v$version changed byte $i" }
    }
    check(GfnKeyboardWirePolicy.readWireScan(a, version) == c.scan)
    check(GfnKeyboardWirePolicy.readWireScan(b, version) == 0)
}

fun main() {
    val cases = listOf(
        Case("W", 0x57, 0x11),
        Case("N", 0x4E, 0x31),
        Case("K", 0x4B, 0x25),
        Case("G", 0x47, 0x22),
    )
    for (version in listOf(2, 3)) {
        for (c in cases) {
            verify(version, c, true)
            verify(version, c, false)
        }
    }
    println("V514_KEYBOARD_WIRE_AB_ONLY_SCAN_BYTES=PASS")
}
KOTLIN
kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/stream-input/src/main/kotlin/dev/gfn/input/GfnInputProtocol.kt" \
  "$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnKeyboardWireMode.kt" \
  "$BUILD/WireAbFixture.kt" \
  -d "$BUILD/check.jar"
kotlin -J-Dfile.encoding=UTF-8 -classpath "$BUILD/check.jar" WireAbFixtureKt
