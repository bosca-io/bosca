package bosca.cli.backup

import bosca.cli.BoscaCommand
import bosca.cli.config.AuthConfig
import bosca.cli.config.CliConfig
import bosca.cli.config.CliConfigStore
import bosca.cli.config.CliInvocation
import bosca.cli.config.ProfileConfig
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Destination-only restore preparation through the real CLI, typed codecs and local HTTP. */
class BackupObjectsPrepareRestoreCommandTest {
    private lateinit var root: Path
    private lateinit var server: HttpServer
    private val requests = mutableListOf<JsonObject>()
    private val auth = mutableListOf<String?>()
    private var response = "{}"
    private var statusCode = 200
    private val endpoint get() = "http://127.0.0.1:${server.address.port}/graphql"
    private val pending = Json.parseToJsonElement("""
        {"key":"run-1","sourceId":"source","destinationId":"recovery","scope":"content",
         "operation":"RESTORE","path":"manifests/inventory.json","status":"PENDING","attempts":2,
         "error":null,"version":9007199254740993,"created":"2026-10-21T18:00:17Z","modified":"2026-10-21T18:01:19Z",
         "inventory":{"id":"inventory","sourceId":"source","scope":"content","created":"2026-10-21T18:00:17Z",
                      "version":1,"format":"bosca-object-backup"},"result":null}
    """.trimIndent()).jsonObject
    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("backup-actions-cli-")
        CliConfigStore.directoryOverride = root.resolve("config").toFile()
        CliConfigStore.workingDirectoryOverride = root.toFile()
        CliInvocation.selectProfile(null)
        requests.clear()
        auth.clear()
        statusCode = 200
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            requests += Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            auth += exchange.requestHeaders.getFirst("Authorization")
            val bytes = response.encodeToByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        CliConfigStore.save(CliConfig(activeProfile = "operations", profiles = mapOf(
            "operations" to ProfileConfig(endpoint = endpoint, auth = AuthConfig(token = "action-test-key")),
        )))
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        CliConfigStore.directoryOverride = null
        CliConfigStore.workingDirectoryOverride = null
        CliInvocation.selectProfile(null)
        root.toFile().deleteRecursively()
    }

    private fun cli() = BoscaCommand().subcommands(BackupCommand())

    private fun answer(field: String, value: JsonElement) {
        response = buildJsonObject {
            put("data", buildJsonObject { put("backups", buildJsonObject {
                put("objectRuns", buildJsonObject { put(field, value) })
            }) })
        }.toString()
    }

    private fun args(vararg extra: String): List<String> = listOf(
        "backup", "objects", "prepare-restore", "--request", "restore-1", "--destination", "recovery",
        "--backup", "retained", "--manifest", "manifests/inventory.json", "--store", "content=recovery-content",
    ) + extra

    @Test
    fun `preparation sends one authenticated typed mutation to the destination without dispatch or reads`() {
        answer("prepareRestoreConfigured", pending)
        val result = cli().test(args())
        assertEquals(0, result.statusCode, result.output)
        val request = requests.single()
        assertEquals("PrepareConfiguredObjectRestore", request.getValue("operationName").jsonPrimitive.content)
        assertTrue(request.getValue("query").jsonPrimitive.content.startsWith("mutation PrepareConfiguredObjectRestore"))
        assertEquals(Json.parseToJsonElement("""{"requestKey":"restore-1","destinationId":"recovery","backupKey":"retained",
            "path":"manifests/inventory.json","stores":[{"store":"content","storageKey":"recovery-content"}]}"""), request["variables"])
        assertEquals(listOf<String?>("Bearer action-test-key"), auth)
        for (text in listOf("Run: run-1", "Status: PENDING", "Version: 9007199254740993", "Dispatch separately")) {
            assertTrue(text in result.stdout, result.output)
        }
    }

    @Test
    fun `JSON preserves exact server run metadata and replay returns its historical outcome`() {
        val completed = JsonObject(pending + mapOf("status" to JsonPrimitive("COMPLETED"), "result" to Json.parseToJsonElement(
            """{"total":3,"pending":0,"running":0,"completed":3,"failed":0,"totalBytes":9007199254740995,"verifiedBytes":9007199254740995}""")))
        for (run in listOf(pending, completed)) {
            answer("prepareRestoreConfigured", run)
            val result = cli().test(args("--json"))
            assertEquals(0, result.statusCode, result.output)
            assertEquals(run, Json.parseToJsonElement(result.stdout))
            assertTrue("Request completion does not establish full recovery coverage" in result.stderr, result.output)
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun `repeat mappings preserve literal logical names and catalog IDs including equals and unicode`() {
        answer("prepareRestoreConfigured", pending)
        val result = cli().test(args("--store", "artifacts σ=target=σ/path"))
        assertEquals(0, result.statusCode, result.output)
        val stores = requests.single().getValue("variables").jsonObject.getValue("stores")
        assertEquals(Json.parseToJsonElement("""[{"store":"content","storageKey":"recovery-content"},
            {"store":"artifacts σ","storageKey":"target=σ/path"}]"""), stores)
    }

    @Test
    fun `empty manifests can be prepared with no target mappings`() {
        answer("prepareRestoreConfigured", pending)
        val result = cli().test(args().dropLast(2))
        assertEquals(0, result.statusCode, result.output)
        assertEquals(Json.parseToJsonElement("[]"), requests.single().getValue("variables").jsonObject["stores"])
    }

    @Test
    fun `all preparation identifiers and manifest path are required before transport`() {
        val pairs = args().drop(3).dropLast(2).chunked(2)
        for (omitted in pairs.indices) {
            val result = cli().test(listOf("backup", "objects", "prepare-restore") +
                pairs.filterIndexed { i, _ -> i != omitted }.flatten() + listOf("--store", "content=recovery-content"))
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `blank identifiers malformed stores and duplicate logical mappings fail before transport`() {
        for ((option, value) in listOf("--request" to " ", "--destination" to " ", "--backup" to " ", "--manifest" to " ")) {
            val arguments = args().toMutableList()
            arguments[arguments.indexOf(option) + 1] = value
            val result = cli().test(arguments)
            assertEquals(1, result.statusCode, result.output)
        }
        for (mapping in listOf("missing-separator", "=target", "content=", " =target", "new= ", "content=other")) {
            val result = cli().test(args("--store", mapping))
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `server validation authorization and changed-intent failures have no followup action`() {
        for (message in listOf("changed request", "missing manifest", "invalid targets", "permission denied")) {
            response = """{"data":null,"errors":[{"message":"$message"}]}"""
            val before = requests.size
            val result = cli().test(args("--json"))
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertTrue(message in result.stderr, result.output)
            assertEquals(before + 1, requests.size)
        }
    }

    @Test
    fun `partial data with GraphQL errors cannot report a successful preparation`() {
        answer("prepareRestoreConfigured", pending)
        response = JsonObject(Json.parseToJsonElement(response).jsonObject +
            ("errors" to Json.parseToJsonElement("""[{"message":"incomplete preparation"}]"""))).toString()
        val result = cli().test(args("--json"))
        assertEquals(1, result.statusCode, result.output)
        assertEquals("", result.stdout)
        assertTrue("incomplete preparation" in result.stderr, result.output)
        assertEquals(1, requests.size)
    }

    @Test
    fun `null malformed and unknown enum results fail loudly without successful output`() {
        for (value in listOf(JsonNull, JsonObject(pending - "key"), JsonObject(pending + ("status" to JsonPrimitive("NEW_STATE"))))) {
            answer("prepareRestoreConfigured", value)
            val result = cli().test(args("--json"))
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertFalse("Request completion" in result.stderr)
        }
        response = "not json"
        assertEquals(1, cli().test(args()).statusCode)
        assertEquals(4, requests.size)
    }

    @Test
    fun `HTTP denial reports failure and cannot print a run`() {
        for (status in listOf(401, 403, 500)) {
            statusCode = status
            response = "denied"
            val result = cli().test(args())
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertTrue(status.toString() in result.stderr, result.output)
        }
        assertEquals(3, requests.size)
    }
}
