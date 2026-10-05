package bosca.ecommerce.graphql

import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.ProviderInput
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShippingProviderInput
import bosca.ecommerce.service.ProviderService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Provider registration mutations: each gates on the ecom-admin group, then delegates with the principal. */
@OptIn(ExperimentalUuidApi::class)
class ProvidersMutationControllerTest {

    private val providerService = mockk<ProviderService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = ProvidersMutationController(providerService, groups)

    private val companyId = UUID.random()
    private val paymentId = UUID.random()
    private val shippingId = UUID.random()
    private val principalId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun authenticated() {
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
    }

    private fun paymentInput() = ProviderInput(companyId, "Pay", "test", EmptyProviderConfiguration)
    private fun shippingInput() = ShippingProviderInput(companyId, "Ship", "ship", "test", EmptyProviderConfiguration)
    private fun paymentProvider() = PaymentProvider(id = paymentId, companyId = companyId, name = "Pay", providerKey = "test")
    private fun shippingProvider() = ShippingProvider(id = shippingId, companyId = companyId, name = "Ship", key = "ship", providerKey = "test")

    @Test
    fun `addPayment gates on admin then creates via the service`() = runTest {
        coEvery { providerService.addPaymentProvider(paymentInput(), null) } returns paymentProvider()

        assertEquals(paymentId, controller.addPayment(auth, paymentInput()).id)

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { providerService.addPaymentProvider(paymentInput(), null) }
    }

    @Test
    fun `addShipping gates on admin then creates via the service`() = runTest {
        coEvery { providerService.addShippingProvider(shippingInput(), null) } returns shippingProvider()

        assertEquals(shippingId, controller.addShipping(auth, shippingInput()).id)

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { providerService.addShippingProvider(shippingInput(), null) }
    }

    @Test
    fun `editPayment gates on admin then edits via the service`() = runTest {
        coEvery { providerService.editPaymentProvider(paymentId, paymentInput(), null) } returns paymentProvider()

        assertEquals(paymentId, controller.editPayment(auth, paymentId, paymentInput()).id)

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { providerService.editPaymentProvider(paymentId, paymentInput(), null) }
    }

    @Test
    fun `editShipping gates on admin then edits via the service`() = runTest {
        coEvery { providerService.editShippingProvider(shippingId, shippingInput(), null) } returns shippingProvider()

        assertEquals(shippingId, controller.editShipping(auth, shippingId, shippingInput()).id)

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { providerService.editShippingProvider(shippingId, shippingInput(), null) }
    }

    @Test
    fun `deletePayment gates on admin then deletes via the service`() = runTest {
        coEvery { providerService.deletePaymentProvider(paymentId, null) } returns true

        assertTrue(controller.deletePayment(auth, paymentId))

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { providerService.deletePaymentProvider(paymentId, null) }
    }

    @Test
    fun `deleteShipping gates on admin then deletes via the service`() = runTest {
        coEvery { providerService.deleteShippingProvider(shippingId, null) } returns true

        assertTrue(controller.deleteShipping(auth, shippingId))

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { providerService.deleteShippingProvider(shippingId, null) }
    }

    @Test
    fun `addPayment delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { providerService.addPaymentProvider(paymentInput(), principalId) } returns paymentProvider()

        assertEquals(paymentId, controller.addPayment(auth, paymentInput()).id)

        coVerify(exactly = 1) { providerService.addPaymentProvider(paymentInput(), principalId) }
    }

    @Test
    fun `addShipping delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { providerService.addShippingProvider(shippingInput(), principalId) } returns shippingProvider()

        assertEquals(shippingId, controller.addShipping(auth, shippingInput()).id)

        coVerify(exactly = 1) { providerService.addShippingProvider(shippingInput(), principalId) }
    }

    @Test
    fun `editPayment delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { providerService.editPaymentProvider(paymentId, paymentInput(), principalId) } returns paymentProvider()

        assertEquals(paymentId, controller.editPayment(auth, paymentId, paymentInput()).id)

        coVerify(exactly = 1) { providerService.editPaymentProvider(paymentId, paymentInput(), principalId) }
    }

    @Test
    fun `editShipping delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { providerService.editShippingProvider(shippingId, shippingInput(), principalId) } returns shippingProvider()

        assertEquals(shippingId, controller.editShipping(auth, shippingId, shippingInput()).id)

        coVerify(exactly = 1) { providerService.editShippingProvider(shippingId, shippingInput(), principalId) }
    }

    @Test
    fun `deletePayment delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { providerService.deletePaymentProvider(paymentId, principalId) } returns true

        assertTrue(controller.deletePayment(auth, paymentId))

        coVerify(exactly = 1) { providerService.deletePaymentProvider(paymentId, principalId) }
    }

    @Test
    fun `deleteShipping delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { providerService.deleteShippingProvider(shippingId, principalId) } returns true

        assertTrue(controller.deleteShipping(auth, shippingId))

        coVerify(exactly = 1) { providerService.deleteShippingProvider(shippingId, principalId) }
    }
}
