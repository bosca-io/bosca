package bosca.ecommerce.graphql

import bosca.ecommerce.model.AddCartItemInput
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartStatus
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.CartSubmitResult
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.RefundItemInput
import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.SetCartAddressInput
import bosca.ecommerce.model.SubmitPaymentInput
import bosca.ecommerce.service.CartService
import bosca.ecommerce.service.PromotionService
import bosca.ecommerce.service.ShippingService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * CartMutationController gating + delegation. Buyer-facing ops authorize via CartAccessEvaluator
 * (after loading the cart); admin ops require the ecom-administrator group.
 */
@OptIn(ExperimentalUuidApi::class)
class CartMutationControllerTest {

    private val cartService = mockk<CartService>()
    private val promotionService = mockk<PromotionService>()
    private val shippingService = mockk<ShippingService>()
    private val cartAccess = mockk<CartAccessEvaluator>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = CartMutationController(cartService, promotionService, shippingService, cartAccess, groups)

    private val cartId = UUID.random()
    private val storeId = UUID.random()
    private val source = CartMutation(cartId)

    private fun cart(status: CartStatus = CartStatus.of(CartStatusFlag.OPEN)) = Cart(
        id = cartId, companyId = UUID.random(), storeId = storeId, status = status,
        expires = OffsetDateTime.now().plusSeconds(3600),
    )

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
        // Buyer-facing ops load the cart and then verify access; default to "found + allowed".
        coEvery { cartService.get(cartId) } returns cart()
        coEvery { cartAccess.verify(auth, any()) } just Runs
    }

    // --- buyer-facing ops (CartAccessEvaluator gate) ---

    @Test
    fun `addItem authorizes then delegates`() = runTest {
        val input = AddCartItemInput(catalogProductId = UUID.random(), quantity = 2)
        coEvery { cartService.addItem(cartId, input, null) } returns cart()

        controller.addItem(auth, source, input)

        coVerify(exactly = 1) { cartAccess.verify(auth, any()) }
        coVerify(exactly = 1) { cartService.addItem(cartId, input, null) }
    }

    @Test
    fun `updateItem authorizes then delegates`() = runTest {
        val itemId = UUID.random()
        coEvery { cartService.updateItemQuantity(cartId, itemId, 5, null) } returns cart()

        controller.updateItem(auth, source, itemId, 5)

        coVerify(exactly = 1) { cartAccess.verify(auth, any()) }
        coVerify(exactly = 1) { cartService.updateItemQuantity(cartId, itemId, 5, null) }
    }

    @Test
    fun `removeItem authorizes then delegates`() = runTest {
        val itemId = UUID.random()
        coEvery { cartService.removeItem(cartId, itemId, null) } returns cart()

        controller.removeItem(auth, source, itemId)

        coVerify(exactly = 1) { cartService.removeItem(cartId, itemId, null) }
    }

    @Test
    fun `setAddress authorizes then delegates`() = runTest {
        val input = SetCartAddressInput(
            type = AddressType.SHIPPING, firstName = "Ada", lastName = "Lovelace",
            address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
        )
        val address = CartAddress(
            id = UUID.random(), cartId = cartId, type = AddressType.SHIPPING, firstName = "Ada",
            lastName = "Lovelace", address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
        )
        coEvery { cartService.setAddress(cartId, input, null) } returns address

        assertEquals(address, controller.setAddress(auth, source, input))
        coVerify(exactly = 1) { cartService.setAddress(cartId, input, null) }
    }

    @Test
    fun `removeAddress authorizes then delegates`() = runTest {
        coEvery { cartService.removeAddress(cartId, AddressType.BILLING, null) } returns cart()

        controller.removeAddress(auth, source, AddressType.BILLING)

        coVerify(exactly = 1) { cartService.removeAddress(cartId, AddressType.BILLING, null) }
    }

    @Test
    fun `setBillingSameAsShipping authorizes then delegates`() = runTest {
        coEvery { cartService.setBillingSameAsShipping(cartId, true, null) } returns cart()

        controller.setBillingSameAsShipping(auth, source, true)

        coVerify(exactly = 1) { cartAccess.verify(auth, any()) }
        coVerify(exactly = 1) { cartService.setBillingSameAsShipping(cartId, true, null) }
    }

    @Test
    fun `applyCode authorizes then delegates to the promotion service`() = runTest {
        coEvery { promotionService.applyToCart(cartId, "SAVE10", null) } returns cart()

        controller.applyCode(auth, source, "SAVE10")

        coVerify(exactly = 1) { cartAccess.verify(auth, any()) }
        coVerify(exactly = 1) { promotionService.applyToCart(cartId, "SAVE10", null) }
    }

    @Test
    fun `removeCode authorizes then delegates to the promotion service`() = runTest {
        coEvery { promotionService.removeFromCart(cartId, "SAVE10", null) } returns cart()

        controller.removeCode(auth, source, "SAVE10")

        coVerify(exactly = 1) { promotionService.removeFromCart(cartId, "SAVE10", null) }
    }

    @Test
    fun `setShipping authorizes then delegates to the shipping service`() = runTest {
        coEvery { shippingService.selectRate(cartId, "tok", null) } returns cart()

        controller.setShipping(auth, source, "tok")

        coVerify(exactly = 1) { shippingService.selectRate(cartId, "tok", null) }
    }

    @Test
    fun `submit authorizes then delegates`() = runTest {
        val payment = SubmitPaymentInput(token = "tok")
        val result = CartSubmitResult(cart = cart())
        coEvery { cartService.submit(cartId, payment, null) } returns result

        assertEquals(result, controller.submit(auth, source, payment))
        coVerify(exactly = 1) { cartService.submit(cartId, payment, null) }
    }

    // --- admin-only ops (ecom-administrator gate) ---

    @Test
    fun `complete requires ecom admin then delegates`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        coEvery { cartService.complete(cartId, null) } returns cart()

        controller.complete(auth, source)

        verify { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { cartService.complete(cartId, null) }
    }

    @Test
    fun `cancel requires ecom admin then delegates`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        coEvery { cartService.cancel(cartId, null) } returns cart()

        controller.cancel(auth, source)

        verify { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { cartService.cancel(cartId, null) }
    }

    @Test
    fun `refundItems requires ecom admin then delegates`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        val items = listOf(RefundItemInput(UUID.random(), 1))
        coEvery { cartService.refundItems(cartId, items, RefundTender.ORIGINAL, "20123", "damaged", null) } returns cart()

        controller.refundItems(auth, source, items, RefundTender.ORIGINAL, "20123", "damaged")

        verify { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { cartService.refundItems(cartId, items, RefundTender.ORIGINAL, "20123", "damaged", null) }
    }

    @Test
    fun `confirmCheck requires ecom admin then delegates`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        val paymentId = UUID.random()
        coEvery { cartService.confirmCheck(cartId, paymentId, "20123", null) } returns cart()

        controller.confirmCheck(auth, source, paymentId, "20123")

        coVerify(exactly = 1) { cartService.confirmCheck(cartId, paymentId, "20123", null) }
    }

    @Test
    fun `setLocked requires ecom admin then delegates`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        coEvery { cartService.setLocked(cartId, true, null) } returns cart()

        controller.setLocked(auth, source, true)

        coVerify(exactly = 1) { cartService.setLocked(cartId, true, null) }
    }

    @Test
    fun `setItemPrice requires ecom admin then delegates`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        val itemId = UUID.random()
        coEvery { cartService.setItemPrice(cartId, itemId, Money.of("8.00"), Money.of("7.00"), null) } returns cart()

        controller.setItemPrice(auth, source, itemId, Money.of("8.00"), Money.of("7.00"))

        coVerify(exactly = 1) { cartService.setItemPrice(cartId, itemId, Money.of("8.00"), Money.of("7.00"), null) }
    }

    // --- authenticated principal passes its id through (the `?.id` non-null side) ---

    @Test
    fun `addItem passes the resolved principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val input = AddCartItemInput(catalogProductId = UUID.random(), quantity = 1)
        coEvery { cartService.addItem(cartId, input, principalId) } returns cart()

        controller.addItem(auth, source, input)

        coVerify(exactly = 1) { cartService.addItem(cartId, input, principalId) }
    }

    @Test
    fun `updateItem passes the resolved principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val itemId = UUID.random()
        coEvery { cartService.updateItemQuantity(cartId, itemId, 3, principalId) } returns cart()

        controller.updateItem(auth, source, itemId, 3)

        coVerify(exactly = 1) { cartService.updateItemQuantity(cartId, itemId, 3, principalId) }
    }

    @Test
    fun `removeItem passes the resolved principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val itemId = UUID.random()
        coEvery { cartService.removeItem(cartId, itemId, principalId) } returns cart()

        controller.removeItem(auth, source, itemId)

        coVerify(exactly = 1) { cartService.removeItem(cartId, itemId, principalId) }
    }

    @Test
    fun `setAddress passes the resolved principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val input = SetCartAddressInput(
            type = AddressType.SHIPPING, firstName = "Ada", lastName = "Lovelace",
            address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
        )
        val address = CartAddress(
            id = UUID.random(), cartId = cartId, type = AddressType.SHIPPING, firstName = "Ada",
            lastName = "Lovelace", address1 = "1 Main", city = "SF", state = "CA", country = "US", zip = "94000", phone = "555",
        )
        coEvery { cartService.setAddress(cartId, input, principalId) } returns address

        assertEquals(address, controller.setAddress(auth, source, input))
        coVerify(exactly = 1) { cartService.setAddress(cartId, input, principalId) }
    }

    @Test
    fun `removeAddress passes the resolved principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { cartService.removeAddress(cartId, AddressType.BILLING, principalId) } returns cart()

        controller.removeAddress(auth, source, AddressType.BILLING)

        coVerify(exactly = 1) { cartService.removeAddress(cartId, AddressType.BILLING, principalId) }
    }

    @Test
    fun `setBillingSameAsShipping passes the resolved principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { cartService.setBillingSameAsShipping(cartId, false, principalId) } returns cart()

        controller.setBillingSameAsShipping(auth, source, false)

        coVerify(exactly = 1) { cartService.setBillingSameAsShipping(cartId, false, principalId) }
    }

    @Test
    fun `applyCode passes the resolved principal id to the promotion service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { promotionService.applyToCart(cartId, "SAVE10", principalId) } returns cart()

        controller.applyCode(auth, source, "SAVE10")

        coVerify(exactly = 1) { promotionService.applyToCart(cartId, "SAVE10", principalId) }
    }

    @Test
    fun `removeCode passes the resolved principal id to the promotion service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { promotionService.removeFromCart(cartId, "SAVE10", principalId) } returns cart()

        controller.removeCode(auth, source, "SAVE10")

        coVerify(exactly = 1) { promotionService.removeFromCart(cartId, "SAVE10", principalId) }
    }

    @Test
    fun `setShipping passes the resolved principal id to the shipping service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { shippingService.selectRate(cartId, "tok", principalId) } returns cart()

        controller.setShipping(auth, source, "tok")

        coVerify(exactly = 1) { shippingService.selectRate(cartId, "tok", principalId) }
    }

    @Test
    fun `submit passes the resolved principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val payment = SubmitPaymentInput(token = "tok")
        val result = CartSubmitResult(cart = cart())
        coEvery { cartService.submit(cartId, payment, principalId) } returns result

        assertEquals(result, controller.submit(auth, source, payment))
        coVerify(exactly = 1) { cartService.submit(cartId, payment, principalId) }
    }

    @Test
    fun `complete passes the resolved principal id to the service`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { cartService.complete(cartId, principalId) } returns cart()

        controller.complete(auth, source)

        coVerify(exactly = 1) { cartService.complete(cartId, principalId) }
    }

    @Test
    fun `cancel passes the resolved principal id to the service`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { cartService.cancel(cartId, principalId) } returns cart()

        controller.cancel(auth, source)

        coVerify(exactly = 1) { cartService.cancel(cartId, principalId) }
    }

    @Test
    fun `refundItems passes the resolved principal id to the service`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val items = listOf(RefundItemInput(UUID.random(), 1))
        coEvery { cartService.refundItems(cartId, items, RefundTender.ACCOUNT_CREDIT, null, null, principalId) } returns cart()

        controller.refundItems(auth, source, items, RefundTender.ACCOUNT_CREDIT, null, null)

        coVerify(exactly = 1) { cartService.refundItems(cartId, items, RefundTender.ACCOUNT_CREDIT, null, null, principalId) }
    }

    @Test
    fun `confirmCheck passes the resolved principal id to the service`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val paymentId = UUID.random()
        coEvery { cartService.confirmCheck(cartId, paymentId, null, principalId) } returns cart()

        controller.confirmCheck(auth, source, paymentId, null)

        coVerify(exactly = 1) { cartService.confirmCheck(cartId, paymentId, null, principalId) }
    }

    @Test
    fun `setLocked passes the resolved principal id to the service`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { cartService.setLocked(cartId, false, principalId) } returns cart()

        controller.setLocked(auth, source, false)

        coVerify(exactly = 1) { cartService.setLocked(cartId, false, principalId) }
    }

    @Test
    fun `setItemPrice passes the resolved principal id to the service`() = runTest {
        every { groups.verifyHasGroup(any(), ECOM_ADMINISTRATOR_GROUP) } just Runs
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val itemId = UUID.random()
        coEvery { cartService.setItemPrice(cartId, itemId, Money.of("8.00"), Money.of("7.00"), principalId) } returns cart()

        controller.setItemPrice(auth, source, itemId, Money.of("8.00"), Money.of("7.00"))

        coVerify(exactly = 1) { cartService.setItemPrice(cartId, itemId, Money.of("8.00"), Money.of("7.00"), principalId) }
    }

    // --- cart-not-found error branch of the shared authorize() helper ---

    @Test
    fun `addItem errors when the cart is not found`() = runTest {
        coEvery { cartService.get(cartId) } returns null
        val input = AddCartItemInput(catalogProductId = UUID.random(), quantity = 1)

        assertFailsWith<IllegalStateException> { controller.addItem(auth, source, input) }

        coVerify(exactly = 0) { cartAccess.verify(any(), any()) }
    }
}
