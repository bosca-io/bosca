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
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

/** Real CLI parsing, profile selection, generated codecs and HTTP transport against a local upstream. */
class BackupPostgresCommandsTest {
    private lateinit var root: Path
    private lateinit var server: HttpServer
    private val requests = mutableListOf<JsonObject>()
    private val auth = mutableListOf<String?>()
    private var statusCode = 200
    private var response = "{}"
    private val endpoint get() = "http://127.0.0.1:${server.address.port}/graphql"
    private val notice = "Request completion does not establish full recovery coverage"
    private val run = Json.parseToJsonElement("""
        {
          "key":"nightly-1", "sourceId":"old-instance", "storageKey":"retained", "prefix":"snapshots",
          "status":"COMPLETED", "attempts":2, "writerToken":null, "error":null, "version":9007199254740993,
          "created":"2026-10-21T18:00:17Z", "modified":"2026-10-21T18:01:19Z",
          "result":{
            "key":"nightly-1", "storageKey":"retained", "path":"snapshots/databases/capture",
            "manifest":{
              "id":"capture", "sourceId":"old-instance", "database":"bosca",
              "created":"2026-10-21T18:00:17Z", "completed":"2026-10-21T18:01:19Z",
              "snapshotId":"exported", "snapshot":"1:2:", "serverVersion":"18.0",
              "serverVersionNumber":180000, "encoding":"UTF8", "extensions":{"vector":"0.8.1"},
              "length":9007199254740995, "sha256":"${"a".repeat(64)}", "payloadPath":"database.dump",
              "version":2, "format":"bosca-postgres-backup", "requiredKeys":{}
            }
          }
        }
    """.trimIndent()).jsonObject

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("backup-postgres-cli-")
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
        CliConfigStore.save(CliConfig(
            activeProfile = "local",
            profiles = mapOf("local" to ProfileConfig(endpoint = endpoint, auth = AuthConfig(token = "stored-api-key"))),
        ))
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

    private fun answer(field: String, value: kotlinx.serialization.json.JsonElement) {
        response = buildJsonObject {
            put("data", buildJsonObject { put("backups", buildJsonObject { put(field, value) }) })
        }.toString()
    }

    private fun listArgs(vararg extra: String) =
        listOf("backup", "postgres", "list", "--source", "old-instance", "--storage", "retained", *extra)

    private fun getArgs(vararg extra: String) =
        listOf("backup", "postgres", "get", "--key", "nightly-1", *extra)

    private fun withRequiredKeys(keys: JsonObject): JsonObject {
        val receipt = run.getValue("result").jsonObject
        val manifest = receipt.getValue("manifest").jsonObject
        val recordedManifest = JsonObject(manifest + mapOf("requiredKeys" to keys, "version" to JsonPrimitive(2)))
        val recordedReceipt = JsonObject(receipt + ("manifest" to recordedManifest))
        return JsonObject(run + ("result" to recordedReceipt))
    }

    @Test
    fun `get shows recorded key requirements without checking runtime keys or exposing authentication`() {
        val keys = Json.parseToJsonElement("""{
            "security.encryption.key":["public.configuration_values","kubernetes.cluster_credential"],
            "PIPELINE_SECRET_KEY":["pipelines.pipeline_secret","git.pipeline_secrets"],
            "custom.key":["custom.encrypted_存储"]
        }""").jsonObject
        answer("postgresRun", withRequiredKeys(keys))
        val result = cli().test(getArgs())

        assertEquals(0, result.statusCode, result.output)
        for (expected in listOf(
            "Recorded encryption key requirements:",
            "security.encryption.key: public.configuration_values, kubernetes.cluster_credential",
            "PIPELINE_SECRET_KEY: pipelines.pipeline_secret, git.pipeline_secrets",
            "custom.key: custom.encrypted_存储",
        )) assertTrue(expected in result.stdout, result.output)
        assertFalse("stored-api-key" in result.output)
        assertTrue(notice in result.stderr, result.output)
    }

    @Test
    fun `get and list JSON retain recorded key requirements and request the field`() {
        val item = withRequiredKeys(Json.parseToJsonElement("""{
            "security.encryption.key":["public.configuration_values"],"PIPELINE_SECRET_KEY":["pipelines.pipeline_secret"]
        }""").jsonObject)
        answer("postgresRun", item)
        val single = cli().test(getArgs("--json"))
        assertEquals(0, single.statusCode, single.output)
        assertEquals(item, Json.parseToJsonElement(single.stdout))
        assertTrue("requiredKeys" in requests.single().getValue("query").jsonPrimitive.content)
        answer("postgresRuns", JsonArray(listOf(item)))
        val listed = cli().test(listArgs("--json"))
        assertEquals(0, listed.statusCode, listed.output)
        assertEquals(JsonArray(listOf(item)), Json.parseToJsonElement(listed.stdout))
        assertTrue("requiredKeys" in requests.last().getValue("query").jsonPrimitive.content)
    }

    @Test
    fun `get distinguishes an empty recorded key inventory from verified decryption`() {
        answer("postgresRun", withRequiredKeys(JsonObject(emptyMap())))
        val result = cli().test(getArgs())
        assertEquals(0, result.statusCode, result.output)
        assertTrue("Recorded encryption key requirements:\n  None recorded." in result.stdout, result.output)
        assertTrue(notice in result.stderr, result.output)
    }

    @Test
    fun `list uses saved authentication and exact source storage pagination without writing remote state`() {
        answer("postgresRuns", JsonArray(listOf(run)))
        val result = cli().test(listArgs("--offset", "40", "--limit", "7"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf<String?>("Bearer stored-api-key"), auth)
        val sent = requests.single()
        assertEquals("ListPostgresBackupRuns", sent.getValue("operationName").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"sourceId":"old-instance","storageKey":"retained","offset":40,"limit":7}"""), sent["variables"])
        assertTrue(sent.getValue("query").jsonPrimitive.content.startsWith("query ListPostgresBackupRuns"))
        for (value in listOf("nightly-1", "COMPLETED", "2", "snapshots/databases/capture")) {
            assertTrue(value in result.stdout, result.output)
        }
        assertTrue(notice in result.stderr, result.output)
        assertFalse("stored-api-key" in result.output)
    }

    @Test
    fun `list defaults to one bounded page and emits the structured array with exact long values`() {
        answer("postgresRuns", JsonArray(listOf(run)))
        val result = cli().test(listArgs("--json"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(JsonArray(listOf(run)), Json.parseToJsonElement(result.stdout))
        val variables = requests.single().getValue("variables").jsonObject
        assertEquals("0", variables.getValue("offset").jsonPrimitive.content)
        assertEquals("20", variables.getValue("limit").jsonPrimitive.content)
        assertTrue(notice in result.stderr, result.output)
    }

    @Test
    fun `empty lists have a clear human result and a JSON array`() {
        answer("postgresRuns", JsonArray(emptyList()))
        val human = cli().test(listArgs())
        val json = cli().test(listArgs("--json"))

        assertEquals(0, human.statusCode, human.output)
        assertTrue("No native backup requests found" in human.stdout, human.output)
        assertEquals(0, json.statusCode, json.output)
        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(json.stdout))
        assertEquals(2, requests.size)
    }

    @Test
    fun `get reports the selected native artifact`() {
        answer("postgresRun", run)
        val result = cli().test(getArgs())

        assertEquals(0, result.statusCode, result.output)
        val request = requests.single()
        assertEquals("GetPostgresBackupRun", request.getValue("operationName").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"key":"nightly-1"}"""), request["variables"])
        for (value in listOf(
            "Request: nightly-1", "Source: old-instance", "Backup storage: retained", "Status: COMPLETED",
            "Attempts: 2", "Version: 9007199254740993",
            "Database artifact: snapshots/databases/capture",
            "Database: bosca", "Dump bytes: 9007199254740995",
        )) assertTrue(value in result.stdout, result.output)
        assertTrue(notice in result.stderr, result.output)
    }

    @Test
    fun `get exposes the exact observed writer token in human and JSON output`() {
        val item = JsonObject(run + mapOf(
            "status" to JsonPrimitive("RUNNING"), "result" to JsonNull,
            "writerToken" to JsonPrimitive("observed-token"),
        ))
        answer("postgresRun", item)
        val human = cli().test(getArgs())
        assertEquals(0, human.statusCode, human.output)
        assertTrue("Writer token: observed-token" in human.stdout, human.output)
        assertTrue("writerToken" in requests.single().getValue("query").jsonPrimitive.content)
        val json = cli().test(getArgs("--json"))
        assertEquals(0, json.statusCode, json.output)
        assertEquals(item, Json.parseToJsonElement(json.stdout))
        assertEquals(2, requests.size)
    }

    @Test
    fun `get JSON preserves the whole receipt including nullable fields native headers and extension data`() {
        answer("postgresRun", run)
        val result = cli().test(getArgs("--json"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(run, Json.parseToJsonElement(result.stdout))
        assertTrue(notice in result.stderr, result.output)
        assertFalse(notice in result.stdout)
    }

    @Test
    fun `unknown request is nonzero with null JSON or a clear human error`() {
        answer("postgresRun", JsonNull)
        val human = cli().test(getArgs())
        val json = cli().test(getArgs("--json"))

        assertEquals(1, human.statusCode, human.output)
        assertEquals("", human.stdout)
        assertTrue("Native backup request not found: nightly-1" in human.stderr, human.output)
        assertEquals(1, json.statusCode, json.output)
        assertEquals(JsonNull, Json.parseToJsonElement(json.stdout))
        assertTrue("Native backup request not found: nightly-1" in json.stderr, json.output)
    }

    @Test
    fun `explicit credentials override saved credentials and a selected profile stays selected for one invocation`() {
        answer("postgresRun", run)
        val before = Files.readString(root.resolve("config/config.json"))
        val explicit = cli().test(getArgs("--url", endpoint, "--token", "explicit-key"))
        assertEquals(0, explicit.statusCode, explicit.output)
        assertEquals("Bearer explicit-key", auth.last())
        assertEquals(before, Files.readString(root.resolve("config/config.json")))

        CliConfigStore.save(CliConfig(
            activeProfile = "wrong", profiles = mapOf(
                "wrong" to ProfileConfig(endpoint = "http://127.0.0.1:1/graphql", auth = AuthConfig(token = "wrong-key")),
                "recovery" to ProfileConfig(endpoint = endpoint, auth = AuthConfig(token = "recovery-key")),
            ),
        ))
        val result = cli().test(listOf("--profile", "recovery") + getArgs())
        assertEquals(0, result.statusCode, result.output)
        assertEquals("Bearer recovery-key", auth.last())
        assertEquals("wrong", CliConfigStore.load().activeProfile)
        assertFalse("recovery-key" in result.output)
    }

    @Test
    fun `missing authentication and credentials for another endpoint never send a backup query`() {
        CliConfigStore.save(CliConfig(activeProfile = "local", profiles = mapOf("local" to ProfileConfig(endpoint = endpoint))))
        val missing = cli().test(getArgs())
        assertEquals(1, missing.statusCode, missing.output)
        assertTrue("Not authenticated" in missing.stderr, missing.output)
        assertTrue(requests.isEmpty())

        CliConfigStore.save(CliConfig(activeProfile = "other", profiles = mapOf(
            "other" to ProfileConfig(endpoint = "http://127.0.0.1:1/graphql", auth = AuthConfig(token = "other-key")),
        )))
        val retargeted = cli().test(getArgs("--url", endpoint))
        assertEquals(1, retargeted.statusCode, retargeted.output)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `invalid pagination identities and incomplete credentials fail before any network request`() {
        val invalid = listOf(
            listArgs("--offset", "-1"), listArgs("--limit", "0"), listArgs("--limit", "101"),
            listArgs("--offset", "2147483648"),
            listOf("backup", "postgres", "list", "--source", " ", "--storage", "retained"),
            listOf("backup", "postgres", "list", "--source", "old-instance", "--storage", " "),
            listOf("backup", "postgres", "get", "--key", " "),
            getArgs("--username", "operator"),
        )
        for (args in invalid) {
            val result = cli().test(args)
            assertEquals(1, result.statusCode, result.output)
            assertTrue(requests.isEmpty(), result.output)
        }
    }

    @Test
    fun `GraphQL errors with partial data and malformed typed responses fail without emitting success data`() {
        val responses = listOf(
            """{"data":{"backups":{"postgresRun":$run}},"errors":[{"message":"backup administration access denied"}]}""",
            """{"data":null}""",
            """{"data":{"backups":{"postgresRun":{"key":"nightly-1"}}}}""",
            """{"data":{"backups":{"postgresRun":${JsonObject(run + ("status" to JsonPrimitive("UNSUPPORTED")))}}}}""",
        )
        for (body in responses) {
            response = body
            val result = cli().test(getArgs("--json", "--token", "explicit-key"))
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.isNotBlank(), result.output)
        }
        assertEquals(responses.size, requests.size)
    }

    @Test
    fun `HTTP authentication permission and server errors remain nonzero`() {
        for ((status, message) in listOf(401 to "Authentication failed", 403 to "Permission denied", 503 to "HTTP 503")) {
            statusCode = status
            response = "unavailable"
            val result = cli().test(getArgs("--json", "--token", "explicit-key"))
            assertEquals(1, result.statusCode, result.output)
            assertEquals("", result.stdout)
            assertTrue(message in result.stderr, result.output)
        }
        assertEquals(3, requests.size)
    }

    @Test
    fun `help exposes the nested commands and required scope without loading authentication`() {
        CliConfigStore.save(CliConfig())
        val group = cli().test("backup postgres --help")
        assertEquals(0, group.statusCode, group.output)
        assertTrue("list" in group.stdout && "get" in group.stdout, group.output)
        val list = cli().test("backup postgres list --help")
        assertEquals(0, list.statusCode, list.output)
        for (option in listOf("--source", "--storage", "--offset", "--limit", "--json", "--url", "--token")) {
            assertTrue(option in list.stdout, list.output)
        }
        val get = cli().test("backup postgres get --help")
        assertEquals(0, get.statusCode, get.output)
        assertTrue("--key" in get.stdout, get.output)
        assertTrue(requests.isEmpty())
    }
    @Test
    fun `every request status is returned as recorded including failed attempts without a receipt`() {
        for (state in listOf("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED")) {
            val item = JsonObject(run + mapOf(
                "status" to JsonPrimitive(state), "result" to JsonNull,
                "error" to JsonPrimitive("previous attempt interrupted"),
            ))
            answer("postgresRun", item)
            val human = cli().test(getArgs())
            assertEquals(0, human.statusCode, human.output)
            assertTrue("Status: $state" in human.stdout, human.output)
            assertTrue("Error: previous attempt interrupted" in human.stdout, human.output)
            assertTrue("Database artifact: -" in human.stdout, human.output)
            val json = cli().test(getArgs("--json"))
            assertEquals(0, json.statusCode, json.output)
            assertEquals(item, Json.parseToJsonElement(json.stdout))
        }
    }

}
