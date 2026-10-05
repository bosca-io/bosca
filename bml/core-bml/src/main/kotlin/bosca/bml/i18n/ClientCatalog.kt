package bosca.bml.i18n

import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The client-side catalog view: ONE locale's strings with the fallback
 * chain already merged server-side (`es-419` <- `es` <- source language; nearer locales win,
 * plural forms replace per key wholesale so categories never mix across languages). The browser
 * helper (`@bosca/bml` `t()`) then needs no fallback logic — just category selection and
 * formatting, which it mirrors from the Kotlin runtime.
 */
suspend fun clientCatalog(source: MessageSource, locale: Locale): MessageCatalog {
    val messages = LinkedHashMap<String, String>()
    val plurals = LinkedHashMap<String, Map<PluralCategory, String>>()
    // Walk the chain from the FAR end so nearer locales overwrite.
    for (candidate in Messages.fallbackChain(locale, source.defaultLocale).asReversed()) {
        val catalog = source.catalog(candidate)
        messages.putAll(catalog.messages)
        plurals.putAll(catalog.plurals)
    }
    return MessageCatalog(messages, plurals)
}

/** The catalog endpoint's wire shape: `{"locale": …, "messages": {…}, "plurals": {k: {ONE: …}}}`. */
fun MessageCatalog.toClientJson(locale: Locale): String = buildJsonObject {
    put("locale", locale.toLanguageTag())
    putJsonObject("messages") {
        for ((key, text) in messages) put(key, text)
    }
    putJsonObject("plurals") {
        for ((key, forms) in plurals) {
            putJsonObject(key) {
                for ((category, text) in forms) put(category.name, text)
            }
        }
    }
}.toString()
