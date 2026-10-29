package bosca.backup.graphql

import bosca.backup.BackupObject
import bosca.backup.ObjectBackupManifestStore
import bosca.backup.ObjectBackupOperation
import bosca.backup.ObjectBackupProgressService
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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

/** Real schema/dispatcher/authorization integration over persisted object runs. */
@OptIn(InternalDI::class)
class ObjectBackupRunGraphQLIntegrationTest : ObjectBackupGraphQLTest() {
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
    fun `checkpoint verification age is exposed independently of upload and modification time`(): Unit = runBlocking {
        val pending = request { runs.items(run.key, 0, 100) }.first()
        val unverified = result("run(key: \"${run.key}\") { items { key verified } }")
            .getValue("run").jsonObject.getValue("items").jsonArray
        assertTrue(unverified.all { it.jsonObject.getValue("verified") == JsonNull })
        val checked = request {
            val progress = provide<ObjectBackupProgressService>()
            val active = progress.start(pending.key, pending.version)
            progress.completeVerified(active.key, active.version, ObjectStorageInfo(pending.payload.length, version = "retained-version"))
        }
        val values = result("run(key: \"${run.key}\") { items { key verified } }")
            .getValue("run").jsonObject.getValue("items").jsonArray
        val saved = values.single { it.jsonObject.string("key") == pending.key }.jsonObject
        assertEquals(assertNotNull(checked.verified).toInstant(), OffsetDateTime.parse(saved.string("verified")).toInstant())
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
        val unavailable = mockk<ManagedObjectStorageService>()
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
        val selected = first.copy(
            key = "items/large", revision = "large-revision", length = length, payloadPath = "objects/large",
        )
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
        assertEquals(setOf("create", "importBackup", "restore", "delete", "objectRuns", "recoverPostgresRun"), mutations.keys)
        val create = mutations.getValue("create").jsonObject.getValue("args").jsonArray.single().jsonObject
        assertEquals("true", create.string("defaultValue"))
        val restoreArgs = mutations.getValue("restore").jsonObject.getValue("args").jsonArray.associateBy { it.jsonObject.string("name") }
        assertEquals("SKIP", restoreArgs.getValue("conflictStrategy").jsonObject.string("defaultValue"))
    }

    private suspend fun result(selection: String): JsonObject = data(execute("{ backups { objectRuns { $selection } } }"))

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
