package dev.gfn.diagnostics

import dev.gfn.core.model.NegotiatedColorMode
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.identity.GfnClientIdentity

data class LocalVideoCapabilities(
    val hevcMain: Boolean = false,
    val hevcMain10: Boolean = false,
    val hdr10Decoder: Boolean = false,
    val displayHdr10: Boolean = false,
)

data class DecoderDiagnostics(
    val codec: String? = null,
    val profile: String? = null,
    val bitDepth: Int? = null,
    val decoderName: String? = null,
    val colorStandard: String? = null,
    val colorTransfer: String? = null,
    val colorRange: String? = null,
)

data class NetworkDiagnostics(
    val fps: Double? = null,
    val bitrateKbps: Int? = null,
    val rttMillis: Int? = null,
    val packetLossPercent: Double? = null,
)

data class DiagnosticsSnapshot(
    val identity: GfnClientIdentity = GfnClientIdentity.WindowsDesktop,
    val localVideo: LocalVideoCapabilities = LocalVideoCapabilities(),
    val requestedColorMode: RequestedColorMode = RequestedColorMode.Automatic,
    val negotiatedColorMode: NegotiatedColorMode = NegotiatedColorMode.Unknown,
    val decoder: DecoderDiagnostics = DecoderDiagnostics(),
    val network: NetworkDiagnostics = NetworkDiagnostics(),
)

class DiagnosticsStore(initial: DiagnosticsSnapshot = DiagnosticsSnapshot()) {
    @Volatile
    private var current: DiagnosticsSnapshot = initial

    fun snapshot(): DiagnosticsSnapshot = current

    @Synchronized
    fun update(transform: (DiagnosticsSnapshot) -> DiagnosticsSnapshot) {
        current = transform(current)
    }
}
