package dev.gfn.cloudmatch

import dev.gfn.core.model.RequestedColorMode
import dev.gfn.identity.GfnClientIdentity

/**
 * Centralized server-visible identity. UI code must never construct these headers directly.
 */
data class GfnRequestContext(
    val token: String,
    val clientId: String,
    val deviceId: String,
    val clientVersion: String,
    val userAgent: String,
    val identity: GfnClientIdentity = GfnClientIdentity.WindowsDesktop,
) {
    fun headers(): Map<String, String> = linkedMapOf(
        "User-Agent" to userAgent,
        "Authorization" to "GFNJWT $token",
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "nv-browser-type" to "CHROME",
        "nv-client-id" to clientId,
        "nv-client-version" to clientVersion,
        "x-device-id" to deviceId,
    ) + identity.protocolHeaders()
}

data class ClientMonitorRequest(
    val monitorId: Int = 0,
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val requestedColorMode: RequestedColorMode,
)

data class RequestedStreamingFeatures(
    /** 0/1 wire encoding is intentionally not assigned yet; fixture work will own that mapping. */
    val tenBitRequested: Boolean,
    val reflexRequested: Boolean = false,
    val l4sRequested: Boolean = false,
)

data class SessionRequestData(
    val appId: String,
    val clientIdentification: String,
    val clientPlatformName: String,
    val deviceHashId: String,
    val clientVersion: String,
    val monitor: ClientMonitorRequest,
    val streamingFeatures: RequestedStreamingFeatures,
)

class SessionRequestFactory(
    private val identity: GfnClientIdentity = GfnClientIdentity.WindowsDesktop,
) {
    fun create(
        appId: String,
        deviceId: String,
        clientVersion: String,
        width: Int,
        height: Int,
        fps: Int,
        colorMode: RequestedColorMode,
    ): SessionRequestData = SessionRequestData(
        appId = appId,
        clientIdentification = identity.clientIdentification,
        clientPlatformName = identity.clientPlatformName,
        deviceHashId = deviceId,
        clientVersion = clientVersion,
        monitor = ClientMonitorRequest(
            width = width,
            height = height,
            framesPerSecond = fps,
            requestedColorMode = colorMode,
        ),
        streamingFeatures = RequestedStreamingFeatures(
            tenBitRequested = colorMode == RequestedColorMode.PreferSdr10 ||
                colorMode == RequestedColorMode.PreferHdr10,
        ),
    )
}
