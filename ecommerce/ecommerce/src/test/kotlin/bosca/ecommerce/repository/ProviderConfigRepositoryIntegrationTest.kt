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
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.ShippingProvider
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for `ecom.payment_providers` and `ecom.shipping_providers`. The point is the
 * sealed [bosca.ecommerce.model.ProviderConfiguration] round-tripping through the `configuration`
 * jsonb via JsonbMapper (polymorphic discriminator, secret bag), plus gets/getByCompany/update/
 * soft-delete against the live DDL.
 */
@OptIn(ExperimentalUuidApi::class)
class ProviderConfigRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_provider_test")
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
    private val companyRepository = CompanyRepositoryImpl()
    private val paymentProviderRepository = PaymentProviderRepositoryImpl()
    private val shippingProviderRepository = ShippingProviderRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(EcommerceMigration())) }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("DELETE FROM ecom.payment_providers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.shipping_providers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.companies") { it.execute() }
            }
        }
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

    private fun companyId(): UUID {
        lateinit var id: UUID
        withDb { transaction { id = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id } }
        return id
    }

    @Test
    fun `payment provider configuration jsonb round-trips and update replaces it`() {
        val companyId = companyId()
        lateinit var provider: PaymentProvider
        withDb {
            transaction {
                provider = paymentProviderRepository.add(
                    PaymentProvider(
                        companyId = companyId, name = "Stripe", providerKey = "stripe",
                        configuration = KeyValueProviderConfiguration(mapOf("apiKey" to "sk_test_123", "endpoint" to "https://api.example")),
                    ),
                )
            }
        }
        val added = assertIs<KeyValueProviderConfiguration>(provider.configuration)
        assertEquals("sk_test_123", added.values["apiKey"])

        withDb {
            transaction {
                val loaded = assertNotNull(paymentProviderRepository.get(provider.id))
                val cfg = assertIs<KeyValueProviderConfiguration>(loaded.configuration, "the sealed config round-trips with its discriminator")
                assertEquals("sk_test_123", cfg.values["apiKey"])
                assertEquals("https://api.example", cfg.values["endpoint"])
            }
        }

        withDb {
            transaction {
                val updated = assertNotNull(
                    paymentProviderRepository.update(
                        provider.copy(name = "Stripe Prod", providerKey = "stripe", configuration = EmptyProviderConfiguration),
                    ),
                )
                assertEquals("Stripe Prod", updated.name)
                assertIs<EmptyProviderConfiguration>(updated.configuration, "update overwrites the jsonb to the empty variant")
            }
        }
    }

    @Test
    fun `payment provider getByCompany lists live providers and softDelete hides one`() {
        val companyId = companyId()
        lateinit var keep: PaymentProvider
        lateinit var drop: PaymentProvider
        withDb {
            transaction {
                keep = paymentProviderRepository.add(PaymentProvider(companyId = companyId, name = "Keep", providerKey = "test"))
                drop = paymentProviderRepository.add(PaymentProvider(companyId = companyId, name = "Drop", providerKey = "test"))
            }
        }

        withDb { transaction { paymentProviderRepository.softDelete(drop.id) } }

        withDb {
            transaction {
                assertNull(paymentProviderRepository.get(drop.id), "soft-deleted provider is invisible")
                val live = paymentProviderRepository.getByCompany(companyId)
                assertEquals(listOf(keep.id), live.map { it.id }, "getByCompany excludes the soft-deleted row")
            }
        }
    }

    @Test
    fun `shipping provider configuration jsonb round-trips with its unique key, update and softDelete work`() {
        val companyId = companyId()
        lateinit var provider: ShippingProvider
        withDb {
            transaction {
                provider = shippingProviderRepository.add(
                    ShippingProvider(
                        companyId = companyId, name = "Shippo", key = "shippo-1", providerKey = "shippo",
                        configuration = KeyValueProviderConfiguration(mapOf("token" to "shippo_live_abc")),
                    ),
                )
            }
        }

        withDb {
            transaction {
                val loaded = assertNotNull(shippingProviderRepository.get(provider.id))
                assertEquals("shippo-1", loaded.key)
                val cfg = assertIs<KeyValueProviderConfiguration>(loaded.configuration)
                assertEquals("shippo_live_abc", cfg.values["token"])

                val byCompany = shippingProviderRepository.getByCompany(companyId)
                assertEquals(listOf(provider.id), byCompany.map { it.id })
            }
        }

        withDb {
            transaction {
                val updated = assertNotNull(
                    shippingProviderRepository.update(
                        provider.copy(name = "Shippo Prod", key = "shippo-2", providerKey = "shippo", configuration = EmptyProviderConfiguration),
                    ),
                )
                assertEquals("shippo-2", updated.key)
                assertIs<EmptyProviderConfiguration>(updated.configuration)
            }
        }

        withDb {
            transaction {
                shippingProviderRepository.softDelete(provider.id)
                assertNull(shippingProviderRepository.get(provider.id))
                assertEquals(emptyList(), shippingProviderRepository.getByCompany(companyId).map { it.id })
            }
        }
    }
}
