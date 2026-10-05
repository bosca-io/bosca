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
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.WeightUnit
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Real-Postgres tests for `ecom.companies` and the `ecom.fulfillment_centers` queries not already
 * covered by [FulfillmentSyncRepositoryIntegrationTest] (which exercises getSyncable/touchSynced).
 * Here: the company `updateUnits` native-enum casts (`(:lengthUnit)::ecom.length_unit`,
 * `(:weightUnit)::ecom.weight_unit`), getAll/getByIds/soft-delete, and the fulfillment center
 * get/getByCompany/update plus `countByShippingProvider` provider-deletion guard.
 */
@OptIn(ExperimentalUuidApi::class)
class CompanyRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_company_test")
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
    private val shippingProviderRepository = ShippingProviderRepositoryImpl()
    private val fulfillmentCenterRepository = FulfillmentCenterRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.fulfillment_centers") { it.execute() }
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

    @Test
    fun `company add then get, getByIds and getAll resolve it with default inches and pounds`() {
        lateinit var company: Company
        withDb { transaction { company = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())) } }
        assertEquals(LengthUnit.INCHES, company.lengthUnit, "default length unit")
        assertEquals(WeightUnit.POUNDS, company.weightUnit, "default weight unit")

        withDb {
            transaction {
                val loaded = assertNotNull(companyRepository.get(company.id))
                assertEquals(company.organizationId, loaded.organizationId)
                assertEquals(company.profileId, loaded.profileId)

                assertEquals(listOf(company.id), companyRepository.getByIds(listOf(company.id)).map { it.id })
                assertEquals(listOf(company.id), companyRepository.getAll(offset = 0, limit = 50).map { it.id })
            }
        }
    }

    @Test
    fun `updateUnits round-trips the length_unit and weight_unit enums`() {
        lateinit var company: Company
        withDb { transaction { company = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())) } }

        withDb {
            transaction {
                val updated = assertNotNull(
                    companyRepository.updateUnits(company.id, LengthUnit.CENTIMETERS, WeightUnit.KILOGRAMS),
                )
                assertEquals(LengthUnit.CENTIMETERS, updated.lengthUnit, "the length-unit enum round-trips through its cast")
                assertEquals(WeightUnit.KILOGRAMS, updated.weightUnit, "the weight-unit enum round-trips through its cast")
            }
        }

        withDb { transaction { assertEquals(LengthUnit.CENTIMETERS, companyRepository.get(company.id)?.lengthUnit) } }
    }

    @Test
    fun `softDelete hides the company from get and getAll`() {
        lateinit var keep: Company
        lateinit var drop: Company
        withDb {
            transaction {
                keep = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random()))
                drop = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random()))
            }
        }

        withDb { transaction { companyRepository.softDelete(drop.id) } }

        withDb {
            transaction {
                assertNull(companyRepository.get(drop.id), "soft-deleted company is invisible")
                assertEquals(listOf(keep.id), companyRepository.getAll(offset = 0, limit = 50).map { it.id })
            }
        }
    }

    @Test
    fun `fulfillment center get, getByCompany, update and countByShippingProvider work`() {
        lateinit var companyId: UUID
        lateinit var shippingProviderId: UUID
        lateinit var otherProviderId: UUID
        lateinit var center: FulfillmentCenter
        withDb {
            transaction {
                companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                shippingProviderId = shippingProviderRepository.add(ShippingProvider(companyId = companyId, name = "Ship", key = "ship-1", providerKey = "manual")).id
                otherProviderId = shippingProviderRepository.add(ShippingProvider(companyId = companyId, name = "Other", key = "ship-2", providerKey = "manual")).id
                center = fulfillmentCenterRepository.add(
                    FulfillmentCenter(
                        companyId = companyId, name = "East", connectorKey = "manual", shippingProviderId = shippingProviderId,
                        address1 = "1 Dock", city = "Newark", state = "NJ", country = "US", zip = "07101",
                    ),
                )
            }
        }

        withDb {
            transaction {
                val loaded = assertNotNull(fulfillmentCenterRepository.get(center.id))
                assertEquals("East", loaded.name)
                assertEquals(shippingProviderId, loaded.shippingProviderId)

                assertEquals(listOf(center.id), fulfillmentCenterRepository.getByCompany(companyId).map { it.id })

                assertEquals(1L, fulfillmentCenterRepository.countByShippingProvider(shippingProviderId), "one center binds the provider")
                assertEquals(0L, fulfillmentCenterRepository.countByShippingProvider(otherProviderId), "no center binds the other provider")
            }
        }

        withDb {
            transaction {
                val updated = assertNotNull(
                    fulfillmentCenterRepository.update(
                        center.copy(name = "East 2", connectorKey = "manual", shippingProviderId = otherProviderId, city = "Edison"),
                    ),
                )
                assertEquals("East 2", updated.name)
                assertEquals(otherProviderId, updated.shippingProviderId)
                assertEquals("Edison", updated.city)
            }
        }

        withDb {
            transaction {
                // The center now binds the other provider, so the count moves across.
                assertEquals(0L, fulfillmentCenterRepository.countByShippingProvider(shippingProviderId))
                assertEquals(1L, fulfillmentCenterRepository.countByShippingProvider(otherProviderId))
            }
        }
    }
}
