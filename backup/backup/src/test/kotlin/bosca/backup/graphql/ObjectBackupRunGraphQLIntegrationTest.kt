package bosca.backup.graphql

import bosca.backup.BackupObject
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupManifestStore
import bosca.backup.ObjectBackupOperation
import bosca.backup.ObjectBackupProgressService
import bosca.backup.ObjectBackupRun
import bosca.backup.ObjectBackupRunService
import bosca.backup.configuration.BackupSchemaRegistrar
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.content.metadata.service.MetadataService
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.BackupTestProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.graphql.GraphQLRequest
import bosca.graphql.SchemaRegistrar
import bosca.graphql.SchemaRegistry
import bosca.graphql.dispatcher.DispatchersRegistry
import bosca.observability.ErrorCapture
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.AuthenticationProviders
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.service.FileSystemObjectStorageService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real schema/dispatcher/authorization integration over persisted object runs. */
@OptIn(InternalDI::class)
class ObjectBackupRunGraphQLIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var runs: ObjectBackupRunService
    private lateinit var progress: ObjectBackupProgressService
    private lateinit var graphql: ObjectBackupQueryTestService
    private lateinit var directory: Path
    private lateinit var source: FileSystemObjectStorageService
    private lateinit var destination: FileSystemObjectStorageService
    private lateinit var run: ObjectBackupRun
    private lateinit var json: Json
    private val first = entry("items/a", "data")
    private val second = entry("items/b", "more")
    private val inventory = ObjectBackupManifest(
        id = "inventory-1", sourceId = "source-1", scope = "content",
        created = "2026-10-16T00:00:00Z", objects = listOf(first, second),
    )
    private val adminGroup = Group(name = "administrators", description = "", type = GroupType.SYSTEM)
    private val admin = ImpersonatedAuthenticationContext(Principal(), listOf(adminGroup))
    private val anonymous = AuthenticationContext(null, null)

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_graphql").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-graphql-test",
        ))
        ProviderRegistry.clear()
        DispatchersRegistry.clear()
        val application = BoscaApplication(ApplicationConfig.load(
            "bosca:\n  server:\n    development: false\n".byteInputStream(),
        ))
        json = application.json
        provides<ConnectionPool>(singleton = true) { pool }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<ErrorCapture> { ErrorCapture.Noop }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("ObjectBackupRunGraphQLIntegrationTest") }
        provides<GroupEvaluator> { GroupEvaluator(mockk()) }
        provides<MetadataService> { mockk() }
        val objectStorage = mockk<ObjectStorageService>()
        provides<ObjectStorageService> { objectStorage }
        BackupProviderRegistrar().register()
        BackupTestProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        runs = provide()
        progress = provide()

        SchemaRegistry.initialize(object : SchemaRegistrar {
            override suspend fun load() = """
                scalar DateTime
                scalar JSON
                scalar Long
                scalar Upload
                scalar UUID
                type Query { test: Boolean }
                type Mutation { test: Boolean }
            """.trimIndent()
        }, BackupSchemaRegistrar())
        graphql = ObjectBackupQueryTestService()
        directory = createTempDirectory("bosca-object-graphql-")
        source = storage("source")
        destination = storage("destination")
        write(source, first.key, "data")
        write(source, second.key, "more")
        run = request { runs.prepare(inventory, "destination-1") }
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            DispatchersRegistry.clear()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `administrator reads all run fields and explicit nullable values`(): Unit = runBlocking {
        val value = result("run(key: \"${run.key}\") { $RUN_FIELDS }").getValue("run").jsonObject

        assertEquals(run.key, value.string("key"))
        assertEquals("source-1", value.string("sourceId"))
        assertEquals("destination-1", value.string("destinationId"))
        assertEquals("content", value.string("scope"))
        assertEquals("BACKUP", value.string("operation"))
        assertEquals("PENDING", value.string("status"))
        assertEquals(run.path, value.string("path"))
        assertEquals(0, value.getValue("attempts").jsonPrimitive.int)
        assertEquals(0L, value.getValue("version").jsonPrimitive.long)
        assertEquals(run.created.toInstant(), OffsetDateTime.parse(value.string("created")).toInstant())
        assertEquals(run.modified.toInstant(), OffsetDateTime.parse(value.string("modified")).toInstant())
        assertEquals(JsonNull, value.getValue("error"))
        assertEquals(JsonNull, value.getValue("result"))
    }

    @Test
    fun `inventory and paginated checkpoints expose their declared fields`(): Unit = runBlocking {
        val value = result("""
            run(key: "${run.key}") {
                inventory { id sourceId scope created version format objects(offset: 1, limit: 1) { $PAYLOAD_FIELDS } }
                items(offset: 1, limit: 1) { $PROGRESS_FIELDS payload { $PAYLOAD_FIELDS } }
            }
        """).getValue("run").jsonObject
        val manifest = value.getValue("inventory").jsonObject
        assertEquals(inventory.id, manifest.string("id"))
        assertEquals(inventory.sourceId, manifest.string("sourceId"))
        assertEquals(inventory.scope, manifest.string("scope"))
        assertEquals(inventory.created, manifest.string("created"))
        assertEquals(1, manifest.getValue("version").jsonPrimitive.int)
        assertEquals("bosca-object-backup", manifest.string("format"))
        assertPayload(second, manifest.getValue("objects").jsonArray.single().jsonObject)
        val item = value.getValue("items").jsonArray.single().jsonObject
        val expected = request { runs.items(run.key, 1, 1) }.single()
        assertEquals(expected.key, item.string("key"))
        assertEquals("source-1", item.string("sourceId"))
        assertEquals("destination-1", item.string("destinationId"))
        assertEquals("content", item.string("scope"))
        assertEquals("BACKUP", item.string("operation"))
        assertEquals("PENDING", item.string("status"))
        assertEquals(0, item.getValue("attempts").jsonPrimitive.int)
        assertEquals(0L, item.getValue("verifiedBytes").jsonPrimitive.long)
        assertEquals(0L, item.getValue("version").jsonPrimitive.long)
        assertEquals(JsonNull, item.getValue("error"))
        assertEquals(expected.created.toInstant(), OffsetDateTime.parse(item.string("created")).toInstant())
        assertEquals(expected.modified.toInstant(), OffsetDateTime.parse(item.string("modified")).toInstant())
        assertPayload(second, item.getValue("payload").jsonObject)
    }

    @Test
    fun `typed enum variables isolate backup and restore history and latest success`(): Unit = runBlocking {
        val completed = complete(run)
        val restoreInventory = inventory.copy(id = "restore-inventory")
        val path = StringObjectPath("recovery/input.json")
        ObjectBackupManifestStore(destination, json).save(path, restoreInventory)
        val restore = request { runs.prepareRestore(restoreInventory, "destination-1", path.toString()) }
        val restored = runs.executeRestore(restore.key, destination, mapOf("content" to storage("restored")))
        for ((operation, expected) in listOf("BACKUP" to completed, "RESTORE" to restored)) {
            val response = execute("""
                query Runs(${'$'}operation: ObjectBackupOperation!) {
                    backups { objectRuns {
                        list(operation: ${'$'}operation, sourceId: "source-1", destinationId: "destination-1", scope: "content") { key operation }
                        lastSuccessful(operation: ${'$'}operation, sourceId: "source-1", destinationId: "destination-1", scope: "content") { key operation }
                    } }
                }
            """, variables = buildJsonObject { put("operation", operation) })
            val values = data(response)
            assertEquals(expected.key, values.getValue("list").jsonArray.single().jsonObject.string("key"))
            assertEquals(operation, values.getValue("list").jsonArray.single().jsonObject.string("operation"))
            assertEquals(expected.key, values.getValue("lastSuccessful").jsonObject.string("key"))
        }
    }

    @Test
    fun `frozen result remains separate from live checkpoint health`(): Unit = runBlocking {
        complete(run)
        val item = request { runs.items(run.key, 0, 1) }.single()
        request { progress.invalidate(item.key, item.version, "later storage loss") }

        val value = result("""
            run(key: "${run.key}") {
                status result { $SUMMARY_FIELDS } summary { $SUMMARY_FIELDS }
                items { status error attempts verifiedBytes }
            }
        """)
            .getValue("run").jsonObject

        assertEquals("COMPLETED", value.string("status"))
        val frozen = value.getValue("result").jsonObject
        val live = value.getValue("summary").jsonObject
        assertEquals(listOf(2L, 0L, 0L, 2L, 0L, 8L, 8L), summaryValues(frozen))
        assertEquals(listOf(2L, 0L, 0L, 1L, 1L, 8L, 4L), summaryValues(live))
        val failed = value.getValue("items").jsonArray.first().jsonObject
        assertEquals("FAILED", failed.string("status"))
        assertEquals("later storage loss", failed.string("error"))
        assertEquals(1, failed.getValue("attempts").jsonPrimitive.int)
        assertEquals(0L, failed.getValue("verifiedBytes").jsonPrimitive.long)
    }

    @Test
    fun `newer partial attempt does not hide the last successful inventory`(): Unit = runBlocking {
        val successful = complete(run)
        val later = request { runs.prepare(inventory.copy(id = "later"), "destination-1") }
        val running = request { runs.start(later.key, later.version) }
        request { runs.fail(running.key, running.version, "manifest publication failed") }

        val value = result("""
            list($SCOPE) { key status error }
            lastSuccessful($SCOPE) { key status }
        """)

        assertEquals(later.key, value.getValue("list").jsonArray.first().jsonObject.string("key"))
        assertEquals("PARTIAL", value.getValue("list").jsonArray.first().jsonObject.string("status"))
        assertEquals("manifest publication failed", value.getValue("list").jsonArray.first().jsonObject.string("error"))
        assertEquals(successful.key, value.getValue("lastSuccessful").jsonObject.string("key"))
    }

    @Test
    fun `running failed and cancelled states retain their distinct outcomes`(): Unit = runBlocking {
        val running = request { runs.start(run.key, run.version) }
        val checkpoint = request { runs.items(run.key, 0, 1) }.single()
        request { progress.start(checkpoint.key, checkpoint.version) }
        val failedRun = request {
            runs.prepare(inventory.copy(id = "failed", objects = listOf(first.copy(revision = "failed-1"))), "destination-1")
        }
        val unavailable = mockk<ObjectStorageService>()
        coEvery { unavailable.getInputStream(any()) } throws IOException("source unavailable")
        kotlin.test.assertFailsWith<IOException> {
            runs.execute(failedRun.key, mapOf("content" to unavailable), destination)
        }
        val queued = request {
            runs.prepare(inventory.copy(id = "cancelled", objects = listOf(first.copy(revision = "cancelled-1"))), "destination-1")
        }
        val cancelled = request { runs.cancel(queued.key, queued.version, "operator cancelled") }

        val value = result("""
            active: run(key: "${running.key}") { status result { total } items { status } }
            failed: run(key: "${failedRun.key}") { status error result { failed } items { status error } }
            cancelled: run(key: "${cancelled.key}") { status error attempts result { pending } }
        """)

        val active = value.getValue("active").jsonObject
        assertEquals("RUNNING", active.string("status"))
        assertEquals(JsonNull, active.getValue("result"))
        assertEquals("RUNNING", active.getValue("items").jsonArray.first().jsonObject.string("status"))
        val failed = value.getValue("failed").jsonObject
        assertEquals("FAILED", failed.string("status"))
        assertEquals("source unavailable", failed.string("error"))
        assertEquals(1L, failed.getValue("result").jsonObject.getValue("failed").jsonPrimitive.long)
        assertEquals("FAILED", failed.getValue("items").jsonArray.single().jsonObject.string("status"))
        assertEquals("source unavailable", failed.getValue("items").jsonArray.single().jsonObject.string("error"))
        val stopped = value.getValue("cancelled").jsonObject
        assertEquals("CANCELLED", stopped.string("status"))
        assertEquals("operator cancelled", stopped.string("error"))
        assertEquals(0, stopped.getValue("attempts").jsonPrimitive.int)
        assertEquals(1L, stopped.getValue("result").jsonObject.getValue("pending").jsonPrimitive.long)
    }

    @Test
    fun `missing lookups return null and empty scopes return an empty list`(): Unit = runBlocking {
        val value = result("""
            run(key: "missing") { key }
            lastSuccessful($SCOPE) { key }
            list(operation: RESTORE, sourceId: "source-1", destinationId: "destination-1", scope: "content") { key }
        """)

        assertEquals(JsonNull, value.getValue("run"))
        assertEquals(JsonNull, value.getValue("lastSuccessful"))
        assertEquals(JsonArray(emptyList()), value.getValue("list"))
    }

    @Test
    fun `list defaults offset pagination and administration page cap are applied`(): Unit = runBlocking {
        repeat(104) { index -> request { runs.prepare(inventory.copy(id = "page-$index"), "destination-1") } }
        val defaults = result("list($SCOPE) { key }").getValue("list").jsonArray
        val capped = result("list($SCOPE, limit: 1000) { key }").getValue("list").jsonArray
        val page = result("list($SCOPE, offset: 1, limit: 2) { key }").getValue("list").jsonArray

        assertEquals(20, defaults.size)
        assertEquals(100, capped.size)
        assertEquals(defaults.drop(1).take(2), page)
        assertEquals(JsonArray(emptyList()), result("list($SCOPE, offset: 105) { key }").getValue("list"))
    }

    @Test
    fun `inventory and item pagination defaults and caps are consistent`(): Unit = runBlocking {
        val entries = List(105) { first.copy(key = "items/$it", revision = "revision-$it") }
        val large = request { runs.prepare(inventory.copy(id = "large", objects = entries), "destination-1") }
        val value = result("""
            run(key: "${large.key}") {
                defaults: items { key }
                capped: items(limit: 1000) { key }
                inventory {
                    defaults: objects { key }
                    capped: objects(limit: 1000) { key }
                    page: objects(offset: 2, limit: 1) { key }
                }
            }
        """).getValue("run").jsonObject

        assertEquals(20, value.getValue("defaults").jsonArray.size)
        assertEquals(100, value.getValue("capped").jsonArray.size)
        val manifest = value.getValue("inventory").jsonObject
        assertEquals(20, manifest.getValue("defaults").jsonArray.size)
        assertEquals(100, manifest.getValue("capped").jsonArray.size)
        assertEquals("items/2", manifest.getValue("page").jsonArray.single().jsonObject.string("key"))
    }

    @Test
    fun `invalid pagination produces errors instead of silently changing the request`(): Unit = runBlocking {
        for (selection in listOf(
            "list($SCOPE, offset: -1) { key }",
            "list($SCOPE, limit: 0) { key }",
            "run(key: \"${run.key}\") { items(offset: -1) { key } }",
            "run(key: \"${run.key}\") { items(limit: 0) { key } }",
            "run(key: \"${run.key}\") { inventory { objects(offset: -1) { key } } }",
            "run(key: \"${run.key}\") { inventory { objects(limit: 0) { key } } }",
        )) {
            val response = execute("{ backups { objectRuns { $selection } } }")
            assertTrue("errors" in response, selection)
            assertTrue(response.getValue("errors").jsonArray.all { it.jsonObject["path"] is JsonArray }, response.toString())
        }
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `anonymous and ordinary callers cannot read run state`(): Unit = runBlocking {
        for (authentication in listOf(anonymous, ImpersonatedAuthenticationContext(Principal(), emptyList()))) {
            val response = execute("{ backups { objectRuns { run(key: \"${run.key}\") { key } } } }", authentication)
            assertTrue("errors" in response)
            assertTrue(response.getValue("errors").toString().contains("Unauthorized access"))
            assertFalse(response.toString().contains(run.key))
        }
    }

    @Test
    fun `super administrator membership uses the existing backup authorization rule`(): Unit = runBlocking {
        val sa = ImpersonatedAuthenticationContext(
            Principal(), listOf(Group(name = "sa", description = "", type = GroupType.SYSTEM)),
        )
        val response = execute("{ backups { objectRuns { run(key: \"${run.key}\") { key } } } }", sa)
        assertEquals(run.key, data(response).getValue("run").jsonObject.string("key"))
    }

    @Test
    fun `API token scopes and group restrictions apply to object run reads`(): Unit = runBlocking {
        val insufficient = scoped(listOf("content:view"))
        val excludedGroups = scoped(listOf("security:manage"), emptySet())
        for (authentication in listOf(insufficient, excludedGroups)) {
            val response = execute("{ backups { objectRuns { list($SCOPE) { key } } } }", authentication)
            assertTrue(response.getValue("errors").toString().contains("Unauthorized access"))
        }
        val allowed = execute("{ backups { objectRuns { list($SCOPE) { key } } } }", scoped(listOf("security:manage")))
        assertEquals(run.key, data(allowed).getValue("list").jsonArray.single().jsonObject.string("key"))
    }

    @Test
    fun `queries do not update run progress or access object storage`(): Unit = runBlocking {
        val before = request { runs.items(run.key, 0, 10) }
        result("run(key: \"${run.key}\") { $RUN_FIELDS inventory { id objects { $PAYLOAD_FIELDS } } summary { $SUMMARY_FIELDS } items { $PROGRESS_FIELDS } }")

        assertEquals(run, request { runs.get(run.key) })
        assertEquals(before, request { runs.items(run.key, 0, 10) })
        val storage = provide<ObjectStorageService>()
        coVerify(exactly = 0) { storage.getInputStream(any()) }
        coVerify(exactly = 0) { storage.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `byte counts beyond GraphQL Int range use the Long scalar`(): Unit = runBlocking {
        val length = 5_000_000_000L
        val selected = first.copy(key = "items/large", revision = "large-revision", length = length)
        val large = request { runs.prepare(inventory.copy(id = "large-bytes", objects = listOf(selected)), "destination-1") }

        val value = result("run(key: \"${large.key}\") { summary { totalBytes verifiedBytes } items { payload { length } } }")
            .getValue("run").jsonObject

        assertEquals(length, value.getValue("summary").jsonObject.getValue("totalBytes").jsonPrimitive.long)
        assertEquals(0L, value.getValue("summary").jsonObject.getValue("verifiedBytes").jsonPrimitive.long)
        assertEquals(length, value.getValue("items").jsonArray.single().jsonObject.getValue("payload").jsonObject.getValue("length").jsonPrimitive.long)
    }

    @Test
    fun `existing ZIP query and mutation fields keep their schema defaults`(): Unit = runBlocking {
        val response = execute("""
            { query: __type(name: "Backups") { fields { name args { name defaultValue } } }
              mutation: __type(name: "BackupsMutation") { fields { name args { name defaultValue } } } }
        """)
        assertFalse("errors" in response, response.toString())
        val types = response.getValue("data").jsonObject
        val queries = types.getValue("query").jsonObject.getValue("fields").jsonArray.associateBy { it.jsonObject.string("name") }
        assertTrue("backup" in queries)
        assertTrue("objectRuns" in queries)
        val defaults = assertNotNull(queries["list"]).jsonObject.getValue("args").jsonArray.associate {
            it.jsonObject.string("name") to it.jsonObject.string("defaultValue")
        }
        assertEquals(mapOf("offset" to "0", "limit" to "20"), defaults)
        val mutations = types.getValue("mutation").jsonObject.getValue("fields").jsonArray.associateBy { it.jsonObject.string("name") }
        assertEquals(setOf("create", "importBackup", "restore", "delete"), mutations.keys)
        val create = mutations.getValue("create").jsonObject.getValue("args").jsonArray.single().jsonObject
        assertEquals("true", create.string("defaultValue"))
        val restoreArgs = mutations.getValue("restore").jsonObject.getValue("args").jsonArray.associateBy { it.jsonObject.string("name") }
        assertEquals("SKIP", restoreArgs.getValue("conflictStrategy").jsonObject.string("defaultValue"))
    }

    private suspend fun result(selection: String): JsonObject = data(execute("{ backups { objectRuns { $selection } } }"))

    private suspend fun execute(
        query: String,
        authentication: AuthenticationContext = admin,
        variables: JsonObject? = null,
    ): JsonObject = graphql.execute(authentication, GraphQLRequest(query = query, variables = variables)).jsonObject

    private fun data(response: JsonObject): JsonObject {
        assertFalse("errors" in response, response.toString())
        return response.getValue("data").jsonObject.getValue("backups").jsonObject.getValue("objectRuns").jsonObject
    }

    private suspend fun complete(value: ObjectBackupRun): ObjectBackupRun =
        runs.execute(value.key, mapOf("content" to source), destination)

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)

    private fun scoped(scopes: List<String>, allowedGroups: Set<bosca.serialization.UUID>? = null): AuthenticationContext {
        val principal = ScopedAuthenticatedPrincipal(Principal(), listOf(adminGroup), scopes, allowedGroups, credentialId = 1)
        return object : AuthenticationContext(null, null) {
            override fun principal(): AuthenticatedPrincipal = principal
        }
    }

    private fun storage(name: String): FileSystemObjectStorageService = FileSystemObjectStorageService(
        "", "", mockk(), directory.resolve(name).toString(), mockk(),
    )

    private suspend fun write(storage: ObjectStorageService, key: String, value: String) {
        val bytes = value.toByteArray()
        storage.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
    }

    private fun entry(key: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return BackupObject("content", key, "revision-1", bytes.size.toLong(), digest, "objects/$digest")
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun assertPayload(expected: BackupObject, value: JsonObject) {
        assertEquals(expected.store, value.string("store"))
        assertEquals(expected.key, value.string("key"))
        assertEquals(expected.revision, value.string("revision"))
        assertEquals(expected.length, value.getValue("length").jsonPrimitive.long)
        assertEquals(expected.sha256, value.string("sha256"))
        assertEquals(expected.payloadPath, value.string("payloadPath"))
    }

    private fun summaryValues(value: JsonObject): List<Long> =
        listOf("total", "pending", "running", "completed", "failed", "totalBytes", "verifiedBytes").map {
            value.getValue(it).jsonPrimitive.long
        }

    companion object {
        private const val SCOPE = "operation: BACKUP, sourceId: \"source-1\", destinationId: \"destination-1\", scope: \"content\""
        private const val RUN_FIELDS = "key sourceId destinationId scope operation status attempts version path error created modified result { total }"
        private const val PAYLOAD_FIELDS = "store key revision length sha256 payloadPath"
        private const val PROGRESS_FIELDS = "key sourceId destinationId scope operation status attempts version verifiedBytes error created modified"
        private const val SUMMARY_FIELDS = "total pending running completed failed totalBytes verifiedBytes"
    }
}
