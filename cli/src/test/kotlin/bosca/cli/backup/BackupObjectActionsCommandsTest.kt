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

/** Versioned operator mutations through real CLI parsing, generated codecs and local HTTP. */
class BackupObjectActionsCommandsTest {
    private lateinit var root: Path
    private lateinit var server: HttpServer
    private val requests = mutableListOf<JsonObject>()
    private val auth = mutableListOf<String?>()
    private val actions = mapOf("retry" to "retry", "dispatch" to "dispatch", "cancel-pending" to "cancelPending")
    private val observedVersion = 9007199254740993L
    private var response = "{}"
    private var statusCode = 200
    private val endpoint get() = "http://127.0.0.1:${server.address.port}/graphql"
    private val pending = Json.parseToJsonElement("""
        {"key":"run-1","sourceId":"source","destinationId":"retained","scope":"content",
         "operation":"BACKUP","path":"manifests/inventory.json","status":"PENDING","attempts":2,
         "error":null,"version":9007199254740993,"created":"2026-10-21T18:00:17Z","modified":"2026-10-21T18:01:19Z",
         "inventory":{"id":"inventory","sourceId":"source","scope":"content","created":"2026-10-21T18:00:17Z",
                      "version":1,"format":"bosca-object-backup"},"result":null}
    """.trimIndent()).jsonObject
    private val dispatch = Json.parseToJsonElement("""
        {"key":"00000000-0000-0000-0000-000000000101","runKey":"run-1","version":9007199254740993,
         "created":"2026-10-21T18:01:19Z"}
    """.trimIndent()).jsonObject
    private val cancelled = JsonObject(pending + mapOf(
        "status" to JsonPrimitive("CANCELLED"), "error" to JsonPrimitive("operator cancelled"),
        "result" to Json.parseToJsonElement("""{"total":3,"pending":1,"running":0,"completed":1,"failed":1,
             "totalBytes":9007199254740995,"verifiedBytes":9007199254740993}"""),
    ))

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

    private fun args(command: String, vararg extra: String): List<String> = listOf(
        "backup", "objects", command, "--key", "run-1", "--expected-version", observedVersion.toString(),
    ) + (if (command == "cancel-pending") listOf("--reason", "operator cancelled") else emptyList()) + extra

    @Test
    fun `retry sends the observed version exactly once and returns the server pending version without dispatch`() {
        val retried = JsonObject(pending + ("version" to JsonPrimitive(9007199254740997L)))
        answer("retry", retried)
        val result = cli().test(args("retry"))

        assertEquals(0, result.statusCode, result.output)
        val request = requests.single()
        assertEquals("RetryObjectBackupRun", request.getValue("operationName").jsonPrimitive.content)
        assertTrue(request.getValue("query").jsonPrimitive.content.startsWith("mutation RetryObjectBackupRun"))
        assertEquals(Json.parseToJsonElement("""{"key":"run-1","expectedVersion":9007199254740993}"""), request["variables"])
        assertEquals(listOf<String?>("Bearer action-test-key"), auth)
        for (text in listOf("Run: run-1", "Status: PENDING", "Version: 9007199254740997", "Dispatch separately")) {
            assertTrue(text in result.stdout, result.output)
        }
    }

    @Test
    fun `retry JSON preserves BACKUP and RESTORE run intent and cleared outcome`() {
        for (operation in listOf("BACKUP", "RESTORE")) {
            val retried = JsonObject(pending + ("operation" to JsonPrimitive(operation)))
            answer("retry", retried)
            val result = cli().test(args("retry", "--json"))
            assertEquals(0, result.statusCode, result.output)
            assertEquals(retried, Json.parseToJsonElement(result.stdout))
            assertTrue("Request completion does not establish full recovery coverage" in result.stderr, result.output)
        }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.getValue("operationName").jsonPrimitive.content == "RetryObjectBackupRun" })
    }

    @Test
    fun `dispatch reports a durable request and asynchronous execution without reading or waiting for a run`() {
        answer("dispatch", dispatch)
        val result = cli().test(args("dispatch"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("DispatchObjectBackupRun", requests.single().getValue("operationName").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"key":"run-1","expectedVersion":9007199254740993}"""), requests.single()["variables"])
        for (text in listOf("Dispatch request: 00000000-0000-0000-0000-000000000101", "Run: run-1",
            "Version: 9007199254740993", "Execution is asynchronous")) assertTrue(text in result.stdout, result.output)
        assertFalse("COMPLETED" in result.stdout)
    }

    @Test
    fun `dispatch JSON retains UUID long version and timestamp`() {
        answer("dispatch", dispatch)
        val result = cli().test(args("dispatch", "--json"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(dispatch, Json.parseToJsonElement(result.stdout))
        assertEquals(1, requests.size)
    }

    @Test
    fun `pending cancellation forwards an exact reason and observed version without a force action`() {
        val reason = "move to another destination\nkeep \"quoted\" evidence σ"
        val outcome = JsonObject(cancelled + ("error" to JsonPrimitive(reason)))
        answer("cancelPending", outcome)
        val result = cli().test(listOf("backup", "objects", "cancel-pending", "--key", "run-1",
            "--expected-version", observedVersion.toString(), "--reason", reason))

        assertEquals(0, result.statusCode, result.output)
        val request = requests.single()
        assertEquals("CancelPendingObjectBackupRun", request.getValue("operationName").jsonPrimitive.content)
        assertEquals(buildJsonObject { put("key", "run-1");put("expectedVersion", observedVersion);put("reason", reason) }, request["variables"])
        for (text in listOf("Run: run-1", "Status: CANCELLED", "Reason: $reason")) assertTrue(text in result.stdout, result.output)
    }

    @Test
    fun `pending cancellation JSON preserves the frozen checkpoint outcome`() {
        answer("cancelPending", cancelled)
        val result = cli().test(args("cancel-pending", "--json"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(cancelled, Json.parseToJsonElement(result.stdout))
        assertEquals(1, requests.size)
    }

    @Test
    fun `stale missing active and terminal state errors never fetch a version or issue a followup action`() {
        for (command in actions.keys) {
            for (message in listOf("stale observed version", "run is missing", "run is already running", "terminal state is not retryable")) {
                val before = requests.size
                response = """{"data":null,"errors":[{"message":"$message"}]}"""
                val result = cli().test(args(command, "--json"))
                assertEquals(1, result.statusCode, result.output)
                assertEquals("", result.stdout)
                assertTrue(message in result.stderr, result.output)
                assertEquals(before + 1, requests.size)
                assertEquals(observedVersion.toString(), requests.last().getValue("variables").jsonObject.getValue("expectedVersion").jsonPrimitive.content)
                assertTrue(requests.last().getValue("query").jsonPrimitive.content.startsWith("mutation "))
            }
        }
    }

    @Test
    fun `partial data errors and null mutation results fail without printing success`() {
        for ((command, field) in actions) {
            val value = when (command) { "dispatch" -> dispatch; "retry" -> pending; else -> cancelled }
            answer(field, value)
            val data = Json.parseToJsonElement(response).jsonObject.getValue("data")
            response = """{"data":$data,"errors":[{"message":"publication failed after commit"}]}"""
            val partial = cli().test(args(command, "--json"))
            assertEquals(1, partial.statusCode, partial.output)
            assertEquals("", partial.stdout)
            assertTrue("publication failed after commit" in partial.stderr, partial.output)
            answer(field, JsonNull)
            val missing = cli().test(args(command, "--json"))
            assertEquals(1, missing.statusCode, missing.output)
            assertEquals("", missing.stdout)
        }
        assertEquals(6, requests.size)
    }

    @Test
    fun `malformed UUID enum and required fields fail through the typed response codecs`() {
        answer("dispatch", JsonObject(dispatch + ("key" to JsonPrimitive("not-a-uuid"))))
        val badId = cli().test(args("dispatch", "--json"))
        assertEquals(1, badId.statusCode, badId.output)
        assertEquals("", badId.stdout)
        answer("retry", JsonObject(pending + ("status" to JsonPrimitive("UNKNOWN"))))
        val badState = cli().test(args("retry", "--json"))
        assertEquals(1, badState.statusCode, badState.output)
        assertEquals("", badState.stdout)
        answer("cancelPending", JsonObject(cancelled - "version"))
        val noVersion = cli().test(args("cancel-pending", "--json"))
        assertEquals(1, noVersion.statusCode, noVersion.output)
        assertEquals("", noVersion.stdout)
        assertEquals(3, requests.size)
    }

    @Test
    fun `HTTP failures remain nonzero with one explicit-token request per action`() {
        for (command in actions.keys) {
            for ((status, message) in listOf(401 to "Authentication failed", 403 to "Permission denied", 503 to "HTTP 503")) {
                statusCode = status
                response = "unavailable"
                val before = requests.size
                val result = cli().test(args(command, "--json", "--token", "explicit-test-key"))
                assertEquals(1, result.statusCode, result.output)
                assertEquals("", result.stdout)
                assertTrue(message in result.stderr, result.output)
                assertEquals(before + 1, requests.size)
            }
        }
    }

    @Test
    fun `all actions require a nonblank key and explicit nonnegative Long version before network access`() {
        for (command in actions.keys) {
            val valid = args(command)
            val missingVersion = valid.toMutableList().apply {
                val index = indexOf("--expected-version");removeAt(index);removeAt(index)
            }
            val invalid = listOf(missingVersion) + listOf("-1", "9223372036854775808", "not-a-version").map { bad ->
                valid.toMutableList().apply { set(indexOf("--expected-version") + 1, bad) }
            } + listOf(valid.toMutableList().apply { set(indexOf("--key") + 1, " ") })
            for (input in invalid) {
                val result = cli().test(input)
                assertEquals(1, result.statusCode, result.output)
                assertTrue(requests.isEmpty(), result.output)
            }
        }
        for (reason in listOf(null, " ")) {
            val input = listOf("backup", "objects", "cancel-pending", "--key", "run-1", "--expected-version", "0") +
                (reason?.let { listOf("--reason", it) } ?: emptyList())
            val result = cli().test(input)
            assertEquals(1, result.statusCode, result.output)
            assertTrue(requests.isEmpty(), result.output)
        }
    }

    @Test
    fun `zero and maximum Long observed versions are transmitted exactly without arithmetic`() {
        for (version in listOf(0L, Long.MAX_VALUE)) {
            val receipt = JsonObject(dispatch + ("version" to JsonPrimitive(version)))
            answer("dispatch", receipt)
            val result = cli().test(listOf("backup", "objects", "dispatch", "--key", "run-1", "--expected-version", version.toString(), "--json"))
            assertEquals(0, result.statusCode, result.output)
            assertEquals(receipt, Json.parseToJsonElement(result.stdout))
            assertEquals(version.toString(), requests.last().getValue("variables").jsonObject.getValue("expectedVersion").jsonPrimitive.content)
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun `selected profiles and transient credentials are honored without editing saved credentials`() {
        CliConfigStore.save(CliConfig(activeProfile = "other", profiles = mapOf(
            "other" to ProfileConfig(endpoint = "http://127.0.0.1:1/graphql", auth = AuthConfig(token = "other-key")),
            "operations" to ProfileConfig(endpoint = endpoint, auth = AuthConfig(token = "action-test-key")),
        )))
        val before = Files.readString(root.resolve("config/config.json"))
        answer("retry", pending)
        val selected = cli().test(listOf("--profile", "operations") + args("retry", "--json"))
        assertEquals(0, selected.statusCode, selected.output)
        assertEquals("Bearer action-test-key", auth.last())
        val explicit = cli().test(listOf("--profile", "operations") + args("retry", "--url", endpoint, "--token", "transient-action-key"))
        assertEquals(0, explicit.statusCode, explicit.output)
        assertEquals("Bearer transient-action-key", auth.last())
        assertEquals(before, Files.readString(root.resolve("config/config.json")))
        assertFalse("transient-action-key" in explicit.output)
    }

    @Test
    fun `missing authentication and cross-endpoint credentials cannot mutate a run`() {
        for (command in actions.keys) {
            CliConfigStore.save(CliConfig(activeProfile = "operations", profiles = mapOf("operations" to ProfileConfig(endpoint = endpoint))))
            val missing = cli().test(args(command))
            assertEquals(1, missing.statusCode, missing.output)
            assertTrue("Not authenticated" in missing.stderr, missing.output)
            CliConfigStore.save(CliConfig(activeProfile = "operations", profiles = mapOf(
                "operations" to ProfileConfig(endpoint = "http://127.0.0.1:1/graphql", auth = AuthConfig(token = "other-key")),
            )))
            val retargeted = cli().test(args(command, "--url", endpoint))
            assertEquals(1, retargeted.statusCode, retargeted.output)
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `help exposes exact observed-version actions and pending-only cancellation without authentication`() {
        CliConfigStore.save(CliConfig())
        val group = cli().test("backup objects --help")
        assertEquals(0, group.statusCode, group.output)
        for (command in actions.keys) {
            assertTrue(command in group.stdout, group.output)
            val help = cli().test("backup objects $command --help")
            assertEquals(0, help.statusCode, help.output)
            for (option in listOf("--key", "--expected-version", "--json", "--url", "--token")) assertTrue(option in help.stdout, help.output)
            if (command == "cancel-pending") assertTrue("--reason" in help.stdout, help.output)
        }
        assertTrue(requests.isEmpty())
    }
}
