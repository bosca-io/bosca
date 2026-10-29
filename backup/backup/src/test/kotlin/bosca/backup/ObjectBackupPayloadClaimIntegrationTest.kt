package bosca.backup

import bosca.backup.graphql.ObjectBackupGraphQLTest
import bosca.db.connection
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Protects retained backup bytes across logical inventories using real persistence and storage. */
@OptIn(InternalDI::class)
class ObjectBackupPayloadClaimIntegrationTest : ObjectBackupGraphQLTest() {
    @Test
    fun `payloads cannot overwrite another snapshot manifest`(): Unit = runBlocking {
        val completed = complete(run)
        val original = destination.getString(StringObjectPath(completed.path))
        val changed = entry("items/changed", "evil").copy(payloadPath = completed.path)
        write(source, changed.key, "evil")
        val failure = runCatching {
            val later = request { runs.prepare(inventory.copy(id = "manifest-collision", objects = listOf(changed)), "destination-1") }
            runs.execute(later.key, mapOf("content" to source), destination)
        }.exceptionOrNull()

        assertEquals(original, destination.getString(StringObjectPath(completed.path)))
        assertIs<IllegalArgumentException>(failure)
    }

    @Test
    fun `a manifest cannot take a path already reserved for retained payload bytes`(): Unit = runBlocking {
        val declared = inventory.copy(id = "reserved-manifest", objects = emptyList())
        val key = objectBackupIdentity(ObjectBackupOperation.BACKUP, listOf(declared.sourceId, "destination-1", declared.scope, declared.id))
        val payload = first.copy(key = "items/manifest-path", payloadPath = "manifests/$key.json")
        request { progress.prepare(ObjectBackupOperation.BACKUP, "source-2", "destination-1", "other", payload) }

        assertFailsWith<IllegalArgumentException> { request { runs.prepare(declared, "destination-1") } }

        assertNull(request { runs.get(key) })
        assertEquals(1, claims(payload.payloadPath))
    }

    @Test
    fun `a later inventory cannot overwrite bytes required by a completed snapshot`(): Unit = runBlocking {
        val completed = complete(run)
        val changed = entry("items/changed", "evil").copy(payloadPath = first.payloadPath)
        write(source, changed.key, "evil")
        val later = inventory.copy(id = "changed", objects = listOf(changed))

        val failure = runCatching {
            val prepared = request { runs.prepare(later, "destination-1") }
            runs.execute(prepared.key, mapOf("content" to source), destination)
        }.exceptionOrNull()

        assertEquals("data", destination.getString(StringObjectPath(first.payloadPath)))
        assertIs<IllegalArgumentException>(failure)
        assertEquals(completed, request { runs.get(run.key) })
        val restored = storage("restored")
        provide<ObjectBackupRestoreService>().restore(
            StringObjectPath(completed.path), destination, mapOf("content" to restored), "recovered",
        )
        assertEquals("data", restored.getString(StringObjectPath(first.key)))
        assertEquals("more", restored.getString(StringObjectPath(second.key)))
    }

    @Test
    fun `conflicting retained claims fail across source scope store key and revision identities`(): Unit = runBlocking {
        val changed = first.copy(length = first.length + 1)
        for ((index, identity) in listOf(
            Triple("source-2", "content", changed),
            Triple("source-1", "other-scope", changed),
            Triple("source-1", "content", changed.copy(store = "media")),
            Triple("source-1", "content", changed.copy(key = "items/other")),
            Triple("source-1", "content", changed.copy(revision = "revision-2")),
        ).withIndex()) {
            val error = assertFailsWith<IllegalArgumentException> {
                request { progress.prepare(ObjectBackupOperation.BACKUP, identity.first, "destination-1", identity.second, identity.third) }
            }
            assertTrue(error.message.orEmpty().contains("retained payload"), "case $index: $error")
            assertNull(request {
                progress.get(ObjectBackupOperation.BACKUP, identity.first, "destination-1", identity.second, identity.third)
            })
        }
        assertEquals(run, request { runs.get(run.key) })
        assertEquals(listOf(first, second), request { runs.items(run.key, 0, 100) }.map { it.payload })
    }

    @Test
    fun `matching claims share retained bytes across sources and scopes without reading the new source`(): Unit = runBlocking {
        complete(run)
        val shared = first.copy(store = "media", key = "media/shared", revision = "shared-2")
        val declared = inventory.copy(id = "shared", sourceId = "source-2", scope = "other", objects = listOf(shared))
        val prepared = request { runs.prepare(declared, "destination-1") }
        val unavailable = mockk<ManagedObjectStorageService>()
        coEvery { unavailable.getInputStream(any()) } throws IllegalStateException("Source must not be read")

        val completed = runs.execute(prepared.key, mapOf("media" to unavailable), destination)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(1L, completed.result?.completed)
        coVerify(exactly = 0) { unavailable.getInputStream(any()) }
        assertEquals("data", destination.getString(StringObjectPath(first.payloadPath)))
        assertEquals(1, claims(first.payloadPath))
    }

    @Test
    fun `different destinations can retain different bytes at the same path`(): Unit = runBlocking {
        val changed = entry("items/new", "new").copy(payloadPath = first.payloadPath)
        val declared = inventory.copy(id = "other-destination", objects = listOf(changed))
        val prepared = request { runs.prepare(declared, "destination-2") }
        val other = storage("other-destination")
        write(source, changed.key, "new")

        assertEquals(ObjectBackupRunStatus.COMPLETED, runs.execute(prepared.key, mapOf("content" to source), other).status)
        assertEquals("new", other.getString(StringObjectPath(changed.payloadPath)))
        assertEquals(2, claims(first.payloadPath))
    }

    @Test
    fun `concurrent conflicting claims admit exactly one immutable payload identity`(): Unit = runBlocking {
        val candidates = listOf(
            first.copy(key = "concurrent/a", payloadPath = "objects/concurrent"),
            second.copy(key = "concurrent/b", payloadPath = "objects/concurrent"),
        )
        val attempts = coroutineScope {
            candidates.map { payload ->
                async {
                    runCatching {
                        request { progress.prepare(ObjectBackupOperation.BACKUP, "source-1", "destination-1", "concurrent", payload) }
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, attempts.count { it.isSuccess })
        assertIs<IllegalArgumentException>(attempts.single { it.isFailure }.exceptionOrNull())
        assertEquals(1, claims("objects/concurrent"))
        assertEquals(1, request { progress.list(ObjectBackupOperation.BACKUP, "source-1", "destination-1", "concurrent", 0, 10) }.size)
    }

    @Test
    fun `concurrent matching claims retain distinct checkpoints and a shared payload claim`(): Unit = runBlocking {
        val prepared = coroutineScope {
            listOf("a", "b").map { name ->
                async {
                    request {
                        progress.prepare(
                            ObjectBackupOperation.BACKUP, "source-$name", "destination-1", "concurrent",
                            first.copy(key = name, payloadPath = "objects/concurrent"),
                        )
                    }
                }
            }.awaitAll()
        }

        assertNotEquals(prepared[0].key, prepared[1].key)
        assertEquals(1, claims("objects/concurrent"))
    }

    @Test
    fun `a restarted service still rejects a different digest at a claimed path`(): Unit = runBlocking {
        val restarted = ObjectBackupProgressServiceImpl(provide())
        val changed = first.copy(key = "items/changed", sha256 = second.sha256)

        assertFailsWith<IllegalArgumentException> {
            request { restarted.prepare(ObjectBackupOperation.BACKUP, "new-source", "destination-1", "new-scope", changed) }
        }

        assertEquals(1, claims(first.payloadPath))
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `a conflicting inventory rolls back earlier claims checkpoints and run intent`(): Unit = runBlocking {
        val fresh = first.copy(key = "items/fresh", payloadPath = "objects/fresh")
        val conflict = second.copy(key = "items/conflict", payloadPath = first.payloadPath)
        val declared = inventory.copy(id = "rollback", objects = listOf(fresh, conflict))

        assertFailsWith<IllegalArgumentException> { request { runs.prepare(declared, "destination-1") } }

        assertEquals(0, claims(fresh.payloadPath))
        assertNull(request { progress.get(ObjectBackupOperation.BACKUP, "source-1", "destination-1", "content", fresh) })
        assertEquals(listOf(run), request {
            runs.list(ObjectBackupOperation.BACKUP, "source-1", "destination-1", "content", 0, 100)
        })
        val different = fresh.copy(length = fresh.length + 1)
        assertNotNull(request { progress.prepare(ObjectBackupOperation.BACKUP, "source-2", "destination-1", "other", different) })
    }

    @Test
    fun `a failed checkpoint insert does not leave a reserved payload claim`(): Unit = runBlocking {
        request {
            connection().useStatement("""
                create function backup.reject_progress() returns trigger language plpgsql as ${'$'}${'$'}
                begin
                    if new.source_id = 'reject-source' then raise exception 'checkpoint insert failed'; end if;
                    return new;
                end ${'$'}${'$'};
                create trigger reject_progress before insert on backup.object_progress
                    for each row execute function backup.reject_progress();
            """.trimIndent()) { it.execute() }
        }
        val payload = first.copy(key = "items/rejected", payloadPath = "objects/rejected")

        assertFailsWith<Exception> {
            request { progress.prepare(ObjectBackupOperation.BACKUP, "reject-source", "destination-1", "content", payload) }
        }

        assertEquals(0, claims(payload.payloadPath))
        assertNull(request { progress.get(ObjectBackupOperation.BACKUP, "reject-source", "destination-1", "content", payload) })
        assertNotNull(request {
            progress.prepare(ObjectBackupOperation.BACKUP, "other-source", "destination-1", "other", payload.copy(length = 9))
        })
    }

    @Test
    fun `restore target paths do not create or conflict with retained backup claims`(): Unit = runBlocking {
        val changed = second.copy(key = first.payloadPath)
        val prepared = request {
            progress.prepare(ObjectBackupOperation.RESTORE, "source-2", "destination-1", "content", changed.copy(payloadPath = first.payloadPath))
        }

        assertEquals(ObjectBackupOperation.RESTORE, prepared.operation)
        assertEquals(1, claims(first.payloadPath))
        val restoreOnly = changed.copy(key = "items/restore-only", payloadPath = "objects/restore-only")
        request { progress.prepare(ObjectBackupOperation.RESTORE, "source-2", "destination-1", "content", restoreOnly) }
        assertEquals(0, claims(restoreOnly.payloadPath))
    }

    @Test
    fun `GraphQL preparation rejects conflicts against another retained inventory`(): Unit = runBlocking {
        val declared = inventory.copy(
            id = "conflicting-api", sourceId = "source-2",
            objects = listOf(first.copy(key = "items/other", sha256 = second.sha256)),
        )
        val response = execute("""
            mutation Prepare(${'$'}inventory: ObjectBackupManifestInput!) {
                backups { objectRuns { prepare(inventory: ${'$'}inventory, destinationId: "destination-1") { key } } }
            }
        """, variables = buildJsonObject {
            put("inventory", json.encodeToJsonElement(ObjectBackupManifest.serializer(), declared))
        })

        val errors = assertNotNull(response["errors"], response.toString()).jsonArray
        assertTrue(errors.any { it.jsonObject.string("message").contains("retained payload") })
        assertTrue(errors.all { it.jsonObject["path"] is JsonArray })
        assertEquals(run, request { runs.get(run.key) })
    }

    private suspend fun claims(path: String): Int = request {
        connection().useStatement("select count(*) from backup.object_paths where path = ?") { statement ->
            statement.setString(1, path)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }
}
