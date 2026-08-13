package dev.gfn.identity

data class GfnClientIdentity(
    val clientIdentification: String,
    val clientPlatformName: String,
    val deviceOs: String,
    val deviceType: String,
    val deviceMake: String,
    val deviceModel: String,
) {
    fun protocolHeaders(): Map<String, String> = linkedMapOf(
        "NV-Device-OS" to deviceOs,
        "NV-Device-Type" to deviceType,
        "NV-Device-Make" to deviceMake,
        "NV-Device-Model" to deviceModel,
        "NV-Client-Type" to "NATIVE",
        "NV-Client-Streamer" to "NVIDIA-CLASSIC",
    )

    companion object {
        val WindowsDesktop = GfnClientIdentity(
            clientIdentification = "GFN-PC",
            clientPlatformName = "windows",
            deviceOs = "WINDOWS",
            deviceType = "DESKTOP",
            deviceMake = "UNKNOWN",
            deviceModel = "UNKNOWN",
        )

        val AndroidGenericTouch = GfnClientIdentity(
            clientIdentification = "GFN-PC",
            clientPlatformName = "Android-Generic-Touch",
            deviceOs = "ANDROID",
            deviceType = "PHONE",
            deviceMake = "UNKNOWN",
            deviceModel = "UNKNOWN",
        )
    }
}
