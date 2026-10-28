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

/** Exact stopped-writer recovery through real CLI parsing, generated codecs and local HTTP. */
class BackupPostgresRecoverCommandTest {
    private lateinit var root: Path
    private lateinit var server: HttpServer
    private val requests = mutableListOf<JsonObject>()
    private val auth = mutableListOf<String?>()
    private var response = "{}"
    private var statusCode = 200
    private val endpoint get() = "http://127.0.0.1:${server.address.port}/graphql"
    private val recovered = Json.parseToJsonElement("""
        {"key":"native-1","sourceId":"source","storageKey":"retained","prefix":"snapshots",
         "status":"FAILED","attempts":2,"writerToken":null,"error":"Stopped PostgreSQL backup writer recovered",
         "version":9007199254740993,"created":"2026-10-21T18:00:17Z","modified":"2026-10-21T18:01:19Z","result":null}
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
                put(field, value)
            }) })
        }.toString()
    }

    private fun args(vararg extra: String): List<String> = listOf(
        "backup", "postgres", "recover", "--key", "native-1", "--expected-token", "observed-token",
    ) + extra

    @Test
    fun `recovery sends exactly the observed token and reports the saved failed request without another backup`() {
        answer("recoverPostgresRun", recovered)
        val result = cli().test(args())

        assertEquals(0, result.statusCode, result.output)
        val request = requests.single()
        assertEquals("RecoverPostgresBackupRun", request.getValue("operationName").jsonPrimitive.content)
        assertTrue(request.getValue("query").jsonPrimitive.content.startsWith("mutation RecoverPostgresBackupRun"))
        assertEquals(Json.parseToJsonElement("""{"key":"native-1","expectedToken":"observed-token"}"""), request["variables"])
        assertEquals(listOf<String?>("Bearer action-test-key"), auth)
        for (text in listOf("Request: native-1", "Status: FAILED", "Attempts: 2", "Version: 9007199254740993",
            "Stopped PostgreSQL backup writer recovered", "Recovery does not start another backup")) {
            assertTrue(text in result.stdout, result.output)
        }
    }

    @Test
    fun `JSON preserves recorded fixed intent cleared token error and exact long version`() {
        answer("recoverPostgresRun", recovered)
        val result = cli().test(args("--json"))
        assertEquals(0, result.statusCode, result.output)
        assertEquals(recovered, Json.parseToJsonElement(result.stdout))
        assertTrue("Request completion does not establish full recovery coverage" in result.stderr, result.output)
        assertEquals(1, requests.size)
    }

    @Test
    fun `opaque observed tokens retain spaces quotes newlines and unicode without client rewriting`() {
        val token = " observed:token=\"σ\"\nline "
        answer("recoverPostgresRun", recovered)
        val arguments = args().toMutableList()
        arguments[arguments.indexOf("--expected-token") + 1] = token
        val result = cli().test(arguments)
        assertEquals(0, result.statusCode, result.output)
        assertEquals(JsonPrimitive(token), requests.single().getValue("variables").jsonObject["expectedToken"])
    }

    @Test
    fun `missing and blank request keys or observed tokens fail before transport`() {
        val missingKey = args().toMutableList().apply { removeAt(3); removeAt(3) }
        val missingToken = args().dropLast(2)
        val blankKey = args().toMutableList().apply { this[indexOf("--key") + 1] = " " }
        val blankToken = args().toMutableList().apply { this[indexOf("--expected-token") + 1] = " " }
        for (input in listOf(missingKey, missingToken, blankKey, blankToken)) {
            val result = cli().test(input)
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `stale missing terminal and ownership errors never read or recover a newer token`() {
        for (message in listOf("observed execution token must match", "request is missing", "request is not running", "destination ownership is missing")) {
            response = """{"data":null,"errors":[{"message":"$message"}]}"""
            val before = requests.size
            val result = cli().test(args("--json"))
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertTrue(message in result.stderr, result.output)
            assertEquals(before + 1, requests.size)
            assertEquals(JsonPrimitive("observed-token"), requests.last().getValue("variables").jsonObject["expectedToken"])
        }
    }

    @Test
    fun `partial error envelopes cannot report a successful recovery`() {
        answer("recoverPostgresRun", recovered)
        response = JsonObject(Json.parseToJsonElement(response).jsonObject +
            ("errors" to Json.parseToJsonElement("""[{"message":"failed after recovery committed"}]"""))).toString()
        val result = cli().test(args("--json"))
        assertEquals(1, result.statusCode, result.output)
        assertEquals("", result.stdout)
        assertTrue("failed after recovery committed" in result.stderr, result.output)
        assertEquals(1, requests.size)
    }

    @Test
    fun `null malformed and unknown enum results fail without successful output`() {
        for (value in listOf(JsonNull, JsonObject(recovered - "key"), JsonObject(recovered + ("status" to JsonPrimitive("UNSUPPORTED"))))) {
            answer("recoverPostgresRun", value)
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
    fun `HTTP authentication permission and server failures preserve one explicit mutation`() {
        for (status in listOf(401, 403, 503)) {
            statusCode = status
            response = "unavailable"
            val result = cli().test(args("--token", "static-test-key"))
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertTrue(status.toString() in result.stderr, result.output)
        }
        assertEquals(3, requests.size)
    }

    @Test
    fun `selected profiles and transient credentials do not change saved configuration`() {
        answer("recoverPostgresRun", recovered)
        CliConfigStore.save(CliConfig(activeProfile = "other", profiles = mapOf(
            "other" to ProfileConfig(endpoint = "http://127.0.0.1:1/graphql", auth = AuthConfig(token = "other-key")),
            "recovery" to ProfileConfig(endpoint = endpoint, auth = AuthConfig(token = "recovery-key")),
        )))
        val before = Files.readString(root.resolve("config/config.json"))
        val selected = cli().test(listOf("--profile", "recovery") + args())
        assertEquals(0, selected.statusCode, selected.output)
        assertEquals("Bearer recovery-key", auth.last())
        val overridden = cli().test(args("--url", endpoint, "--token", "transient-test-key"))
        assertEquals(0, overridden.statusCode, overridden.output)
        assertEquals("Bearer transient-test-key", auth.last())
        assertEquals(before, Files.readString(root.resolve("config/config.json")))
        assertEquals(2, requests.size)
    }

    @Test
    fun `missing authentication and cross-endpoint credentials cannot send a recovery mutation`() {
        CliConfigStore.save(CliConfig(activeProfile = "none", profiles = mapOf("none" to ProfileConfig(endpoint = endpoint))))
        val missing = cli().test(args())
        assertEquals(1, missing.statusCode, missing.output)
        assertTrue("Not authenticated" in missing.stderr, missing.output)
        CliConfigStore.save(CliConfig(activeProfile = "other", profiles = mapOf(
            "other" to ProfileConfig(endpoint = "http://127.0.0.1:1/graphql", auth = AuthConfig(token = "other-key")),
        )))
        val isolated = cli().test(args("--url", endpoint))
        assertEquals(1, isolated.statusCode, isolated.output)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `help explains the stopped writer requirement without loading authentication`() {
        CliConfigStore.save(CliConfig())
        val result = cli().test("backup postgres recover --help")
        assertEquals(0, result.statusCode, result.output)
        assertTrue("--expected-token" in result.stdout, result.output)
        assertTrue("stopped" in result.stdout, result.output)
        assertTrue(requests.isEmpty())
    }
}
