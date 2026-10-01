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
import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.model.TransactionType
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
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres proof of the critical money path `ecom.payments`: the native enum columns
 * (`transaction_type`/`type`) need their `::ecom.<type>` casts in `add`, and the `numeric(32,4)`
 * Money amounts must round-trip; `getByDateRange` enforces a half-open `[start, end)` window; `update`
 * persists the refund/void lifecycle fields. The unit suite mocks this repository — exactly the kind
 * of place a missing enum cast (like the IAP `platform` bug) would hide — so it's exercised HERE
 * against live SQL.
 */
@OptIn(ExperimentalUuidApi::class)
class PaymentRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_ecom_payment_test")
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
    private val accountRepository = AccountRepositoryImpl()
    private val cartRepository = CartRepositoryImpl()
    private val paymentRepository = PaymentRepositoryImpl()

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
                connection().useStatement("DELETE FROM ecom.payments") { it.execute() }
                connection().useStatement("DELETE FROM ecom.carts") { it.execute() }
                connection().useStatement("DELETE FROM ecom.accounts") { it.execute() }
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

    /** store(chain) + account + cart so payments' FKs (provider/store/account/cart) are satisfiable. */
    private data class Fixture(val storeId: UUID, val providerId: UUID, val accountId: UUID, val cartId: UUID)

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
                val providerId = paymentProviderRepository.add(PaymentProvider(companyId = companyId, name = "Test", providerKey = "test")).id
                val storeId = storeRepository.add(
                    Store(identifier = "shop", name = "Shop", companyId = companyId, catalogId = catalogId, type = StoreType.VIRTUAL, paymentProviderId = providerId, shippingCatalogProductId = shippingCp),
                ).id
                val accountId = accountRepository.add(Account(companyId = companyId, type = AccountType.CONSUMER)).id
                val cartId = cartRepository.add(Cart(companyId = companyId, storeId = storeId, accountId = accountId, expires = OffsetDateTime.now().plusSeconds(3600))).id
                f = Fixture(storeId, providerId, accountId, cartId)
            }
        }
        return f
    }

    private fun Fixture.payment(
        transactionType: TransactionType = TransactionType.PAYMENT,
        type: PaymentType = PaymentType.CREDIT_CARD,
        amount: Money = Money.of("19.99"),
    ) = Payment(
        transactionType = transactionType,
        type = type,
        providerId = providerId,
        storeId = storeId,
        accountId = accountId,
        cartId = cartId,
        amount = amount,
        complete = true,
        confirmed = true,
        providerTransactionId = "ptx-1",
    )

    @Test
    fun `add get and getForUpdate round-trip the enum columns and money amount`() {
        val fx = fixture()
        lateinit var created: Payment
        withDb {
            transaction {
                created = paymentRepository.add(fx.payment(transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, amount = Money.of("19.99")))
            }
        }
        assertEquals(TransactionType.PAYMENT, created.transactionType)
        assertEquals(PaymentType.CREDIT_CARD, created.type)
        assertEquals(Money.of("19.99"), created.amount)

        var byGet: Payment? = null
        var byLock: Payment? = null
        withDb {
            transaction {
                byGet = paymentRepository.get(created.id)
                byLock = paymentRepository.getForUpdate(created.id)
            }
        }
        val g = requireNotNull(byGet)
        assertEquals(TransactionType.PAYMENT, g.transactionType, "transaction_type native enum must read back")
        assertEquals(PaymentType.CREDIT_CARD, g.type, "payment_type native enum must read back")
        assertEquals(Money.of("19.99"), g.amount, "numeric(32,4) money must round-trip")
        assertEquals(created.id, byLock?.id, "getForUpdate returns the same row under a row lock")
    }

    @Test
    fun `getByCart and getAllByCart and getByAccount page and order the cart's tenders`() {
        val fx = fixture()
        withDb {
            transaction {
                paymentRepository.add(fx.payment(amount = Money.of("10.00")))
                paymentRepository.add(fx.payment(amount = Money.of("20.00")))
                paymentRepository.add(fx.payment(amount = Money.of("30.00")))
            }
        }

        var byCart: List<Payment> = emptyList()
        var allByCart: List<Payment> = emptyList()
        var byAccount: List<Payment> = emptyList()
        var page: List<Payment> = emptyList()
        withDb {
            transaction {
                byCart = paymentRepository.getByCart(fx.cartId, 0, 10)
                allByCart = paymentRepository.getAllByCart(fx.cartId)
                byAccount = paymentRepository.getByAccount(fx.accountId, 0, 10)
                page = paymentRepository.getByCart(fx.cartId, 1, 1)
            }
        }
        assertEquals(3, byCart.size)
        assertEquals(3, allByCart.size)
        assertEquals(3, byAccount.size)
        // getByCart is oldest-first; page offset 1 limit 1 must yield exactly the middle row.
        assertEquals(1, page.size, "offset/limit paging is honored")
        assertEquals(allByCart[1].id, page.first().id)
    }

    @Test
    fun `getByDateRange includes a payment inside the half-open window and excludes one outside`() {
        val fx = fixture()
        val start = OffsetDateTime.now().minusHours(1)
        val end = OffsetDateTime.now().plusHours(1)
        lateinit var inside: Payment
        withDb {
            transaction {
                inside = paymentRepository.add(fx.payment(amount = Money.of("5.00")))
                // Backdate one payment well before `start` so it is excluded by created >= :start.
                val old = paymentRepository.add(fx.payment(amount = Money.of("6.00")))
                connection().useStatement("update ecom.payments set created = now() - interval '2 days' where id = ?") { stmt ->
                    stmt.setObject(1, old.id.toJavaUuid())
                    stmt.execute()
                }
            }
        }

        var inWindow: List<Payment> = emptyList()
        withDb { transaction { inWindow = paymentRepository.getByDateRange(start, end, 0, 10) } }
        assertEquals(1, inWindow.size, "only the payment created within [start, end) is returned")
        assertEquals(inside.id, inWindow.first().id)
        assertTrue(inWindow.all { !it.created.isBefore(start) && it.created.isBefore(end) })
    }

    @Test
    fun `update persists the refund and void accounting fields`() {
        val fx = fixture()
        lateinit var created: Payment
        withDb { transaction { created = paymentRepository.add(fx.payment(amount = Money.of("50.00"))) } }

        val refundedAt = OffsetDateTime.now()
        var updated: Payment? = null
        withDb {
            transaction {
                updated = paymentRepository.update(
                    created.copy(
                        refundedAmount = Money.of("12.50"),
                        refunded = refundedAt,
                        refundReason = "partial",
                        complete = true,
                        confirmed = true,
                    ),
                )
            }
        }
        val u = requireNotNull(updated)
        assertEquals(Money.of("12.50"), u.refundedAmount, "refunded_amount persists")
        assertEquals("partial", u.refundReason, "refund_reason persists")
        assertTrue(u.refunded != null, "refunded timestamp persists")
    }
}
