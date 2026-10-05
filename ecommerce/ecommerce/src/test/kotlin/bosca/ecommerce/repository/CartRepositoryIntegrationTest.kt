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
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CartStatus
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.InventoryReservation
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.model.Tax
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for `ecom.carts` — above all, proof that the new `@DbMapper(JsonbMapper)` /
 * value-class codegen round-trips a strongly-typed `List<CartItem>` (with nested Money/Tax/UUID and
 * inventory reservations) through a jsonb column, and the `CartStatus` value class through the int
 * `status` column. Also covers the expiration-sweep candidate query.
 */
@OptIn(ExperimentalUuidApi::class)
class CartRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_cart_test")
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
    private val manufacturerRepository = ManufacturerRepositoryImpl()
    private val productRepository = ProductRepositoryImpl()
    private val catalogRepository = CatalogRepositoryImpl()
    private val catalogProductRepository = CatalogProductRepositoryImpl()
    private val paymentProviderRepository = PaymentProviderRepositoryImpl()
    private val storeRepository = StoreRepositoryImpl()
    private val cartRepository = CartRepositoryImpl()
    private val cartAddressRepository = CartAddressRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.cart_addresses") { it.execute() }
                connection().useStatement("DELETE FROM ecom.carts") { it.execute() }
                connection().useStatement("DELETE FROM ecom.stores") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalog_products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.catalogs") { it.execute() }
                connection().useStatement("DELETE FROM ecom.products") { it.execute() }
                connection().useStatement("DELETE FROM ecom.manufacturers") { it.execute() }
                connection().useStatement("DELETE FROM ecom.payment_providers") { it.execute() }
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

    private data class Fixture(val companyId: UUID, val storeId: UUID)

    private fun fixture(): Fixture {
        lateinit var f: Fixture
        withDb {
            transaction {
                val companyId = companyRepository.add(Company(organizationId = UUID.random(), profileId = UUID.random())).id
                val manufacturerId = manufacturerRepository.add(Manufacturer(companyId = companyId, name = "Acme")).id
                val catalogId = catalogRepository.add(Catalog(companyId = companyId, key = "default", name = "Default")).id
                val productId = productRepository.add(
                    Product(companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SHIP", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.SHIPPING),
                ).id
                val shippingCp = catalogProductRepository.add(
                    CatalogProduct(catalogId = catalogId, productId = productId, type = ProductType.SHIPPING, price = Money.of("0.00")),
                ).id
                val paymentProviderId = paymentProviderRepository.add(PaymentProvider(companyId = companyId, name = "Test", providerKey = "test")).id
                val storeId = storeRepository.add(
                    Store(identifier = "shop", name = "Shop", companyId = companyId, catalogId = catalogId, type = StoreType.VIRTUAL, paymentProviderId = paymentProviderId, shippingCatalogProductId = shippingCp),
                ).id
                f = Fixture(companyId, storeId)
            }
        }
        return f
    }

    @Test
    fun `cart with typed items round-trips through the items jsonb and status value class`() {
        val fx = fixture()
        val itemId = UUID.random()
        val invId = UUID.random()
        val item = CartItem(
            id = itemId,
            catalogProductId = UUID.random(),
            type = ProductType.PHYSICAL,
            quantity = 3,
            status = CartStatus.of(CartStatusFlag.OPEN, CartStatusFlag.PREPARING),
            baseRetailPrice = Money.of("9.99"),
            retailPrice = Money.of("9.99"),
            salesPrice = Money.of("8.99"),
            retailSubtotal = Money.of("29.97"),
            salesSubtotal = Money.of("26.97"),
            taxes = Tax.of(state = Money.of("1.50")),
            reservations = listOf(InventoryReservation(invId, 3)),
        )
        lateinit var created: Cart
        withDb {
            transaction {
                created = cartRepository.add(
                    Cart(companyId = fx.companyId, storeId = fx.storeId, status = CartStatus.of(CartStatusFlag.OPEN, CartStatusFlag.PREPARING), items = listOf(item), expires = OffsetDateTime.now().plusSeconds(3600), quantity = 3),
                )
            }
        }

        var loaded: Cart? = null
        withDb { transaction { loaded = cartRepository.get(created.id) } }

        val cart = requireNotNull(loaded)
        assertEquals(setOf(CartStatusFlag.OPEN, CartStatusFlag.PREPARING), cart.status.flags)
        assertEquals(1, cart.items.size)
        val roundTripped = cart.items.first()
        assertEquals(itemId, roundTripped.id)
        assertEquals(ProductType.PHYSICAL, roundTripped.type)
        assertEquals(3, roundTripped.quantity)
        assertEquals(Money.of("8.99"), roundTripped.salesPrice)
        assertEquals(Money.of("1.50"), roundTripped.taxes.state)
        assertEquals(Money.of("1.50"), roundTripped.taxes.taxes)
        assertEquals(listOf(InventoryReservation(invId, 3)), roundTripped.reservations)
        assertTrue(roundTripped.status.has(CartStatusFlag.PREPARING))
    }

    @Test
    fun `cart address upserts on the cart+type uniqueness`() {
        val fx = fixture()
        lateinit var cartId: UUID
        withDb { transaction { cartId = cartRepository.add(Cart(companyId = fx.companyId, storeId = fx.storeId, expires = OffsetDateTime.now().plusSeconds(3600))).id } }

        withDb {
            transaction {
                cartAddressRepository.upsert(
                    CartAddress(cartId = cartId, type = AddressType.SHIPPING, firstName = "Ada", lastName = "L", address1 = "1 Way", city = "London", state = "LDN", country = "GB", zip = "EC1", phone = "555"),
                )
                // second upsert of the same cart+type updates rather than inserting
                cartAddressRepository.upsert(
                    CartAddress(cartId = cartId, type = AddressType.SHIPPING, firstName = "Grace", lastName = "H", address1 = "2 Way", city = "Arlington", state = "VA", country = "US", zip = "22202", phone = "777"),
                )
            }
        }

        var addresses: List<CartAddress> = emptyList()
        withDb { transaction { addresses = cartAddressRepository.getByCart(cartId) } }
        assertEquals(1, addresses.size)
        assertEquals(AddressType.SHIPPING, addresses.first().type)
        assertEquals("Grace", addresses.first().firstName)
    }

    @Test
    fun `getExpiredOpen returns only past-due open carts`() {
        val fx = fixture()
        val past = OffsetDateTime.now().minusSeconds(60)
        val future = OffsetDateTime.now().plusSeconds(3600)
        withDb {
            transaction {
                cartRepository.add(Cart(companyId = fx.companyId, storeId = fx.storeId, status = CartStatus.of(CartStatusFlag.OPEN), expires = past))
                cartRepository.add(Cart(companyId = fx.companyId, storeId = fx.storeId, status = CartStatus.of(CartStatusFlag.OPEN), expires = future))
                cartRepository.add(Cart(companyId = fx.companyId, storeId = fx.storeId, status = CartStatus.of(CartStatusFlag.PAID), expires = past))
            }
        }

        var expired: List<Cart> = emptyList()
        withDb { transaction { expired = cartRepository.getExpiredOpen(CartStatusFlag.OPEN.bit, 10) } }

        assertEquals(1, expired.size)
        assertTrue(expired.first().status.has(CartStatusFlag.OPEN))
        assertTrue(expired.first().expires.isBefore(OffsetDateTime.now()))
    }
}
