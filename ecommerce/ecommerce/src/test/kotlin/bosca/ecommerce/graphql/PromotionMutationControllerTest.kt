package bosca.ecommerce.graphql

import bosca.ecommerce.model.AmountOffCartRule
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionInput
import bosca.ecommerce.model.PromotionType
import bosca.ecommerce.service.PromotionService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
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

/** Per-promotion mutations: each gates on the ecom-admin group, then delegates with the principal. */
@OptIn(ExperimentalUuidApi::class)
class PromotionMutationControllerTest {

    private val promotionService = mockk<PromotionService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = PromotionMutationController(promotionService, groups)

    private val promotionId = UUID.random()
    private val storeId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = PromotionInput(
        storeId = storeId, code = "SAVE", name = "Save", type = PromotionType.CART,
        rule = AmountOffCartRule(Money.of("5.00")),
        starts = OffsetDateTime.now(), ends = OffsetDateTime.now().plusSeconds(60),
    )

    private fun promotion() = Promotion(
        id = promotionId, storeId = storeId, code = "SAVE", name = "Save", type = PromotionType.CART,
        rule = AmountOffCartRule(Money.of("5.00")),
        starts = OffsetDateTime.now(), ends = OffsetDateTime.now().plusSeconds(60),
    )

    @Test
    fun `edit gates on admin then edits via the service`() = runTest {
        coEvery { promotionService.edit(promotionId, any(), null) } returns promotion()

        assertEquals(promotionId, controller.edit(auth, PromotionMutation(promotionId), input()).id)

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { promotionService.edit(promotionId, any(), null) }
    }

    @Test
    fun `delete gates on admin then deletes via the service and returns true`() = runTest {
        coEvery { promotionService.delete(promotionId, null) } returns Unit

        assertTrue(controller.delete(auth, PromotionMutation(promotionId)))

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { promotionService.delete(promotionId, null) }
    }

    @Test
    fun `edit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { promotionService.edit(promotionId, any(), principalId) } returns promotion()

        controller.edit(auth, PromotionMutation(promotionId), input())

        coVerify(exactly = 1) { promotionService.edit(promotionId, any(), principalId) }
    }

    @Test
    fun `delete forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { promotionService.delete(promotionId, principalId) } returns Unit

        controller.delete(auth, PromotionMutation(promotionId))

        coVerify(exactly = 1) { promotionService.delete(promotionId, principalId) }
    }
}
