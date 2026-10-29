package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.serialization.OffsetDateTimeSerializer
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Immutable selected intent through registered PostgreSQL migrations and generated repositories. */
@OptIn(InternalDI::class)
class ObjectBackupCapturePlanIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var service: ObjectBackupCaptureService
    private val entry = ObjectStorageEntry("assets/a", ObjectStorageInfo(
        4, "\"opaque\"", "provider-v1", OffsetDateTime.parse("2026-10-18T00:00:00.123456789Z"),
    ))
    private val selected = ObjectBackupCaptureInventory(
        "selected", "source", "content", "2026-10-18T00:00:00Z",
        linkedMapOf("content" to listOf(entry.copy(key = "assets/b"), entry), "artifacts" to listOf(entry)),
    )

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_capture_plans").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3), key = "capture-plan-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) {
            Json { serializersModule = SerializersModule { contextual(OffsetDateTimeSerializer()) } }
        }
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        service = provide()
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `prepare persists canonical nested provider metadata without creating transfer progress`(): Unit = runBlocking {
        val plan = prepare()
        assertEquals("retained", plan.destinationId)
        assertEquals("payloads", plan.payloadPrefix)
        assertEquals("manifests/selected.json", plan.path)
        assertEquals(listOf("assets/a", "assets/b"), plan.inventory.stores.getValue("content").map { it.key })
        assertEquals(entry.info.modified, plan.inventory.stores.getValue("content").first().info.modified)
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
        assertTrue(withConnectionManager {
            provide<ObjectBackupProgressService>().list(ObjectBackupOperation.BACKUP, "source", "retained", "content", 0, 100)
        }.isEmpty())
    }

    @Test
    fun `matching replay with reordered selections returns the exact existing plan`(): Unit = runBlocking {
        val plan = prepare()
        val reordered = selected.copy(stores = selected.stores.entries.reversed().associate { it.key to it.value.reversed() })
        assertEquals(plan, prepare(reordered))
        assertEquals(listOf(plan), list())
    }

    @Test
    fun `changed selected revisions metadata or output intent cannot replace an existing plan`(): Unit = runBlocking {
        val plan = prepare()
        for (changed in listOf(
            selected.copy(created = "2026-10-19T00:00:00Z"), selected.copy(stores = emptyMap()),
            selected.copy(stores = mapOf("content" to listOf(entry.copy(info = ObjectStorageInfo(5, version = "provider-v2"))))),
        )) {
            assertFailsWith<IllegalArgumentException> { prepare(changed) }
        }
        assertFailsWith<IllegalArgumentException> { prepare(prefix = "other") }
        assertFailsWith<IllegalArgumentException> { prepare(path = "manifests/other.json") }
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
    }

    @Test
    fun `concurrent matching preparation records only one immutable plan`(): Unit = runBlocking {
        val plans = (1..3).map { async { prepare() } }.awaitAll()
        assertEquals(1, plans.toSet().size)
        assertEquals(plans.take(1), list())
    }

    @Test
    fun `caller rollback removes plan intent and matching replay does not escape it`(): Unit = runBlocking {
        var key: String? = null
        assertFailsWith<IOException> {
            withConnectionManager {
                transaction {
                    val plan = service.prepareInventory(selected, "retained", "payloads", "manifests/selected.json")
                    key = plan.key
                    assertEquals(plan, service.prepareInventory(selected, "retained", "payloads", "manifests/selected.json"))
                    throw IOException("caller rollback")
                }
            }
        }
        assertNull(withConnectionManager { service.getPlan(assertNotNull(key)) })
        assertTrue(list().isEmpty())
    }

    @Test
    fun `plan identity and listing isolate source destination scope and inventory identity`(): Unit = runBlocking {
        val primary = prepare()
        val newer = prepare(selected.copy(id = "other"))
        val source = prepare(selected.copy(sourceId = "other"))
        val destination = prepare(destination = "other")
        val scope = prepare(selected.copy(scope = "other"))
        assertEquals(5, listOf(primary, newer, source, destination, scope).map { it.key }.toSet().size)
        assertEquals(setOf(primary.key, newer.key), list().map { it.key }.toSet())
        assertEquals(listOf(source), list(source = "other"))
        assertEquals(listOf(destination), list(destination = "other"))
        assertEquals(listOf(scope), list(scope = "other"))
    }

    @Test
    fun `offset pages have deterministic ordering with equal creation times`(): Unit = runBlocking {
        val plans = withConnectionManager {
            transaction {
                (1..4).map { service.prepareInventory(selected.copy(id = "selection-$it"), "retained", "payloads", "manifests/$it.json") }
            }
        }
        val expected = plans.sortedWith(compareByDescending<ObjectBackupCapturePlan> { it.created }.thenBy { it.key })
        assertEquals(expected.take(2), list(offset = 0, limit = 2))
        assertEquals(expected.drop(2), list(offset = 2, limit = 2))
        assertTrue(list(offset = 4, limit = 2).isEmpty())
    }

    @Test
    fun `plan preparation snapshots caller collections before they can change`(): Unit = runBlocking {
        val entries = selected.stores.getValue("content").toMutableList()
        val stores = selected.stores.toMutableMap().apply { put("content", entries) }
        val plan = prepare(selected.copy(stores = stores))
        entries.clear()
        stores.clear()
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
        assertEquals(2, plan.inventory.stores.getValue("content").size)
    }

    @Test
    fun `restart reads persisted input rather than reselecting source entries`(): Unit = runBlocking {
        val plan = prepare()
        BackupProviderRegistrar().register()
        service = provide()
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
        assertEquals(listOf(plan), list())
    }

    @Test
    fun `invalid selections and output paths cannot create durable plans`(): Unit = runBlocking {
        for (invalid in listOf(selected.copy(id = " "), selected.copy(created = "yesterday"),
            selected.copy(stores = mapOf("content" to listOf(entry.copy(info = ObjectStorageInfo(4))))))) {
            assertFailsWith<IllegalArgumentException> { prepare(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { prepare(destination = " ") }
        for (invalid in listOf("", " ", "/absolute", "a/../b", "a//b")) {
            assertFailsWith<IllegalArgumentException> { prepare(prefix = invalid) }
            assertFailsWith<IllegalArgumentException> { prepare(path = invalid) }
        }
        assertFailsWith<IllegalArgumentException> { prepare(prefix = "same", path = "same") }
        assertFailsWith<IllegalArgumentException> { prepare(prefix = "same/children", path = "same") }
        assertTrue(list().isEmpty())
    }

    @Test
    fun `plan reads and offset pages reject invalid arguments without creating intent`(): Unit = runBlocking {
        assertNull(withConnectionManager { service.getPlan("unknown") })
        assertFailsWith<IllegalArgumentException> { withConnectionManager { service.getPlan(" ") } }
        assertFailsWith<IllegalArgumentException> { list(source = " ") }
        assertFailsWith<IllegalArgumentException> { list(destination = "") }
        assertFailsWith<IllegalArgumentException> { list(scope = "") }
        assertFailsWith<IllegalArgumentException> { list(offset = -1) }
        assertFailsWith<IllegalArgumentException> { list(limit = 0) }
        assertFailsWith<IllegalArgumentException> { list(limit = -1) }
        assertTrue(list().isEmpty())
    }

    private suspend fun prepare(
        inventory: ObjectBackupCaptureInventory = selected,
        destination: String = "retained",
        prefix: String = "payloads",
        path: String = "manifests/selected.json",
    ) = withConnectionManager { service.prepareInventory(inventory, destination, prefix, path) }

    private suspend fun list(
        source: String = "source",
        destination: String = "retained",
        scope: String = "content",
        offset: Long = 0,
        limit: Int = 100,
    ) = withConnectionManager { service.listPlans(source, destination, scope, offset, limit) }
}
