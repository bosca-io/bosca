package bosca.bible.providers.apibible

import java.util.Locale

/** ISO 15924 code for scripts that cannot be identified. */
internal const val UNKNOWN_SCRIPT_CODE = "Zzzz"

/**
 * ISO 15924 codes keyed by lowercase code and English name. API.Bible reports scripts by
 * name; the explicit names cover scripts the JDK locale data omits.
 */
private val scriptCodes: Map<String, String> = buildMap {
    for (locale in Locale.getAvailableLocales()) {
        val code = locale.script.takeIf { it.isNotBlank() } ?: continue
        put(code.lowercase(), code)
        put(locale.getDisplayScript(Locale.ENGLISH).lowercase(), code)
    }
    for ((code, names) in listOf(
        "Arab" to listOf("Arabic"), "Armn" to listOf("Armenian"), "Bali" to listOf("Balinese"),
        "Bamu" to listOf("Bamum"), "Batk" to listOf("Batak"), "Beng" to listOf("Bengali", "Bangla"),
        "Bugi" to listOf("Buginese"), "Cans" to listOf("Canadian Aboriginal", "Canadian Syllabics",
            "Unified Canadian Aboriginal Syllabics"), "Cher" to listOf("Cherokee"), "Copt" to listOf("Coptic"),
        "Cyrl" to listOf("Cyrillic"), "Deva" to listOf("Devanagari"), "Ethi" to listOf("Ethiopic", "Ge'ez"),
        "Geor" to listOf("Georgian"), "Goth" to listOf("Gothic"), "Grek" to listOf("Greek"),
        "Gujr" to listOf("Gujarati"), "Guru" to listOf("Gurmukhi"), "Hang" to listOf("Hangul"),
        "Hani" to listOf("Han"), "Hans" to listOf("Han (Simplified variant)", "Simplified Han", "Simplified"),
        "Hant" to listOf("Han (Traditional variant)", "Traditional Han", "Traditional"), "Hebr" to listOf("Hebrew"),
        "Java" to listOf("Javanese"), "Jpan" to listOf("Japanese"), "Kali" to listOf("Kayah Li"),
        "Khmr" to listOf("Khmer"), "Knda" to listOf("Kannada"), "Kore" to listOf("Korean"), "Lana" to listOf("Tai Tham"),
        "Laoo" to listOf("Lao"), "Latn" to listOf("Latin"), "Lepc" to listOf("Lepcha"), "Limb" to listOf("Limbu"),
        "Lisu" to listOf("Fraser"), "Mlym" to listOf("Malayalam"), "Mong" to listOf("Mongolian"),
        "Mtei" to listOf("Meetei Mayek"), "Mymr" to listOf("Myanmar", "Burmese"), "Nkoo" to listOf("N'Ko", "Nko"),
        "Olck" to listOf("Ol Chiki"), "Orya" to listOf("Oriya", "Odia"), "Osge" to listOf("Osage"),
        "Plrd" to listOf("Miao", "Pollard Phonetic"), "Samr" to listOf("Samaritan"), "Sinh" to listOf("Sinhala"),
        "Sund" to listOf("Sundanese"), "Syrc" to listOf("Syriac"), "Tavt" to listOf("Tai Viet"),
        "Taml" to listOf("Tamil"), "Telu" to listOf("Telugu"), "Tfng" to listOf("Tifinagh"),
        "Tglg" to listOf("Tagalog", "Baybayin"), "Thaa" to listOf("Thaana"), "Thai" to listOf("Thai"),
        "Tibt" to listOf("Tibetan"), "Vaii" to listOf("Vai"), "Yiii" to listOf("Yi"),
    )) {
        put(code.lowercase(), code)
        for (name in names) put(name.lowercase(), code)
    }
}

/** Resolves an API.Bible script name or ISO 15924 code, using [UNKNOWN_SCRIPT_CODE] when unrecognized. */
internal fun resolveScriptCode(script: String): String = scriptCodes[script.trim().lowercase()] ?: UNKNOWN_SCRIPT_CODE
