@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.export

import bosca.localization.model.ExportFormat
import bosca.localization.model.LocalizationPluralTranslation
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationTranslation
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.serialization.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Golden-output tests for every exporter. Each test feeds a shared fixture of three
 * strings (plain, plain with ICU placeholder, plural) and asserts key structural
 * properties of the output rather than exact byte-for-byte equality — because
 * whitespace and attribute ordering can drift without breaking consumers, but
 * missing keys, wrong placeholder conversions, or malformed structure would.
 */
class ExporterTest {

    private val plainId = UUID.random()
    private val placeholderId = UUID.random()
    private val pluralId = UUID.random()

    private val strings = listOf(
        LocalizationString(id = plainId, projectId = UUID.random(), key = "welcome"),
        LocalizationString(id = placeholderId, projectId = UUID.random(), key = "greeting"),
        LocalizationString(id = pluralId, projectId = UUID.random(), key = "items_count", plural = true, context = "Cart badge")
    )

    private val translations = mapOf(
        plainId to LocalizationTranslation(stringId = plainId, languageTag = "es", text = "Bienvenido", state = TranslationState.PUBLISHED, origin = TranslationOrigin.HUMAN),
        placeholderId to LocalizationTranslation(stringId = placeholderId, languageTag = "es", text = "Hola {name}, tienes {count, number} mensajes", state = TranslationState.PUBLISHED, origin = TranslationOrigin.HUMAN)
    )

    private val plurals = mapOf(
        pluralId to listOf(
            LocalizationPluralTranslation(stringId = pluralId, languageTag = "es", pluralCategory = "one", text = "{count} artículo", state = TranslationState.PUBLISHED, origin = TranslationOrigin.HUMAN),
            LocalizationPluralTranslation(stringId = pluralId, languageTag = "es", pluralCategory = "other", text = "{count} artículos", state = TranslationState.PUBLISHED, origin = TranslationOrigin.HUMAN)
        )
    )

    // --- JSON_FLAT ---

    @Test
    fun `JSON_FLAT produces a flat key-value object with plural keys expanded by category`() = runTest {
        val result = JsonFlatExporter().export("es", strings, translations, plurals)
        assertEquals("application/json", result.contentType)
        assertEquals("es.json", result.fileName)
        assertTrue(result.content.contains("\"welcome\""), "plain key present")
        assertTrue(result.content.contains("\"Bienvenido\""), "plain value present")
        assertTrue(result.content.contains("\"items_count_one\""), "plural one key")
        assertTrue(result.content.contains("\"items_count_other\""), "plural other key")
        assertTrue(result.content.contains("{name}"), "ICU placeholders pass through verbatim")
    }

    // --- JSON_NESTED ---

    @Test
    fun `JSON_NESTED expands dot-separated keys into nested objects`() = runTest {
        val dotStrings = listOf(
            LocalizationString(id = plainId, projectId = UUID.random(), key = "settings.profile.title")
        )
        val dotTranslations = mapOf(
            plainId to LocalizationTranslation(stringId = plainId, languageTag = "es", text = "Título", state = TranslationState.PUBLISHED, origin = TranslationOrigin.HUMAN)
        )
        val result = JsonNestedExporter().export("es", dotStrings, dotTranslations, emptyMap())
        assertTrue(result.content.contains("\"settings\""), "top-level key present")
        assertTrue(result.content.contains("\"profile\""), "nested key present")
        assertTrue(result.content.contains("\"Título\""), "leaf value present")
    }

    // --- JSON_I18N (Nuxt) ---

    @Test
    fun `JSON_I18N renders plurals as pipe-separated values in CLDR order`() = runTest {
        val result = JsonI18nExporter().export("es", strings, translations, plurals)
        assertTrue(result.content.contains("|"), "pipe separator present for plurals")
        assertTrue(result.content.contains("{name}"), "ICU placeholders pass through for Nuxt")
    }

    // --- ANDROID_XML ---

    @Test
    fun `ANDROID_XML rewrites ICU placeholders to positional specifiers and wraps plurals in plurals element`() = runTest {
        val result = AndroidXmlExporter().export("es", strings, translations, plurals)
        assertEquals("application/xml", result.contentType)
        assertTrue(result.fileName.startsWith("values-es/"), "Android resource path")
        assertTrue(result.content.contains("%1\$s"), "ICU {name} should become %1\$s")
        assertTrue(result.content.contains("%2\$d"), "ICU {count, number} should become %2\$d")
        assertTrue(result.content.contains("<plurals name=\"items_count\">"), "plural strings use <plurals>")
        assertTrue(result.content.contains("<item quantity=\"one\">"), "plural category one present")
        assertTrue(result.content.contains("<item quantity=\"other\">"), "plural category other present")
    }

    // --- IOS_STRINGS ---

    @Test
    fun `IOS_STRINGS skips plural strings and converts ICU to iOS specifiers`() = runTest {
        val result = IosStringsExporter().export("es", strings, translations, plurals)
        assertTrue(result.content.contains("\"welcome\" = \"Bienvenido\";"), "plain key-value pair")
        assertTrue(result.content.contains("%@"), "ICU {name} becomes %@")
        assertTrue(result.content.contains("%d"), "ICU {count, number} becomes %d")
        assertTrue(!result.content.contains("items_count"), "plural strings must be excluded from .strings")
    }

    // --- IOS_STRINGSDICT ---

    @Test
    fun `IOS_STRINGSDICT produces a plist with NSStringPluralRuleType entries and skips non-plural strings`() = runTest {
        val result = IosStringsdictExporter().export("es", strings, translations, plurals)
        assertEquals("application/xml", result.contentType)
        assertTrue(result.content.contains("<plist"), "plist wrapper")
        assertTrue(result.content.contains("NSStringPluralRuleType"), "plural rule type key")
        assertTrue(result.content.contains("<key>items_count</key>"), "plural string key present")
        assertTrue(!result.content.contains("<key>welcome</key>"), "non-plural strings excluded")
    }

    // --- ARB ---

    @Test
    fun `ARB encodes plurals as ICU select syntax and emits metadata keys`() = runTest {
        val result = ArbExporter().export("es", strings, translations, plurals)
        assertEquals("application/json", result.contentType)
        assertTrue(result.content.contains("\"@@locale\""), "locale marker")
        assertTrue(result.content.contains("\"@items_count\""), "metadata key for plural string")
        assertTrue(result.content.contains("{count, plural,"), "ICU plural syntax in value")
    }

    // --- XLIFF ---

    @Test
    fun `XLIFF produces trans-unit elements with source and target`() = runTest {
        val result = XliffExporter().export("es", strings, translations, plurals)
        assertEquals("application/xliff+xml", result.contentType)
        assertTrue(result.content.contains("target-language=\"es\""), "target language attribute")
        assertTrue(result.content.contains("<trans-unit id=\"welcome\">"), "trans-unit for plain string")
        assertTrue(result.content.contains("<target>Bienvenido</target>"), "target text")
        assertTrue(result.content.contains("items_count.one"), "plural category in trans-unit id")
    }

    // --- ANDROID_XML source language filename ---

    @Test
    fun `ANDROID_XML uses values dir for source language and values-tag for other languages`() = runTest {
        val sourceResult = AndroidXmlExporter().export("en", strings, translations, plurals, sourceLanguage = "en")
        assertEquals("values/strings.xml", sourceResult.fileName)

        val targetResult = AndroidXmlExporter().export("es", strings, translations, plurals, sourceLanguage = "en")
        assertEquals("values-es/strings.xml", targetResult.fileName)
    }

    // --- Empty translations ---

    @Test
    fun `exporters produce valid output when no translations match`() = runTest {
        val emptyTranslations = emptyMap<UUID, LocalizationTranslation>()
        val emptyPlurals = emptyMap<UUID, List<LocalizationPluralTranslation>>()

        val xmlResult = AndroidXmlExporter().export("es", strings, emptyTranslations, emptyPlurals)
        assertTrue(xmlResult.content.contains("<resources>"), "Android XML has resources root")
        assertTrue(xmlResult.content.contains("</resources>"), "Android XML closes resources root")

        val jsonResult = JsonFlatExporter().export("es", strings, emptyTranslations, emptyPlurals)
        assertTrue(jsonResult.content.contains("{"), "JSON flat produces valid JSON")

        val xliffResult = XliffExporter().export("es", strings, emptyTranslations, emptyPlurals)
        assertTrue(xliffResult.content.contains("<xliff"), "XLIFF produces valid root")
    }

    // --- XML special characters ---

    @Test
    fun `ANDROID_XML escapes special characters in keys and values`() = runTest {
        val specialId = UUID.random()
        val specialStrings = listOf(
            LocalizationString(id = specialId, projectId = UUID.random(), key = "key&with<special>chars")
        )
        val specialTranslations = mapOf(
            specialId to LocalizationTranslation(
                stringId = specialId, languageTag = "es",
                text = "value with <html> & 'quotes'",
                state = TranslationState.PUBLISHED, origin = TranslationOrigin.HUMAN
            )
        )
        val result = AndroidXmlExporter().export("es", specialStrings, specialTranslations, emptyMap())
        assertTrue(result.content.contains("&amp;"), "ampersand escaped in key")
        assertTrue(result.content.contains("&lt;"), "angle bracket escaped")
        assertTrue(!result.content.contains("<html>"), "raw HTML not present")
    }

    // --- XLIFF source text ---

    @Test
    fun `XLIFF uses source translations for source elements when provided`() = runTest {
        val sourceTranslations = mapOf(
            plainId to LocalizationTranslation(
                stringId = plainId, languageTag = "en",
                text = "Welcome", state = TranslationState.PUBLISHED, origin = TranslationOrigin.HUMAN
            )
        )
        val result = XliffExporter().export("es", strings, translations, plurals, sourceLanguage = "en", sourceTranslations = sourceTranslations)
        assertTrue(result.content.contains("<source>Welcome</source>"), "source element uses actual text")
        assertTrue(result.content.contains("<target>Bienvenido</target>"), "target element present")
    }

    // --- ExportEngine dispatch ---

    @Test
    fun `ExportEngine dispatches to the correct exporter by format`() {
        val engine = ExportEngine()
        assertEquals(ExportFormat.JSON_FLAT, engine.exporterFor(ExportFormat.JSON_FLAT).format)
        assertEquals(ExportFormat.ANDROID_XML, engine.exporterFor(ExportFormat.ANDROID_XML).format)
        assertEquals(ExportFormat.XLIFF, engine.exporterFor(ExportFormat.XLIFF).format)
    }

    @Test
    fun `ExportEngine throws for unknown format after construction with subset`() {
        val engine = ExportEngine(listOf(JsonFlatExporter()))
        assertFailsWith<IllegalArgumentException> {
            engine.exporterFor(ExportFormat.XLIFF)
        }
    }
}
