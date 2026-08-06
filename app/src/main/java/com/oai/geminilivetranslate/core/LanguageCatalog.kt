package com.oai.geminilivetranslate.core

import java.util.Locale

object LanguageCatalog {
    val entries: List<Pair<String, String>> = listOf(
        "vi" to "Tiếng Việt (vi)", "en" to "English (en)", "ja" to "日本語 (ja)",
        "ko" to "한국어 (ko)", "zh-Hans" to "中文·简体 (zh-Hans)",
        "zh-Hant" to "中文·繁體 (zh-Hant)", "th" to "ไทย (th)", "fr" to "Français (fr)",
        "de" to "Deutsch (de)", "es" to "Español (es)", "pt-BR" to "Português·Brasil (pt-BR)",
        "pt-PT" to "Português·Portugal (pt-PT)", "ru" to "Русский (ru)",
        "id" to "Bahasa Indonesia (id)", "ms" to "Bahasa Melayu (ms)", "hi" to "हिन्दी (hi)",
        "ar" to "العربية (ar)", "it" to "Italiano (it)", "nl" to "Nederlands (nl)",
        "pl" to "Polski (pl)", "tr" to "Türkçe (tr)", "uk" to "Українська (uk)",
        "bn" to "বাংলা (bn)", "fil" to "Filipino (fil)", "fa" to "فارسی (fa)",
        "he" to "עברית (he)", "ur" to "اردو (ur)", "ta" to "தமிழ் (ta)",
        "te" to "తెలుగు (te)", "sw" to "Kiswahili (sw)", "cs" to "Čeština (cs)",
        "da" to "Dansk (da)", "fi" to "Suomi (fi)", "no" to "Norsk (no)",
        "nb" to "Norsk bokmål (nb)", "sv" to "Svenska (sv)", "ro" to "Română (ro)",
        "af" to "Afrikaans (af)", "ak" to "Akan (ak)", "sq" to "Shqip (sq)",
        "am" to "አማርኛ (am)", "hy" to "Հայերեն (hy)", "az" to "Azərbaycanca (az)",
        "eu" to "Euskara (eu)", "be" to "Беларуская (be)", "bg" to "Български (bg)",
        "my" to "မြန်မာ (my)", "ca" to "Català (ca)", "hr" to "Hrvatski (hr)",
        "et" to "Eesti (et)", "gl" to "Galego (gl)", "ka" to "ქართული (ka)",
        "el" to "Ελληνικά (el)", "gu" to "ગુજરાતી (gu)", "ha" to "Hausa (ha)",
        "hu" to "Magyar (hu)", "is" to "Íslenska (is)", "jv" to "Basa Jawa (jv)",
        "kn" to "ಕನ್ನಡ (kn)", "kk" to "Қазақша (kk)", "km" to "ខ្មែរ (km)",
        "rw" to "Kinyarwanda (rw)", "lo" to "ລາວ (lo)", "lv" to "Latviešu (lv)",
        "lt" to "Lietuvių (lt)", "mk" to "Македонски (mk)", "ml" to "മലയാളം (ml)",
        "mr" to "मराठी (mr)", "mn" to "Монгол (mn)", "ne" to "नेपाली (ne)",
        "pa" to "ਪੰਜਾਬੀ (pa)", "sr" to "Српски (sr)", "sd" to "سنڌي (sd)",
        "si" to "සිංහල (si)", "sk" to "Slovenčina (sk)", "sl" to "Slovenščina (sl)",
        "su" to "Basa Sunda (su)", "uz" to "O‘zbekcha (uz)", "zu" to "isiZulu (zu)"
    )

    val codes: List<String> = entries.map { it.first }
    val labels: List<String> = entries.map { it.second }

    fun normalize(raw: String): String? {
        val cleaned = raw.trim().replace('_', '-')
        if (cleaned.isBlank() || !cleaned.matches(Regex("[A-Za-z0-9-]+"))) return null
        val parts = cleaned.split('-').filter { it.isNotBlank() }.toMutableList()
        if (parts.isEmpty() || parts.first().length !in 2..3 || !parts.first().all(Char::isLetter)) return null
        parts[0] = when (parts[0].lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> parts[0].lowercase(Locale.ROOT)
        }
        for (i in 1 until parts.size) {
            val part = parts[i]
            if (part.length !in 2..8) return null
            parts[i] = when {
                part.length == 2 && part.all(Char::isLetter) -> part.uppercase(Locale.ROOT)
                part.length == 4 && part.all(Char::isLetter) -> part.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase)
                else -> part
            }
        }
        return when (val result = parts.joinToString("-")) {
            "zh" -> "zh-Hans"
            "pt" -> "pt-BR"
            else -> result
        }
    }

    fun displayName(code: String): String = entries.firstOrNull { it.first == code }?.second ?: code
}
