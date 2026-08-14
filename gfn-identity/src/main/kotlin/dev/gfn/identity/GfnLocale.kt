package dev.gfn.identity

import java.util.Locale

/** NVIDIA API 使用的 locale 代码（下划线格式）。 */
object GfnLocale {
    fun nvidiaCode(locale: Locale = Locale.getDefault()): String {
        val language = locale.language.lowercase()
        val region = locale.country.uppercase()
        return when (language) {
            "zh" -> if (region in setOf("HK", "MO", "TW")) "zh_TW" else "zh_CN"
            "en" -> when (region) {
                "AU" -> "en_AU"; "CA" -> "en_CA"; "GB" -> "en_GB"; "IE" -> "en_IE"
                "IN" -> "en_IN"; "NZ" -> "en_NZ"; "SG" -> "en_SG"; "ZA" -> "en_ZA"
                else -> "en_US"
            }
            "es" -> if (region == "ES") "es_ES" else "es_419"
            "fr" -> if (region == "CA") "fr_CA" else "fr_FR"
            "pt" -> if (region == "PT") "pt_PT" else "pt_BR"
            "de" -> "de_DE"
            "cs" -> "cs_CZ"
            "da" -> "da_DK"
            "hr" -> "hr_HR"
            "hu" -> "hu_HU"
            "id" -> "id_ID"
            "it" -> "it_IT"
            "ja" -> "ja_JP"
            "ko" -> "ko_KR"
            "ms" -> "ms_MY"
            "nb", "no", "nn" -> "nb_NO"
            "nl" -> "nl_NL"
            "pl" -> "pl_PL"
            "ro" -> "ro_RO"
            "sk" -> "sk_SK"
            "fi" -> "fi_FI"
            "sv" -> "sv_SE"
            "th" -> "th_TH"
            "tr" -> "tr_TR"
            "el" -> "el_GR"
            "ru" -> "ru_RU"
            "ar" -> "ar_SA"
            "hi" -> "hi_IN"
            "he" -> "he_IL"
            "vi" -> "vi_VN"
            "uk" -> "uk_UA"
            // CloudNow 当前映射对 Catalan 回退英文。
            "ca" -> "en_US"
            else -> "en_US"
        }
    }
}
