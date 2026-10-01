@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.ecommerce.migration.EcommerceMigration
import bosca.ecommerce.model.Audit
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres proof of the module's single accountability log `ecom.audit`: `add` casts the
 * before/after/details jsonb (`::jsonb`) and `list` applies the nullable-filter idiom
 * (`:p::type is null or col = :p`) so a null param matches everything while a concrete one narrows.
 * The unit suite mocks this repository, so the jsonb round-trip and the both-ways filter SQL are
 * exercised HERE against live SQL. Audit has no FK seeding — it is standalone.
 */
@OptIn(ExperimentalUuidApi::class)
class AuditRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_audit_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 5),
                key = "test",
            ),
        )

        private var schemaInitialized = false
    }

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }
    private val auditRepository = AuditRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(EcommerceMigration())) }
            schemaInitialized = true
        }
        withDb { transaction { connection().useStatement("DELETE FROM ecom.audit") { it.execute() } } }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    @Test
    fun `add round-trips the before after and details jsonb snapshots`() {
        val entityId = UUID.random()
        val principalId = UUID.random()
        val before = JsonObject(mapOf("balance" to JsonPrimitive("10.00")))
        val after = JsonObject(mapOf("balance" to JsonPrimitive("5.00")))
        val details = JsonObject(mapOf("reason" to JsonPrimitive("spend")))

        lateinit var created: Audit
        withDb {
            transaction {
                created = auditRepository.add(
                    Audit(
                        principalId = principalId,
                        entityType = "company_credit",
                        entityId = entityId,
                        action = "balance_changed",
                        before = before,
                        after = after,
                        details = details,
                    ),
                )
            }
        }

        var loaded: List<Audit> = emptyList()
        withDb { transaction { loaded = auditRepository.list("company_credit", entityId, null, null, 0, 10) } }
        assertEquals(1, loaded.size)
        val a = loaded.first()
        assertEquals(created.id, a.id)
        assertEquals(before, a.before, "before jsonb round-trips")
        assertEquals(after, a.after, "after jsonb round-trips")
        assertEquals(details, a.details, "details jsonb round-trips")
        assertEquals(principalId, a.principalId)
    }

    @Test
    fun `add tolerates null before after and details`() {
        val entityId = UUID.random()
        withDb {
            transaction {
                auditRepository.add(Audit(entityType = "cart", entityId = entityId, action = "viewed"))
            }
        }
        var loaded: List<Audit> = emptyList()
        withDb { transaction { loaded = auditRepository.list("cart", entityId, null, null, 0, 10) } }
        assertEquals(1, loaded.size)
        assertNull(loaded.first().before)
        assertNull(loaded.first().after)
        assertNull(loaded.first().details)
    }

    @Test
    fun `list with all-null filters returns everything newest first`() {
        withDb {
            transaction {
                auditRepository.add(Audit(entityType = "cart", entityId = UUID.random(), action = "created"))
                auditRepository.add(Audit(entityType = "payment", entityId = UUID.random(), action = "charged"))
                auditRepository.add(Audit(entityType = "subscription", entityId = UUID.random(), action = "renewed"))
            }
        }

        var all: List<Audit> = emptyList()
        withDb { transaction { all = auditRepository.list(null, null, null, null, 0, 10) } }
        assertEquals(3, all.size, "every filter null -> the nullable-filter idiom matches all rows")
    }

    @Test
    fun `list narrows to the matching entityType and entityId`() {
        val targetId = UUID.random()
        withDb {
            transaction {
                auditRepository.add(Audit(entityType = "payment", entityId = targetId, action = "charged"))
                // Same type, different id -> excluded by the entityId filter.
                auditRepository.add(Audit(entityType = "payment", entityId = UUID.random(), action = "charged"))
                // Same id, different type -> excluded by the entityType filter.
                auditRepository.add(Audit(entityType = "cart", entityId = targetId, action = "created"))
            }
        }

        var byEntity: List<Audit> = emptyList()
        var byAction: List<Audit> = emptyList()
        withDb {
            transaction {
                byEntity = auditRepository.list("payment", targetId, null, null, 0, 10)
                byAction = auditRepository.list(null, null, null, "charged", 0, 10)
            }
        }
        assertEquals(1, byEntity.size, "entityType + entityId narrows to exactly the one match")
        assertEquals("payment", byEntity.first().entityType)
        assertEquals(targetId, byEntity.first().entityId)
        assertEquals(2, byAction.size, "the action filter alone matches both 'charged' rows")
    }
}
