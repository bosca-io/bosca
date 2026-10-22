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
import kotlinx.serialization.json.JsonArray
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

/** Real CLI, generated codecs and HTTP transport; no production instance or asset storage is used. */
class BackupObjectsCommandsTest {
    private lateinit var root: Path
    private lateinit var server: HttpServer
    private val requests = mutableListOf<JsonObject>()
    private val auth = mutableListOf<String?>()
    private var response = "{}"
    private var statusCode = 200
    private val endpoint get() = "http://127.0.0.1:${server.address.port}/graphql"
    private val recorded = Json.parseToJsonElement("""
        {"total":3,"pending":0,"running":0,"completed":3,"failed":0,
         "totalBytes":9007199254740995,"verifiedBytes":9007199254740995}
    """.trimIndent()).jsonObject
    private val current = JsonObject(recorded + mapOf(
        "pending" to JsonPrimitive(1), "completed" to JsonPrimitive(1), "failed" to JsonPrimitive(1),
        "verifiedBytes" to JsonPrimitive(9007199254740993L),
    ))
    private val listed = Json.parseToJsonElement("""
        {"key":"run-1","sourceId":"source","destinationId":"retained","scope":"content",
         "operation":"BACKUP","path":"manifests/inventory.json","status":"COMPLETED","attempts":2,
         "error":null,"version":9007199254740993,"created":"2026-10-21T18:00:17Z","modified":"2026-10-21T18:01:19Z",
         "inventory":{"id":"inventory","sourceId":"source","scope":"content","created":"2026-10-21T18:00:17Z",
                      "version":1,"format":"bosca-object-backup"},"result":$recorded}
    """.trimIndent()).jsonObject
    private val storage = Json.parseToJsonElement("""
        {"key":"run-1","backupKey":"vault","created":"2026-10-21T18:00:17Z",
         "stores":[{"store":"content","storageKey":"live-content"}]}
    """.trimIndent()).jsonObject
    private val detail = JsonObject(listed + mapOf("summary" to current, "storage" to storage))
    private val item = Json.parseToJsonElement("""
        {"key":"checkpoint-1","sourceId":"source","destinationId":"retained","scope":"content",
         "operation":"BACKUP","status":"COMPLETED","attempts":2,"verifiedBytes":9007199254740993,
         "verified":"2026-10-21T18:01:19Z","error":null,"version":9007199254740993,
         "created":"2026-10-21T18:00:17Z","modified":"2026-10-21T18:01:19Z",
         "payload":{"store":"content","key":"metadata/123/7/content","revision":"observed",
                    "length":9007199254740993,"sha256":"${"a".repeat(64)}","payloadPath":"assets/retained"}}
    """.trimIndent()).jsonObject

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("backup-objects-cli-")
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
        CliConfigStore.save(CliConfig(activeProfile = "recovery", profiles = mapOf(
            "recovery" to ProfileConfig(endpoint = endpoint, auth = AuthConfig(token = "object-test-key")),
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

    private fun listArgs(operation: String = "BACKUP", vararg extra: String) = listOf(
        "backup", "objects", "list", "--operation", operation, "--source", "source",
        "--destination", "retained", "--scope", "content", *extra,
    )

    private fun keyArgs(command: String = "get", vararg extra: String) =
        listOf("backup", "objects", command, "--key", "run-1", *extra)

    private fun items(values: List<JsonObject>) =
        JsonObject(mapOf("key" to JsonPrimitive("run-1"), "items" to JsonArray(values)))

    @Test
    fun `list authenticates and sends an exact operation source destination scope and page`() {
        for (operation in listOf("BACKUP", "restore")) {
            val row = JsonObject(listed + ("operation" to JsonPrimitive(operation.uppercase())))
            answer("runs", JsonArray(listOf(row)))
            val result = cli().test(listArgs(operation, "--offset", "40", "--limit", "7"))

            assertEquals(0, result.statusCode, result.output)
            assertEquals("Bearer object-test-key", auth.last())
            val request = requests.last()
            assertEquals("ListObjectBackupRuns", request.getValue("operationName").jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement("""{"operation":"${operation.uppercase()}","sourceId":"source",
                "destinationId":"retained","scope":"content","offset":40,"limit":7}"""), request["variables"])
            for (value in listOf("run-1", operation.uppercase(), "COMPLETED", "manifests/inventory.json")) {
                assertTrue(value in result.stdout, result.output)
            }
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun `list JSON preserves recorded totals and exact long values without loading checkpoint or object pages`() {
        answer("runs", JsonArray(listOf(listed)))
        val result = cli().test(listArgs("BACKUP", "--json"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(JsonArray(listOf(listed)), Json.parseToJsonElement(result.stdout))
        val request = requests.single()
        assertEquals("0", request.getValue("variables").jsonObject.getValue("offset").jsonPrimitive.content)
        assertEquals("20", request.getValue("variables").jsonObject.getValue("limit").jsonPrimitive.content)
        val query = request.getValue("query").jsonPrimitive.content
        assertFalse("items(" in query)
        assertFalse("objects(" in query)
        assertFalse("summary {" in query)
        assertTrue("Request completion does not establish full recovery coverage" in result.stderr, result.output)
    }

    @Test
    fun `empty run scopes are successful human and JSON reads`() {
        answer("runs", JsonArray(emptyList()))
        val human = cli().test(listArgs())
        val json = cli().test(listArgs("BACKUP", "--json"))

        assertEquals(0, human.statusCode, human.output)
        assertTrue("No object backup or restore runs found" in human.stdout, human.output)
        assertEquals(0, json.statusCode, json.output)
        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(json.stdout))
    }

    @Test
    fun `get distinguishes frozen last outcome from current shared checkpoint health`() {
        answer("run", detail)
        val result = cli().test(keyArgs())

        assertEquals(0, result.statusCode, result.output)
        assertEquals("GetObjectBackupRun", requests.single().getValue("operationName").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"key":"run-1"}"""), requests.single()["variables"])
        for (text in listOf(
            "Run: run-1", "Operation: BACKUP", "Status: COMPLETED", "Source: source", "Destination: retained",
            "Scope: content", "Inventory: inventory", "Manifest: manifests/inventory.json", "Version: 9007199254740993",
            "Backup storage: vault", "content: live-content",
            "Recorded outcome: total=3 pending=0 running=0 completed=3 failed=0",
            "Current checkpoints: total=3 pending=1 running=0 completed=1 failed=1",
            "Current verified bytes: 9007199254740993 / 9007199254740995",
        )) assertTrue(text in result.stdout, result.output)
    }

    @Test
    fun `get JSON preserves current summary separately from recorded result and bound storage`() {
        answer("run", detail)
        val result = cli().test(keyArgs("get", "--json"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(detail, Json.parseToJsonElement(result.stdout))
        val query = requests.single().getValue("query").jsonPrimitive.content
        assertFalse("items(" in query)
        assertFalse("objects(" in query)
    }

    @Test
    fun `all recorded run states remain typed and readable before a result or storage is recorded`() {
        for (state in listOf("PENDING", "RUNNING", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED")) {
            val row = JsonObject(detail + mapOf(
                "status" to JsonPrimitive(state), "result" to JsonNull, "storage" to JsonNull,
                "error" to JsonPrimitive("last attempt stopped"),
            ))
            answer("run", row)
            val human = cli().test(keyArgs())
            val json = cli().test(keyArgs("get", "--json"))

            assertEquals(0, human.statusCode, human.output)
            for (text in listOf("Status: $state", "Recorded outcome: -", "Backup storage: -", "Error: last attempt stopped")) {
                assertTrue(text in human.stdout, human.output)
            }
            assertEquals(0, json.statusCode, json.output)
            assertEquals(row, Json.parseToJsonElement(json.stdout))
        }
    }

    @Test
    fun `items preserve all checkpoint states original keys verification evidence and declared ordering`() {
        val states = listOf("FAILED", "PENDING", "COMPLETED", "RUNNING")
        val rows = states.mapIndexed { index, state ->
            val payload = item.getValue("payload").jsonObject
            JsonObject(item + mapOf(
                "key" to JsonPrimitive("checkpoint-$index"), "status" to JsonPrimitive(state),
                "verified" to if (state == "COMPLETED") item.getValue("verified") else JsonNull,
                "error" to if (state == "FAILED") JsonPrimitive("copy interrupted") else JsonNull,
                "payload" to JsonObject(payload + ("key" to JsonPrimitive("metadata/id/$index/content"))),
            ))
        }
        answer("run", items(rows))
        val human = cli().test(keyArgs("items", "--offset", "20", "--limit", "4"))
        val json = cli().test(keyArgs("items", "--offset", "20", "--limit", "4", "--json"))

        assertEquals(0, human.statusCode, human.output)
        assertTrue("copy interrupted" in human.stdout, human.output)
        var position = -1
        for (index in states.indices) {
            val next = human.stdout.indexOf("metadata/id/$index/content")
            assertTrue(next > position, human.output)
            position = next
            assertTrue(states[index] in human.stdout, human.output)
        }
        assertEquals(0, json.statusCode, json.output)
        assertEquals(JsonArray(rows), Json.parseToJsonElement(json.stdout))
        assertEquals(Json.parseToJsonElement("""{"key":"run-1","offset":20,"limit":4}"""), requests.last()["variables"])
    }

    @Test
    fun `restore item payload paths and operation are returned exactly as recorded by the existing service`() {
        val payload = item.getValue("payload").jsonObject
        val restore = JsonObject(item + mapOf(
            "operation" to JsonPrimitive("RESTORE"), "destinationId" to JsonPrimitive("recovery-content"),
            "payload" to JsonObject(payload + ("payloadPath" to payload.getValue("key"))),
        ))
        answer("run", items(listOf(restore)))
        val result = cli().test(keyArgs("items", "--json"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(JsonArray(listOf(restore)), Json.parseToJsonElement(result.stdout))
        assertEquals("GetObjectBackupRunItems", requests.single().getValue("operationName").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"key":"run-1","offset":0,"limit":20}"""), requests.single()["variables"])
    }

    @Test
    fun `empty checkpoint page is distinct from an unknown run`() {
        answer("run", items(emptyList()))
        val human = cli().test(keyArgs("items"))
        val json = cli().test(keyArgs("items", "--json"))
        assertEquals(0, human.statusCode, human.output)
        assertTrue("No checkpoints in this page" in human.stdout, human.output)
        assertEquals(0, json.statusCode, json.output)
        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(json.stdout))

        answer("run", JsonNull)
        for (command in listOf("get", "items")) {
            val missing = cli().test(keyArgs(command, "--json"))
            assertEquals(1, missing.statusCode, missing.output)
            assertEquals(JsonNull, Json.parseToJsonElement(missing.stdout))
            assertTrue("Object run not found: run-1" in missing.stderr, missing.output)
        }
    }

    @Test
    fun `malformed typed checkpoint state and run status fail without emitting success JSON`() {
        val bad = JsonObject(item + ("status" to JsonPrimitive("UNKNOWN")))
        answer("run", items(listOf(bad)))
        val invalidItem = cli().test(keyArgs("items", "--json"))
        assertEquals(1, invalidItem.statusCode, invalidItem.output)
        assertEquals("", invalidItem.stdout)

        answer("run", JsonObject(detail + ("status" to JsonPrimitive("UNKNOWN"))))
        val invalidRun = cli().test(keyArgs("get", "--json"))
        assertEquals(1, invalidRun.statusCode, invalidRun.output)
        assertEquals("", invalidRun.stdout)

        answer("run", JsonObject(detail - "summary"))
        val noSummary = cli().test(keyArgs("get", "--json"))
        assertEquals(1, noSummary.statusCode, noSummary.output)
        assertEquals("", noSummary.stdout)
        assertEquals(3, requests.size)
    }

    @Test
    fun `GraphQL partial data and HTTP errors stay nonzero for all three commands`() {
        val commands = listOf(listArgs("BACKUP", "--json"), keyArgs("get", "--json"), keyArgs("items", "--json"))
        for (args in commands) {
            response = """{"data":{"backups":{"objectRuns":{"run":$detail,"runs":[$listed]}}},"errors":[{"message":"backup administration denied"}]}"""
            val partial = cli().test(args)
            assertEquals(1, partial.statusCode, partial.output)
            assertEquals("", partial.stdout)
            assertTrue("backup administration denied" in partial.stderr, partial.output)
            statusCode = 403
            val forbidden = cli().test(args)
            assertEquals(1, forbidden.statusCode, forbidden.output)
            assertTrue("Permission denied" in forbidden.stderr, forbidden.output)
            statusCode = 200
        }
        assertEquals(6, requests.size)
    }

    @Test
    fun `scope operation pagination and key validation happen before any query`() {
        val invalid = listOf(
            listArgs("COPY"), listArgs("BACKUP", "--limit", "0"), listArgs("BACKUP", "--limit", "101"),
            listArgs("BACKUP", "--offset", "-1"), keyArgs("items", "--limit", "101"),
            keyArgs("items", "--offset", "-1"), keyArgs("items", "--offset", "2147483648"),
            listOf("backup", "objects", "get", "--key", " "),
            listOf("backup", "objects", "items", "--key", " "),
        ) + listOf("--source", "--destination", "--scope").map { flag ->
            val args = listArgs().toMutableList();args[args.indexOf(flag) + 1] = " ";args
        }
        for (args in invalid) {
            val result = cli().test(args)
            assertEquals(1, result.statusCode, result.output)
            assertTrue(requests.isEmpty(), result.output)
        }
    }

    @Test
    fun `queries use transient token overrides without changing saved configuration`() {
        answer("run", items(listOf(item)))
        val before = Files.readString(root.resolve("config/config.json"))
        val result = cli().test(keyArgs("items", "--url", endpoint, "--token", "explicit-object-key"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf<String?>("Bearer explicit-object-key"), auth)
        assertEquals(before, Files.readString(root.resolve("config/config.json")))
        assertFalse("explicit-object-key" in result.output)
    }

    @Test
    fun `missing authentication cannot send an object administration query`() {
        CliConfigStore.save(CliConfig(activeProfile = "recovery", profiles = mapOf(
            "recovery" to ProfileConfig(endpoint = endpoint),
        )))
        val result = cli().test(keyArgs())

        assertEquals(1, result.statusCode, result.output)
        assertTrue("Not authenticated" in result.stderr, result.output)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `help exposes operation scope and checkpoint pagination without authentication`() {
        CliConfigStore.save(CliConfig())
        val group = cli().test("backup objects --help")
        assertEquals(0, group.statusCode, group.output)
        for (name in listOf("list", "get", "items")) assertTrue(name in group.stdout, group.output)
        for ((command, options) in mapOf(
            "list" to listOf("--operation", "--source", "--destination", "--scope", "--offset", "--limit"),
            "get" to listOf("--key"), "items" to listOf("--key", "--offset", "--limit"),
        )) {
            val result = cli().test("backup objects $command --help")
            assertEquals(0, result.statusCode, result.output)
            for (option in options + listOf("--json", "--url", "--token")) assertTrue(option in result.stdout, result.output)
        }
        assertTrue(requests.isEmpty())
    }
}
