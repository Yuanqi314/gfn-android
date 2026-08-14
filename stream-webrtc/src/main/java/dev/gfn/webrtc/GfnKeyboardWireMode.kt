package dev.gfn.webrtc

enum class GfnKeyboardWireMode {
    SCAN_SET1,
    VK_ONLY_SCAN_ZERO,
}

/**
 * v5.1.4 single-variable keyboard wire experiment.
 * Mapping and base encoding stay unchanged; only the final scan field may differ.
 */
object GfnKeyboardWirePolicy {
    fun applyInPlace(
        packet: ByteArray,
        protocolVersion: Int,
        mode: GfnKeyboardWireMode,
    ): Int {
        val payloadOffset = if (protocolVersion >= 3) 10 else 0
        val scanOffset = payloadOffset + 8
        require(packet.size >= scanOffset + 2) { "keyboard packet too short: ${packet.size}" }
        return when (mode) {
            GfnKeyboardWireMode.SCAN_SET1 -> readWireScan(packet, protocolVersion)
            GfnKeyboardWireMode.VK_ONLY_SCAN_ZERO -> {
                packet[scanOffset] = 0
                packet[scanOffset + 1] = 0
                0
            }
        }
    }

    fun readWireScan(packet: ByteArray, protocolVersion: Int): Int {
        val payloadOffset = if (protocolVersion >= 3) 10 else 0
        val scanOffset = payloadOffset + 8
        require(packet.size >= scanOffset + 2) { "keyboard packet too short: ${packet.size}" }
        return ((packet[scanOffset].toInt() and 0xff) shl 8) or
            (packet[scanOffset + 1].toInt() and 0xff)
    }
}
