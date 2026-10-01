package bosca.ecommerce.service

import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Tax
import bosca.ecommerce.repository.CartAddressRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**tax pricing — taxes the discounted amount using the cart's shipping address; idempotent. */
@OptIn(ExperimentalUuidApi::class)
class TaxPricerTest {

    private val taxCalculator = mockk<TaxCalculator>()
    private val cartAddressRepository = mockk<CartAddressRepository>()
    private val pricer = TaxPricer(taxCalculator, cartAddressRepository)

    private val cartId = UUID.random()

    private fun cart() = Cart(
        id = cartId, companyId = UUID.random(), storeId = UUID.random(),
        items = listOf(
            CartItem(id = UUID.random(), catalogProductId = UUID.random(), type = ProductType.PHYSICAL, quantity = 2, retailPrice = Money.of("10.00"), salesPrice = Money.of("10.00"), retailSubtotal = Money.of("20.00"), salesSubtotal = Money.of("20.00"), discounts = Money.of("5.00")),
        ),
        expires = OffsetDateTime.now().plusSeconds(3600),
    )

    @Test
    fun `taxes the discounted amount using the shipping address`() = runTest {
        val shipping = CartAddress(cartId = cartId, type = AddressType.SHIPPING, firstName = "A", lastName = "B", address1 = "1", city = "C", state = "CA", country = "US", zip = "0", phone = "5")
        coEvery { cartAddressRepository.getByCart(cartId) } returns listOf(shipping)
        // taxable = salesSubtotal(20) - discounts(5) = 15
        coEvery { taxCalculator.calculate(Money.of("15.00"), shipping) } returns Tax.of(state = Money.of("1.20"))

        val result = pricer.price(cart())

        assertEquals(Money.of("1.20"), result.items.first().taxes.taxes)
    }

    @Test
    fun `no address yields zero tax`() = runTest {
        coEvery { cartAddressRepository.getByCart(cartId) } returns emptyList()
        coEvery { taxCalculator.calculate(any(), null) } returns Tax.ZERO

        val result = pricer.price(cart())

        assertEquals(Tax.ZERO, result.items.first().taxes)
    }

    @Test
    fun `falls back to the billing address when there is no shipping address`() = runTest {
        val billing = CartAddress(cartId = cartId, type = AddressType.BILLING, firstName = "A", lastName = "B", address1 = "1", city = "C", state = "CA", country = "US", zip = "0", phone = "5")
        coEvery { cartAddressRepository.getByCart(cartId) } returns listOf(billing)
        coEvery { taxCalculator.calculate(Money.of("15.00"), billing) } returns Tax.of(state = Money.of("1.00"))

        val result = pricer.price(cart())

        assertEquals(Money.of("1.00"), result.items.first().taxes.taxes)
    }

    @Test
    fun `a discount exceeding the subtotal clamps the taxable amount to zero`() = runTest {
        // salesSubtotal 20 - discounts 25 = negative -> the `if (it.isPositive) it else ZERO` else branch.
        val zeroed = cart().let { c -> c.copy(items = c.items.map { it.copy(discounts = Money.of("25.00")) }) }
        coEvery { cartAddressRepository.getByCart(cartId) } returns emptyList()
        coEvery { taxCalculator.calculate(Money.ZERO, null) } returns Tax.ZERO

        val result = pricer.price(zeroed)

        assertEquals(Tax.ZERO, result.items.first().taxes)
    }

    @Test
    fun `prefers the shipping address even when a billing address is also present`() = runTest {
        // Both types on file: the `firstOrNull { SHIPPING }` wins, so the BILLING `?:` arm is never taken.
        val shipping = CartAddress(cartId = cartId, type = AddressType.SHIPPING, firstName = "S", lastName = "S", address1 = "1", city = "C", state = "CA", country = "US", zip = "0", phone = "5")
        val billing = CartAddress(cartId = cartId, type = AddressType.BILLING, firstName = "B", lastName = "B", address1 = "2", city = "C", state = "NY", country = "US", zip = "0", phone = "5")
        coEvery { cartAddressRepository.getByCart(cartId) } returns listOf(billing, shipping)
        coEvery { taxCalculator.calculate(Money.of("15.00"), shipping) } returns Tax.of(state = Money.of("1.20"))

        val result = pricer.price(cart())

        assertEquals(Money.of("1.20"), result.items.first().taxes.taxes)
    }

    @Test
    fun `an empty item list yields a cart with no items`() = runTest {
        // The `cart.items.map { }` over an empty list never calls the calculator.
        coEvery { cartAddressRepository.getByCart(cartId) } returns emptyList()
        val empty = cart().copy(items = emptyList())

        val result = pricer.price(empty)

        assertEquals(emptyList(), result.items)
    }

    @Test
    fun `pricing order is after promotions`() {
        assertEquals(20, pricer.order)
    }
}
