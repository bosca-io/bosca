package bosca.pages

import gg.jte.Content
import gg.jte.TemplateOutput
import gg.jte.support.LocalizationSupport
import java.text.MessageFormat

/**
 * A [LocalizationSupport] backed by an in-memory map of `key -> value`.
 *
 * Templates call [localize] synchronously while rendering, so the strings must be resolved
 * ahead of time (e.g. loaded from the localization service for a given locale) and handed to
 * this class. Values may contain [MessageFormat] placeholders (`{0}`, `{1}`, ...) which are
 * substituted by the vararg overload of [localize] — mirroring [Localization] so a map source
 * is a drop-in replacement for the [java.util.ResourceBundle]-backed implementation.
 *
 * A missing key resolves to the key itself, matching [Localization]'s behaviour.
 */
class MapLocalization(
    private val strings: Map<String, String>
) : LocalizationSupport {

    override fun lookup(key: String): String? = strings[key]

    override fun localize(key: String): Content {
        val value = lookup(key) ?: return LocalizedContent(key)
        return LocalizedContent(value)
    }

    override fun localize(key: String, vararg params: Any?): Content {
        val value = lookup(key) ?: return LocalizedContent(key)
        val formatted = MessageFormat.format(value, *params)
        return LocalizedContent(formatted)
    }
}

private class LocalizedContent(private val formatted: String) : Content {

    override fun writeTo(output: TemplateOutput) {
        output.writeContent(formatted)
    }

    override fun toString() = formatted
}
