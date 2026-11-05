@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ai.kit.harness.runtime

import bosca.ai.kit.configuration.KitMigration
import bosca.ai.kit.harness.KitRuntimeSession
import bosca.ai.kit.harness.KitRuntimeTurn
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

/** Real generated repository mapping, row locking, CAS fencing and ordered durable turns. */
class KitRuntimePersistenceIntegrationTest {
    @Test
    fun `concurrent dispatch reservations obey global and principal capacity and rollback releases admission`(): Unit = runBlocking {
        val postgres = SharedPostgreSQLContainer().withReuse(true)
        postgres.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username,
            postgres.password, maxConnections = 8), key = "kit-admission-integration"))
        val json = Json { serializersModule = SerializersModule {
            contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
        } }
        val repository = KitRuntimeRepositoryImpl()
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }; provides<Json> { json }
        FlywayMigration(pool).migrate(listOf(KitMigration()))
        val principal = UUID.random()
        val sessions = (0 until 8).map { index -> KitRuntimeSession(UUID.random(), if (index < 4) principal else UUID.random(),
            buildJsonObject { put("profile", "profile-${index % 2}") }, JsonObject(emptyMap()), idleAt = OffsetDateTime.now()) }
        try {
            withConnectionManager { sessions.forEach { repository.create(it) } }
            val activeBaseline = withConnectionManager { repository.activeCount() }
            val failedBaseline = withConnectionManager { repository.failedCount() }
            suspend fun reserve(session: KitRuntimeSession, global: Int, perPrincipal: Int) = withConnectionManager { transaction {
                assertTrue(repository.lockAdmission())
                val locked = assertNotNull(repository.lock(session.sessionId))
                if (!repository.hasCapacity(locked.principalId, global, perPrincipal)) return@transaction false
                assertEquals(1, repository.save(locked.copy(state = "STARTING", dispatchId = UUID.random())))
                true
            } }
            val ready = CompletableDeferred<Unit>()
            val principalResults = sessions.take(4).map { session -> async(Dispatchers.IO) {
                ready.await(); reserve(session, 4, 1)
            } }
            ready.complete(Unit)
            assertEquals(1, principalResults.awaitAll().count { it }, "One principal cannot evade capacity through profiles or simultaneous starts")
            val globalResults = sessions.drop(4).map { session -> async(Dispatchers.IO) { reserve(session, 3, 1) } }.awaitAll()
            assertEquals(2, globalResults.count { it }, "Only the remaining global slots can commit")
            withConnectionManager {
                val active = sessions.mapNotNull { repository.get(it.sessionId) }.filter { it.dispatchId != null }
                assertEquals(3, active.size)
                assertEquals(activeBaseline + 3, repository.activeCount())
                val selected = active.first()
                for (state in listOf("STARTING", "READY", "BUSY", "WAITING_FOR_APPROVAL", "STOPPING")) {
                    val current = assertNotNull(repository.get(selected.sessionId))
                    assertEquals(1, repository.save(current.copy(state = state, deletedAt = OffsetDateTime.now())))
                    assertFalse(repository.hasCapacity(UUID.random(), 3, 1), "$state and deletion cleanup retain capacity")
                }
                val current = assertNotNull(repository.get(selected.sessionId))
                assertEquals(1, repository.save(current.copy(state = "FAILED")))
                assertTrue(repository.hasCapacity(UUID.random(), 3, 1), "Confirmed removal releases a failed generation")
                assertEquals(activeBaseline + 2, repository.activeCount())
                assertEquals(failedBaseline, repository.failedCount(), "Deleted failures are excluded")
                val failed = assertNotNull(repository.get(selected.sessionId))
                assertEquals(1, repository.save(failed.copy(deletedAt = null)))
                assertEquals(failedBaseline + 1, repository.failedCount())
            }
            val unused = withConnectionManager { sessions.mapNotNull { repository.get(it.sessionId) }.first { it.dispatchId == null } }
            withConnectionManager {
                assertFailsWith<IllegalStateException> { transaction {
                    assertTrue(repository.lockAdmission())
                    val current = assertNotNull(repository.lock(unused.sessionId))
                    assertTrue(repository.hasCapacity(current.principalId, 4, 4))
                    assertEquals(1, repository.save(current.copy(state = "STARTING", dispatchId = UUID.random())))
                    error("Injected dispatch transaction failure")
                } }
                assertNull(repository.get(unused.sessionId)?.dispatchId)
            }
            withTimeout(5000) { assertTrue(reserve(unused, 3, 4)) }
        } finally {
            withConnectionManager { sessions.forEach { session ->
                repository.get(session.sessionId)?.let { current ->
                    val owner = UUID.random()
                    repository.save(current.copy(owner = owner, deletedAt = OffsetDateTime.now()))
                    repository.deleteSession(current.sessionId, owner, current.epoch)
                }
            } }
            ProviderRegistry.clear(); pool.close(); postgres.stop()
        }
    }

    @Test
    fun `message and archive claim serialize and stale generation cannot overwrite committed archive`(): Unit = runBlocking {
        val postgres = SharedPostgreSQLContainer().withReuse(true)
        postgres.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username,
            postgres.password, maxConnections = 4), key = "kit-runtime-integration"))
        val json = Json { serializersModule = SerializersModule {
            contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
        } }
        val repository = KitRuntimeRepositoryImpl()
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }
        provides<Json> { json }
        FlywayMigration(pool).migrate(listOf(KitMigration()))
        val id = UUID.random()
        val firstTurn = UUID.random()
        val ready = KitRuntimeSession(id, UUID.random(), JsonObject(emptyMap()), JsonObject(emptyMap()),
            state = "READY", generation = 3, epoch = 4, nativeSessionId = "opaque-session",
            idleAt = OffsetDateTime.now().minusSeconds(30))
        try {
            withConnectionManager { repository.create(ready); assertEquals(1, repository.save(ready)) }
            val archivedClaim = CompletableDeferred<Unit>()
            val allowCommit = CompletableDeferred<Unit>()
            val archive = async(Dispatchers.IO) { withConnectionManager { transaction {
                val current = assertNotNull(repository.lock(id))
                assertNull(repository.next(id))
                assertEquals("opaque-session", current.nativeSessionId)
                archivedClaim.complete(Unit)
                allowCommit.await()
                assertEquals(1, repository.save(current.copy(state = "STOPPING")))
            } } }
            archivedClaim.await()
            val message = async(Dispatchers.IO) { withConnectionManager { transaction {
                val current = assertNotNull(repository.lock(id))
                assertEquals("STOPPING", current.state)
                repository.add(KitRuntimeTurn(firstTurn, id, message = buildJsonObject { put("text", "arrived during archive") }))
                assertEquals(1, repository.save(current.copy(idleAt = OffsetDateTime.now().plusMinutes(15))))
            } } }
            allowCommit.complete(Unit)
            archive.await(); message.await()
            withConnectionManager {
                val current = assertNotNull(repository.get(id))
                assertEquals("STOPPING", current.state)
                assertEquals(firstTurn, assertNotNull(repository.next(id)).id)
                assertEquals(0, repository.save(ready.copy(archiveKey = "stale-generation")))
                assertNull(repository.get(id)?.archiveKey)
                val second = UUID.random()
                repository.add(KitRuntimeTurn(second, id, message = JsonObject(emptyMap())))
                assertNull(repository.turn(firstTurn)?.deadline)
                val deadline = OffsetDateTime.now().plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                assertEquals(1, repository.send(firstTurn, deadline))
                assertEquals(0, repository.send(firstTurn, deadline.plusSeconds(30)))
                assertEquals(deadline.toInstant(), repository.turn(firstTurn)?.deadline?.toInstant(), "Recovery cannot extend a sent prompt's budget")
                assertEquals(second, repository.next(id)?.id)
                repository.finish(firstTurn, "COMPLETED", JsonPrimitive("done"))
                assertEquals(0, repository.send(firstTurn, deadline))
                assertEquals("COMPLETED", repository.turn(firstTurn)?.status)
                assertEquals(JsonPrimitive("done"), repository.turn(firstTurn)?.result)
                val owner = UUID.random()
                val archiveCreated = OffsetDateTime.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                val active = current.copy(owner = owner, epoch = 5, leaseUntil = OffsetDateTime.now().plusSeconds(60),
                    archiveKey = "committed", archiveSha256 = "a".repeat(64), archiveSize = 40, archiveCreated = archiveCreated)
                assertEquals(1, repository.save(active))
                assertEquals(0, repository.renew(id, owner, 4))
                assertEquals(1, repository.renew(id, owner, 5))
                val mapped = assertNotNull(repository.get(id))
                assertEquals("committed", mapped.archiveKey)
                assertEquals(40L, mapped.archiveSize)
                assertEquals(owner, mapped.owner)
                assertEquals(5L, mapped.epoch)
                assertEquals(archiveCreated.toInstant(), mapped.archiveCreated?.toInstant())
                val created = OffsetDateTime.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                val approval = bosca.ai.kit.harness.KitRuntimeApproval(UUID.random(), id, firstTurn, "request", JsonObject(emptyMap()), expires = deadline, created = created)
                repository.addApproval(approval)
                assertEquals(created.toInstant(), repository.approval(firstTurn, "request")?.created?.toInstant())
                repository.addApproval(approval.copy(id = UUID.random(), created = created.plusSeconds(5)))
                assertEquals(created.toInstant(), repository.approval(firstTurn, "request")?.created?.toInstant(), "Redelivery cannot reset approval duration")
            }
        } finally {
            ProviderRegistry.clear()
            pool.close()
            postgres.stop()
        }
    }
}
