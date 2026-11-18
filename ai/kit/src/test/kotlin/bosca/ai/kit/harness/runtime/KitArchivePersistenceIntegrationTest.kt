@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ai.kit.harness.runtime

import bosca.ai.kit.configuration.KitMigration
import bosca.ai.kit.harness.KitRuntimeArchive
import bosca.ai.kit.harness.KitRuntimeSession
import bosca.db.*
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.*
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

/** Real migrations, generated mappers and independent archive reservation transactions. */
class KitArchivePersistenceIntegrationTest {
    @Test fun `archive migration accounts for earlier generations without inventing object timestamps`(): Unit = runBlocking {
        val postgres = SharedPostgreSQLContainer().withReuse(false)
        postgres.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username,
            postgres.password, maxConnections = 2), key = "kit-archive-migration"))
        val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer()) } }
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }; provides<Json> { json }
        try {
            val current = KitMigration()
            FlywayMigration(pool).migrate(listOf(object : bosca.db.migrations.Migration {
                override val schema = "kit"
                override val resources = current.resources.takeWhile { it != "V8__kit_archive_retention.sql" }
            }))
            val session = UUID.random()
            val principal = UUID.random()
            withContext(Dispatchers.IO) {
                java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                    connection.prepareStatement("""insert into kit.runtime_session
                        (session_id,principal_id,configuration,authority,state,generation,idle_at,archive_key,archive_size)
                        values (?,?,'{"maxArchiveBytes":500}'::jsonb,'{}'::jsonb,'FAILED',3,now(),?,100)""").use {
                        it.setObject(1, java.util.UUID.fromString(session.toString()))
                        it.setObject(2, java.util.UUID.fromString(principal.toString()))
                        it.setString(3, "kit/runtime/$session/2.tar.gz")
                        assertEquals(1, it.executeUpdate())
                    }
                }
            }
            FlywayMigration(pool).migrate(listOf(current))
            withConnectionManager {
                val repository = KitRuntimeRepositoryImpl()
                val inventory = repository.archives(session)
                assertEquals(listOf(500L, 100L, 500L), inventory.map { it.bytes })
                assertEquals(listOf(1L, 2L, 3L), inventory.map { it.generation })
                assertTrue(inventory.all { it.created == null })
                assertEquals(1100L, repository.archiveBytes(principal))
                assertEquals(listOf(1L, 3L), repository.obsoleteArchives(session).map { it.generation })
                assertNull(repository.get(session)?.archiveFailure)
            }
        } finally {
            ProviderRegistry.clear(); pool.close(); postgres.stop()
        }
    }

    @Test fun `quota reservations include pending objects and cleanup never releases a current checkpoint`(): Unit = runBlocking {
        val postgres = SharedPostgreSQLContainer().withReuse(true)
        postgres.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username,
            postgres.password, maxConnections = 8), key = "kit-archive-reservations"))
        val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer()) } }
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }; provides<Json> { json }
        FlywayMigration(pool).migrate(listOf(KitMigration()))
        val repository = KitRuntimeRepositoryImpl()
        val principal = UUID.random()
        val sessions = (0..3).map { KitRuntimeSession(UUID.random(), if (it < 3) principal else UUID.random(),
            JsonObject(emptyMap()), JsonObject(emptyMap()), state = "STOPPING", generation = 1,
            dispatchId = UUID.random(), idleAt = OffsetDateTime.now()) }
        fun key(session: KitRuntimeSession, generation: Long = 1) = "kit/runtime/${session.sessionId}/$generation.tar.gz"
        suspend fun reserve(session: KitRuntimeSession, generation: Long, bytes: Long, limit: Long) = withConnectionManager { transaction {
            assertTrue(repository.lockAdmission())
            val current = assertNotNull(repository.lock(session.sessionId))
            val used = repository.archiveBytes(current.principalId)
            if (used > limit || bytes > limit - used) return@transaction false
            repository.reserveArchive(KitRuntimeArchive(key(session, generation), session.sessionId, generation, bytes)) == 1
        } }
        try {
            withConnectionManager { sessions.forEach { repository.create(it); assertEquals(1, repository.save(it)) } }
            val start = CompletableDeferred<Unit>()
            val attempts = sessions.take(3).map { s -> async(Dispatchers.IO) { start.await(); reserve(s, 1, 400, 800) } }
            start.complete(Unit)
            assertEquals(2, attempts.awaitAll().count { it }, "Concurrent sessions cannot exceed one principal's quota")
            assertTrue(reserve(sessions.last(), 1, 600, 800), "Another principal has independent capacity")
            val selected = withConnectionManager {
                assertEquals(800L, repository.archiveBytes(principal))
                assertEquals(600L, repository.archiveBytes(sessions.last().principalId))
                sessions.first { repository.archive(key(it)) != null }.also { s ->
                    val archive = assertNotNull(repository.archive(key(s)))
                    assertEquals(s.sessionId, archive.sessionId)
                    assertEquals(1L, archive.generation)
                    assertEquals(400L, archive.bytes)
                    assertNotNull(archive.created)
                    assertEquals(0, repository.reserveArchive(archive), "Retrying the same reservation never charges twice")
                    assertTrue(repository.obsoleteArchives(s.sessionId).isEmpty(), "A stopping generation retains its pending upload")
                    val current = assertNotNull(repository.get(s.sessionId))
                    assertEquals(1, repository.save(current.copy(archiveKey = archive.key)))
                    assertEquals(0, repository.deleteArchive(s.sessionId, archive.key), "Current checkpoints cannot release their reservation")
                }
            }
            assertTrue(reserve(selected, 2, 500, 1600))
            withConnectionManager {
                val current = assertNotNull(repository.get(selected.sessionId))
                assertEquals(1, repository.save(current.copy(generation = 2, state = "STOPPED", dispatchId = null, archiveKey = key(selected, 2))))
                assertEquals(listOf(key(selected)), repository.obsoleteArchives(selected.sessionId).map { it.key })
                assertEquals(1300L, repository.archiveBytes(principal), "Objects awaiting deletion retain their charge")
                assertEquals(1, repository.deleteArchive(selected.sessionId, key(selected)))
                assertEquals(900L, repository.archiveBytes(principal))
                assertEquals(0, repository.deleteArchive(selected.sessionId, key(selected, 2)))
            }
            assertTrue(reserve(selected, 3, 300, 1600))
            withConnectionManager {
                val current = assertNotNull(repository.get(selected.sessionId))
                assertEquals(1, repository.save(current.copy(state = "FAILED", generation = 3, dispatchId = null)))
                assertEquals(listOf(key(selected, 3)), repository.obsoleteArchives(selected.sessionId).map { it.key })
                assertTrue(repository.pending(100, 0, 0).any { it.sessionId == selected.sessionId }, "Failed generations with uncommitted objects remain collectible")
            }
        } finally {
            withConnectionManager { sessions.forEach { s -> repository.get(s.sessionId)?.let { current ->
                val owner = UUID.random()
                repository.save(current.copy(owner = owner, deletedAt = OffsetDateTime.now()))
                repository.deleteSession(s.sessionId, owner, current.epoch)
            } } }
            ProviderRegistry.clear(); pool.close(); postgres.stop()
        }
    }

    @Test fun `recovery claims healthy idle sessions less often and never for two replicas at once`(): Unit = runBlocking {
        val postgres = SharedPostgreSQLContainer().withReuse(true)
        postgres.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username,
            postgres.password, maxConnections = 8), key = "kit-recovery-claims"))
        val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer()) } }
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }; provides<Json> { json }
        FlywayMigration(pool).migrate(listOf(KitMigration()))
        val repository = KitRuntimeRepositoryImpl()
        fun session(state: String, idleAt: OffsetDateTime) = KitRuntimeSession(UUID.random(), UUID.random(),
            JsonObject(emptyMap()), JsonObject(emptyMap()), state = state, generation = 1, dispatchId = UUID.random(), idleAt = idleAt)
        val healthy = session("READY", OffsetDateTime.now().plusHours(1))
        val expired = session("READY", OffsetDateTime.now().minusMinutes(1))
        val stopping = session("STOPPING", OffsetDateTime.now().plusHours(1))
        val raced = listOf(session("READY", OffsetDateTime.now().plusHours(1)), session("STOPPING", OffsetDateTime.now().plusHours(1)))
        val sessions = listOf(healthy, expired, stopping)
        suspend fun claimed(urgent: Long, idle: Long, among: List<KitRuntimeSession> = sessions) = withConnectionManager {
            repository.pending(100, urgent, idle).map { it.sessionId }.filter { id -> among.any { it.sessionId == id } }
        }
        try {
            withConnectionManager { sessions.forEach { repository.create(it); assertEquals(1, repository.save(it)) } }
            assertEquals(sessions.map { it.sessionId }.toSet(), claimed(3600, 3600).toSet())
            assertEquals(emptyList(), claimed(3600, 3600), "A claimed session waits for its interval")
            assertEquals(setOf(expired.sessionId, stopping.sessionId), claimed(0, 3600).toSet(), "Pending work is claimed on the shorter interval")
            assertEquals(sessions.map { it.sessionId }.toSet(), claimed(0, 0).toSet())

            withConnectionManager { raced.forEach { repository.create(it); assertEquals(1, repository.save(it)) } }
            val start = CompletableDeferred<Unit>()
            val replicas = (0..3).map { async(Dispatchers.IO) { start.await(); claimed(3600, 3600, raced) } }
            start.complete(Unit)
            val claims = replicas.awaitAll().flatten()
            assertEquals(raced.map { it.sessionId }.toSet(), claims.toSet())
            assertEquals(claims.size, claims.toSet().size, "One replica claims each session")
        } finally {
            withConnectionManager { (sessions + raced).forEach { s -> repository.get(s.sessionId)?.let { current ->
                val owner = UUID.random()
                repository.save(current.copy(owner = owner, deletedAt = OffsetDateTime.now()))
                repository.deleteSession(s.sessionId, owner, current.epoch)
            } } }
            ProviderRegistry.clear(); pool.close(); postgres.stop()
        }
    }
}
