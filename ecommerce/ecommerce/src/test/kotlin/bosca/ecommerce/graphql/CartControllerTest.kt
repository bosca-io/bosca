package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CartStatus
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.TransactionType
import bosca.ecommerce.service.CartService
import bosca.ecommerce.service.PaymentService
import bosca.graphql.Batch
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Cart field wiring: scalar fields read the source; relations resolve through the services. */
@OptIn(ExperimentalUuidApi::class)
class CartControllerTest {

    private val cartService = mockk<CartService>(relaxUnitFun = true)
    private val paymentService = mockk<PaymentService>()
    private val controller = CartController(cartService, paymentService)

    private val companyId = UUID.random()
    private val storeId = UUID.random()
    private val accountId = UUID.random()
    private val customerId = UUID.random()

    private val source = Cart(
        id = UUID.random(),
        companyId = companyId,
        storeId = storeId,
        accountId = accountId,
        customerId = customerId,
        status = CartStatus.of(CartStatusFlag.OPEN, CartStatusFlag.PAYMENT_DUE),
        items = listOf(CartItem(id = UUID.random(), catalogProductId = UUID.random(), type = ProductType.PHYSICAL, quantity = 1)),
        expires = OffsetDateTime.now().plusSeconds(3600),
        retailTotal = Money.of("110.00"),
        retailSubtotal = Money.of("100.00"),
        salesTotal = Money.of("105.00"),
        salesSubtotal = Money.of("95.00"),
        shipping = Money.of("5.00"),
        tax = Money.of("8.00"),
        discounts = Money.of("3.00"),
        paid = Money.of("20.00"),
        pendingPaid = Money.of("4.00"),
        refundDue = Money.of("1.00"),
        due = Money.of("80.00"),
        quantity = 1,
    )

    @Test
    fun `scalar fields resolve from the source`() {
        assertEquals(source.id, controller.id(source))
        assertEquals(listOf(CartStatusFlag.OPEN, CartStatusFlag.PAYMENT_DUE), controller.status(source))
        assertEquals(source.items, controller.items(source))
        assertEquals(Money.of("110.00"), controller.retailTotal(source))
        assertEquals(Money.of("100.00"), controller.retailSubtotal(source))
        assertEquals(Money.of("105.00"), controller.salesTotal(source))
        assertEquals(Money.of("95.00"), controller.salesSubtotal(source))
        assertEquals(Money.of("5.00"), controller.shipping(source))
        assertEquals(Money.of("8.00"), controller.tax(source))
        assertEquals(Money.of("3.00"), controller.discounts(source))
        assertEquals(Money.of("20.00"), controller.paid(source))
        assertEquals(Money.of("4.00"), controller.pendingPaid(source))
        assertEquals(Money.of("1.00"), controller.refundDue(source))
        assertEquals(Money.of("80.00"), controller.due(source))
        assertEquals(1, controller.quantity(source))
        assertEquals(source.expires, controller.expires(source))
        assertEquals(source.created, controller.created(source))
        assertEquals(source.modified, controller.modified(source))
    }

    @Test
    fun `addresses and payments resolve through their services`() = runTest {
        val addresses = listOf(
            CartAddress(id = UUID.random(), cartId = source.id, type = AddressType.SHIPPING, firstName = "A", lastName = "B", address1 = "1", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555"),
        )
        coEvery { cartService.getAddresses(source.id) } returns addresses
        val payments = listOf(
            Payment(id = UUID.random(), transactionType = TransactionType.PAYMENT, type = PaymentType.CASH, providerId = UUID.random(), storeId = storeId, amount = Money.of("20.00")),
        )
        coEvery { paymentService.getByCart(source.id, 0, 25) } returns payments

        assertEquals(addresses, controller.addresses(source))
        assertEquals(payments, controller.payments(source, 0, 25))
    }

    @Test
    fun `company, store, account and customer delegate to the cart service batch loaders`() = runTest {
        val companyBatch = Batch<UUID, Company>(listOf(source.id))
        val storeBatch = Batch<UUID, Store>(listOf(source.id))
        val accountBatch = Batch<UUID, Account>(listOf(source.id))
        val customerBatch = Batch<UUID, Customer>(listOf(source.id))

        controller.company(companyBatch)
        controller.store(storeBatch)
        controller.account(accountBatch)
        controller.customer(customerBatch)

        coVerify(exactly = 1) { cartService.addCompaniesToBatch(companyBatch) }
        coVerify(exactly = 1) { cartService.addStoresToBatch(storeBatch) }
        coVerify(exactly = 1) { cartService.addAccountsToBatch(accountBatch) }
        coVerify(exactly = 1) { cartService.addCustomersToBatch(customerBatch) }
    }
}
