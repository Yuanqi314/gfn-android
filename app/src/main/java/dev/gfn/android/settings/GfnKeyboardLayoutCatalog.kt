package dev.gfn.android.settings

/** GFN Session keyboardLayout choices. */
data class GfnKeyboardLayoutChoice(
    val code: String,
    val label: String,
    val automatic: Boolean = false,
)

object GfnKeyboardLayoutCatalog {
    const val AUTO = "auto"
    const val DEFAULT = "en-US"

    val choices: List<GfnKeyboardLayoutChoice> = listOf(
        GfnKeyboardLayoutChoice(AUTO, "自动（跟随手机）", automatic = true),
        GfnKeyboardLayoutChoice("en-US", "English (US)"),
        GfnKeyboardLayoutChoice("en-GB", "English (UK)"),
        GfnKeyboardLayoutChoice("de-DE", "Deutsch"),
        GfnKeyboardLayoutChoice("fr-FR", "Français"),
        GfnKeyboardLayoutChoice("es-ES", "Español (España)"),
        GfnKeyboardLayoutChoice("es-MX", "Español (Latinoamérica)"),
        GfnKeyboardLayoutChoice("it-IT", "Italiano"),
        GfnKeyboardLayoutChoice("pt-PT", "Português (Portugal)"),
        GfnKeyboardLayoutChoice("pt-BR", "Português (Brasil)"),
        GfnKeyboardLayoutChoice("pl-PL", "Polski"),
        GfnKeyboardLayoutChoice("da-DK", "Dansk"),
        GfnKeyboardLayoutChoice("nb-NO", "Norsk"),
        GfnKeyboardLayoutChoice("sv-SE", "Svenska"),
        GfnKeyboardLayoutChoice("fi-FI", "Suomi"),
        GfnKeyboardLayoutChoice("ru-RU", "Русский"),
        GfnKeyboardLayoutChoice("tr-TR", "Türkçe"),
        GfnKeyboardLayoutChoice("ja-JP", "日本語"),
        GfnKeyboardLayoutChoice("ko-KR", "한국어"),
        GfnKeyboardLayoutChoice("zh-CN", "简体中文"),
        GfnKeyboardLayoutChoice("zh-TW", "繁體中文"),
    )

    private val supportedCodes = choices.mapTo(linkedSetOf()) { it.code }

    fun normalize(code: String?): String = code?.takeIf(supportedCodes::contains) ?: DEFAULT

    fun choice(code: String): GfnKeyboardLayoutChoice =
        choices.firstOrNull { it.code == normalize(code) }
            ?: error("默认 GFN keyboard layout 不在 catalog 中")
}
