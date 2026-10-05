package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.TransactionType
import bosca.ecommerce.service.PaymentService
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
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Per-payment mutations: each gates on the ecom-admin group, then delegates to the service with the principal. */
@OptIn(ExperimentalUuidApi::class)
class PaymentMutationControllerTest {

    private val paymentService = mockk<PaymentService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = PaymentMutationController(paymentService, groups)

    private val paymentId = UUID.random()
    private val principalId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun authenticated() {
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
    }

    private fun payment() = Payment(
        id = paymentId, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD,
        providerId = UUID.random(), storeId = UUID.random(), amount = Money.of("20.00"),
    )

    @Test
    fun `refund gates on admin then refunds via the service`() = runTest {
        coEvery { paymentService.refund(paymentId, Money.of("5.00"), "partial", null) } returns payment()

        val result = controller.refund(auth, PaymentMutation(paymentId), Money.of("5.00"), "partial")

        assertEquals(paymentId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { paymentService.refund(paymentId, Money.of("5.00"), "partial", null) }
    }

    @Test
    fun `refundToAccountCredit gates on admin then refunds to credit via the service`() = runTest {
        coEvery { paymentService.refundToAccountCredit(paymentId, Money.of("8.00"), "goodwill", null) } returns payment()

        val result = controller.refundToAccountCredit(auth, PaymentMutation(paymentId), Money.of("8.00"), "goodwill")

        assertEquals(paymentId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { paymentService.refundToAccountCredit(paymentId, Money.of("8.00"), "goodwill", null) }
    }

    @Test
    fun `void gates on admin then voids via the service`() = runTest {
        coEvery { paymentService.void(paymentId, "duplicate", null) } returns payment()

        val result = controller.void(auth, PaymentMutation(paymentId), "duplicate")

        assertEquals(paymentId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { paymentService.void(paymentId, "duplicate", null) }
    }

    @Test
    fun `refund delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { paymentService.refund(paymentId, Money.of("5.00"), "partial", principalId) } returns payment()

        controller.refund(auth, PaymentMutation(paymentId), Money.of("5.00"), "partial")

        coVerify(exactly = 1) { paymentService.refund(paymentId, Money.of("5.00"), "partial", principalId) }
    }

    @Test
    fun `refundToAccountCredit delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { paymentService.refundToAccountCredit(paymentId, Money.of("8.00"), "goodwill", principalId) } returns payment()

        controller.refundToAccountCredit(auth, PaymentMutation(paymentId), Money.of("8.00"), "goodwill")

        coVerify(exactly = 1) { paymentService.refundToAccountCredit(paymentId, Money.of("8.00"), "goodwill", principalId) }
    }

    @Test
    fun `void delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { paymentService.void(paymentId, "duplicate", principalId) } returns payment()

        controller.void(auth, PaymentMutation(paymentId), "duplicate")

        coVerify(exactly = 1) { paymentService.void(paymentId, "duplicate", principalId) }
    }
}
