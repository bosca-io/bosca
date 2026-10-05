package bosca.bml.i18n

import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin half of the shared client/server parity contract: every case in
 * `bml-runtime/test/parity/i18n-vectors.json` must produce the same string here and in the
 * TypeScript `t()` (vitest runs the other half). Change the fixture, change both implementations.
 */
class I18nParityTest {

    private val fixture = File("../bml-runtime/test/parity/i18n-vectors.json")

    @Test
    fun `every parity vector matches the Kotlin resolver`() {
        assertTrue(fixture.isFile, "parity fixture missing at ${fixture.absolutePath}")
        val root = Json.parseToJsonElement(fixture.readText()).jsonObject
        val defaultLocale = Locale.forLanguageTag(root.getValue("defaultLocale").jsonPrimitive.content)

        val catalogs = root.getValue("catalogs").jsonObject.mapValues { (_, cat) ->
            val obj = cat.jsonObject
            MessageCatalog(
                messages = obj.getValue("messages").jsonObject.mapValues { it.value.jsonPrimitive.content },
                plurals = obj.getValue("plurals").jsonObject.mapValues { (_, forms) ->
                    forms.jsonObject.entries.associate { (category, text) ->
                        PluralCategory.valueOf(category) to text.jsonPrimitive.content
                    }
                },
            )
        }

        val failures = mutableListOf<String>()
        for (case in root.getValue("cases").jsonArray) {
            val obj = case.jsonObject
            val localeTag = obj.getValue("locale").jsonPrimitive.content
            val key = obj.getValue("key").jsonPrimitive.content
            val expected = obj.getValue("expected").jsonPrimitive.content
            val count = obj["count"]?.jsonPrimitive?.intOrNull
            val args: Map<String, Any?> = obj["args"]?.jsonObject?.mapValues { (_, v) ->
                when (v) {
                    is JsonNull -> null
                    is JsonPrimitive -> v.intOrNull ?: v.content
                    else -> v.toString()
                }
            } ?: emptyMap()

            // Mirror the client's world: one merged catalog, registered under the case's exact
            // tag (the endpoint pre-merges the chain before the browser ever sees it).
            val source = MessageSource.of(
                defaultLocale = defaultLocale,
                catalogs = mapOf(localeTag to catalogs.getValue(localeTag)),
            )
            val locale = Locale.forLanguageTag(localeTag)
            val actual = runBlocking {
                if (count != null) {
                    Messages.resolvePlural(source, locale, key, count.toLong(), args)
                } else {
                    Messages.resolve(source, locale, key, args)
                }
            }
            if (actual != expected) failures += "$localeTag $key count=$count: expected '$expected', got '$actual'"
        }
        assertEquals(emptyList(), failures)
    }
}
