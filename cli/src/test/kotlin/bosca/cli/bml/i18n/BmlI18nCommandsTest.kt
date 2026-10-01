package bosca.cli.bml.i18n

import bosca.cli.config.CliConfigStore
import bosca.cli.config.CliInvocation
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `bosca bml i18n`: manifest parsing, the extract inventory, and push's
 * create + source-seed + update-report + orphan-report semantics against a scripted GraphQL
 * upstream that records every mutation.
 */
class BmlI18nCommandsTest {

    private lateinit var upstream: HttpServer
    private lateinit var configDir: File
    private var port = 0

    /** Keys the scripted project already contains: key -> (source text or null, forms). */
    private val existing = LinkedHashMap<String, Pair<String?, Map<String, String>>>()
    private val addedStrings = mutableListOf<String>()
    private val setTranslations = mutableListOf<Pair<String, String>>() // (stringId, text)
    private val setPluralRows = mutableListOf<Triple<String, String, String>>() // (stringId, category, text)
    private val deletedPluralRows = mutableListOf<Pair<String, String>>() // (stringId, languageTag)

    private val projectId = "0e30b60e-2f9b-4a5e-9a4e-1c0000000001"

    private fun stringId(key: String): String =
        java.util.UUID.nameUUIDFromBytes(key.encodeToByteArray()).toString()

    private fun manifest(dir: File, body: String): File =
        File(dir, "manifest.json").apply { writeText(body) }

    private val sampleManifest = """
        {
          "manifestVersion": 1,
          "strings": [
            {"key": "home.title", "plural": false, "origin": "ELEMENT", "file": "home.bml", "line": 3,
             "message": "Welcome to Acme", "placeholders": []},
            {"key": "cart.items", "plural": true, "origin": "ELEMENT", "file": "cart.bml", "line": 9,
             "forms": {"ONE": "You have one item", "OTHER": "You have {count} items"},
             "placeholders": [{"name": "count", "expression": "items.size"}]},
            {"key": "search.hint", "plural": false, "origin": "ATTRIBUTE", "file": "home.bml", "line": 12,
             "message": "Search here"},
            {"key": "nav.dynamic", "plural": false, "origin": "FUNCTION", "file": "home.bml", "line": 20}
          ]
        }
    """.trimIndent()

    @BeforeTest
    fun boot() {
        existing.clear(); addedStrings.clear(); setTranslations.clear(); setPluralRows.clear(); deletedPluralRows.clear()
        configDir = kotlin.io.path.createTempDirectory("bml-i18n-config-test").toFile()
        CliConfigStore.directoryOverride = configDir
        CliInvocation.selectProfile(null)
        port = ServerSocket(0).use { it.localPort }
        upstream = HttpServer.create(InetSocketAddress("localhost", port), 0)
        upstream.createContext("/graphql") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val request = Json.parseToJsonElement(body).jsonObject
            val query = request.getValue("query").jsonPrimitive.content
            val variables = request["variables"]?.jsonObject
            val response = when {
                "GetLocalizationProjectConfig" in query ->
                    """{"data":{"localization":{"project":{"sourceLanguage":"en","languages":[{"languageTag":"es"}]}}}}"""
                "GetLocalizationProjectStrings" in query -> {
                    val offset = variables?.get("variables")?.jsonPrimitive?.content?.toIntOrNull()
                        ?: variables?.get("offset")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val rows = if (offset > 0) "" else existing.entries.joinToString(",") { (key, value) ->
                        val (text, forms) = value
                        val translations = text?.let { """{"languageTag":"en","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(it))}}""" } ?: ""
                        val pluralRows = forms.entries.joinToString(",") {
                            """{"pluralCategory":"${it.key}","text":"${it.value}"}"""
                        }
                        """{"id":"${stringId(key)}","key":"$key","plural":${forms.isNotEmpty()},"translations":[$translations],"pluralTranslations":[$pluralRows]}"""
                    }
                    """{"data":{"localization":{"project":{"strings":[$rows]}}}}"""
                }
                "AddLocalizationString" in query -> {
                    val key = variables!!.getValue("input").jsonObject.getValue("key").jsonPrimitive.content
                    addedStrings += key
                    """{"data":{"localization":{"addString":{"id":"${stringId(key)}","key":"$key"}}}}"""
                }
                "SetLocalizationTranslation" in query -> {
                    val input = variables!!.getValue("input").jsonObject
                    setTranslations += input.getValue("stringId").jsonPrimitive.content to
                        input.getValue("text").jsonPrimitive.content
                    """{"data":{"localization":{"setTranslation":{"id":"00000000-0000-0000-0000-000000000001"}}}}"""
                }
                "SetLocalizationPluralTranslation" in query -> {
                    val input = variables!!.getValue("input").jsonObject
                    setPluralRows += Triple(
                        input.getValue("stringId").jsonPrimitive.content,
                        input.getValue("pluralCategory").jsonPrimitive.content,
                        input.getValue("text").jsonPrimitive.content,
                    )
                    """{"data":{"localization":{"setPluralTranslation":{"id":"00000000-0000-0000-0000-000000000002"}}}}"""
                }
                "DeleteLocalizationPluralTranslations" in query -> {
                    deletedPluralRows += variables!!.getValue("stringId").jsonPrimitive.content to
                        variables.getValue("languageTag").jsonPrimitive.content
                    """{"data":{"localization":{"deletePluralTranslations":true}}}"""
                }
                else -> """{"errors":[{"message":"unexpected operation in test: $query"}]}"""
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        upstream.start()
    }

    @AfterTest
    fun shutdown() {
        upstream.stop(0)
        CliInvocation.selectProfile(null)
        CliConfigStore.directoryOverride = null
        configDir.deleteRecursively()
    }

    private fun push(manifestFile: File) = I18nPushCommand().test(
        listOf(
            "--manifest", manifestFile.absolutePath,
            "--endpoint", "http://localhost:$port/graphql",
            "--project", projectId,
        ),
    )

    @Test
    fun `extract prints the inventory with shapes and origins`() {
        val dir = createTempDir()
        val result = I18nExtractCommand().test(listOf("--manifest", manifest(dir, sampleManifest).absolutePath))
        assertEquals(0, result.statusCode, result.output)
        assertTrue("home.title  [element/text]" in result.output, result.output)
        assertTrue("cart.items  [element/plural[ONE,OTHER]] {count}" in result.output, result.output)
        assertTrue("nav.dynamic  [function/key-only]" in result.output, result.output)
        assertTrue("4 key(s)" in result.output, result.output)
    }

    @Test
    fun `push creates missing keys and seeds source-language text including plural rows`() {
        val dir = createTempDir()
        val result = push(manifest(dir, sampleManifest))
        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("home.title", "cart.items", "search.hint", "nav.dynamic"), addedStrings)
        assertTrue(setTranslations.contains(stringId("home.title") to "Welcome to Acme"), "$setTranslations")
        assertTrue(setTranslations.contains(stringId("search.hint") to "Search here"), "$setTranslations")
        assertTrue(setPluralRows.contains(Triple(stringId("cart.items"), "ONE", "You have one item")), "$setPluralRows")
        assertTrue(setPluralRows.contains(Triple(stringId("cart.items"), "OTHER", "You have {count} items")), "$setPluralRows")
        // The bare t("key") call creates its key but has no authored text to seed.
        assertTrue(setTranslations.none { it.first == stringId("nav.dynamic") }, "$setTranslations")
        assertTrue("created 4 string(s)" in result.output, result.output)
    }

    @Test
    fun `push updates changed source text, reports it, and leaves matching keys alone`() {
        existing["home.title"] = "OLD title" to emptyMap()
        existing["search.hint"] = "Search here" to emptyMap()
        existing["cart.items"] = null to mapOf("ONE" to "You have one item", "OTHER" to "You have {count} items")
        val dir = createTempDir()
        val result = push(manifest(dir, sampleManifest))
        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("nav.dynamic"), addedStrings, "only the missing key is created")
        assertTrue(
            setTranslations.contains(stringId("home.title") to "Welcome to Acme"),
            "changed text re-seeds: $setTranslations",
        )
        assertTrue(
            setTranslations.none { it.first == stringId("search.hint") },
            "unchanged text is untouched: $setTranslations",
        )
        assertTrue(setPluralRows.isEmpty(), "matching plural forms are untouched: $setPluralRows")
        assertTrue("re-review their translations" in result.output && "home.title" in result.output, result.output)
    }

    @Test
    fun `push removes obsolete source plural categories before writing changed forms`() {
        existing["cart.items"] = null to mapOf(
            "ONE" to "You have one item",
            "OTHER" to "You have {count} items",
            "MANY" to "Obsolete many form",
        )
        val dir = createTempDir()

        val result = push(manifest(dir, sampleManifest))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf(stringId("cart.items") to "en"), deletedPluralRows)
        assertEquals(
            setOf("ONE", "OTHER"),
            setPluralRows.filter { it.first == stringId("cart.items") }.map { it.second }.toSet(),
        )
    }

    @Test
    fun `push reports orphans and never deletes them`() {
        existing["legacy.key"] = "Old copy" to emptyMap()
        val dir = createTempDir()
        val result = push(manifest(dir, sampleManifest))
        assertEquals(0, result.statusCode, result.output)
        assertTrue("orphaned key(s)" in result.output && "legacy.key" in result.output, result.output)
    }

    @Test
    fun `a missing manifest fails with build guidance`() {
        val result = I18nExtractCommand().test(listOf("--manifest", "/nonexistent/manifest.json"))
        assertTrue(result.statusCode != 0)
        assertTrue("build the project first" in result.output || "No i18n manifest" in result.output, result.output)
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("bml-i18n-test").toFile()
}
