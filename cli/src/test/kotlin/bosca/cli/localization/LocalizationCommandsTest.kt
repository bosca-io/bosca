package bosca.cli.localization

import bosca.cli.config.CliConfigStore
import bosca.cli.config.CliInvocation
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalizationCommandsTest {

    private lateinit var server: HttpServer
    private lateinit var tempDir: File
    private val requests = mutableListOf<JsonObject>()
    private val projectId = "0e30b60e-2f9b-4a5e-9a4e-1c0000000001"
    private val stringId = "0e30b60e-2f9b-4a5e-9a4e-1c0000000002"

    @BeforeTest
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("localization-command-test").toFile()
        CliConfigStore.directoryOverride = File(tempDir, "config")
        CliInvocation.selectProfile(null)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(
                exchange.requestBody.readAllBytes().decodeToString(),
            ).jsonObject
            requests += request
            val variables = request["variables"]?.jsonObject.orEmpty()
            val response = when (request.getValue("operationName").jsonPrimitive.content) {
                "GetLocalizationStringByKey" ->
                    """{"data":{"localization":{"stringByKey":null}}}"""

                "AddLocalizationString" -> {
                    val key = variables.getValue("input").jsonObject.getValue("key").jsonPrimitive.content
                    """{"data":{"localization":{"addString":{"id":"$stringId","key":"$key"}}}}"""
                }

                "SetLocalizationTranslation" ->
                    """{"data":{"localization":{"setTranslation":{"id":"00000000-0000-0000-0000-000000000001"}}}}"""

                "ExportLocalization" ->
                    """{"data":{"localization":{"export":{"content":"{\"hello\":\"Hola\"}","contentType":"application/json","fileName":"translations.json"}}}}"""

                "GetLocalizationProgress" ->
                    """{"data":{"localization":{"project":{"id":"$projectId","name":"Website","progress":{"totalStrings":10,"translatedStrings":8,"approvedStrings":6,"publishedStrings":5,"aiGeneratedStrings":2,"humanTranslatedStrings":6,"percentage":80.0}}}}}"""

                "SyncLocalizationToProvider" ->
                    """{"data":{"localization":{"syncToProvider":{"stringsAdded":1,"stringsUpdated":2,"translationsAdded":3,"translationsUpdated":4,"errors":[]}}}}"""

                "SyncLocalizationFromProvider" ->
                    """{"data":{"localization":{"syncFromProvider":{"stringsAdded":5,"stringsUpdated":6,"translationsAdded":7,"translationsUpdated":8,"errors":["one warning"]}}}}"""

                else -> """{"errors":[{"message":"unexpected operation"}]}"""
            }
            val bytes = response.encodeToByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        CliInvocation.selectProfile(null)
        CliConfigStore.directoryOverride = null
        tempDir.deleteRecursively()
    }

    private val endpoint: String
        get() = "http://127.0.0.1:${server.address.port}/graphql"

    private fun commonArgs(): List<String> =
        listOf("--endpoint", endpoint, "--project", projectId)

    @Test
    fun `upload uses generated UUID and localization input types`() {
        val source = File(tempDir, "source.json").apply {
            writeText("""{"hello":"Hello"}""")
        }

        val result = UploadCommand().test(
            commonArgs() + listOf("--file", source.absolutePath, "--language", "en"),
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue("1 added, 0 updated" in result.output)
        assertEquals(
            listOf(
                "GetLocalizationStringByKey",
                "AddLocalizationString",
                "SetLocalizationTranslation",
            ),
            requests.map { it.getValue("operationName").jsonPrimitive.content },
        )
        val lookup = requests[0].getValue("variables").jsonObject
        assertEquals(projectId, lookup.getValue("projectId").jsonPrimitive.content)
        val addInput = requests[1].getValue("variables").jsonObject.getValue("input").jsonObject
        assertEquals(projectId, addInput.getValue("projectId").jsonPrimitive.content)
        val translation = requests[2].getValue("variables").jsonObject.getValue("input").jsonObject
        assertEquals(stringId, translation.getValue("stringId").jsonPrimitive.content)
        assertEquals("IMPORT", translation.getValue("origin").jsonPrimitive.content)
    }

    @Test
    fun `download uses generated export enums and writes the typed response`() {
        val output = File(tempDir, "output")

        val result = DownloadCommand().test(
            commonArgs() + listOf(
                "--language", "es",
                "--format", "JSON_FLAT",
                "--state", "APPROVED",
                "--output", output.absolutePath,
            ),
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals("""{"hello":"Hola"}""", File(output, "translations.json").readText())
        val input = requests.single()
            .getValue("variables").jsonObject
            .getValue("request").jsonObject
        assertEquals("JSON_FLAT", input.getValue("format").jsonPrimitive.content)
        assertEquals(
            listOf("APPROVED", "PUBLISHED"),
            input.getValue("statesFilter").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `status reads generated progress fields`() {
        val result = StatusCommand().test(
            commonArgs() + listOf("--language", "es"),
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue("Project: Website" in result.output)
        assertTrue("Translated:           8" in result.output)
        assertTrue("Percent complete:     80.0%" in result.output)
    }

    @Test
    fun `sync uses both generated result types`() {
        val result = SyncCommand().test(
            commonArgs() + listOf("--direction", "both"),
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(
            listOf("SyncLocalizationToProvider", "SyncLocalizationFromProvider"),
            requests.map { it.getValue("operationName").jsonPrimitive.content },
        )
        assertTrue("strings added:       1" in result.output)
        assertTrue("translations updated:8" in result.output)
        assertTrue("one warning" in result.output)
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
}
