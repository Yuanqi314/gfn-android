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

/** CloudNow 当前公开实现中已验证可工作的 GFN PC 协议常量。 */
object GfnProtocolDefaults {
    const val clientId = "ec7e38d4-03af-4b58-b131-cfb0495903ab"
    const val clientVersion = "2.0.86.124"
    const val webOrigin = "https://play.geforcenow.com"
    const val webReferer = "https://play.geforcenow.com/"
    const val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 " +
        "NVIDIACEFClient/HEAD/debb5919f6 GFN-PC/2.0.86.124"
}
