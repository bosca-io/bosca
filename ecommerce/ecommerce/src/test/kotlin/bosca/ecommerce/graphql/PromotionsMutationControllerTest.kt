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
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Promotion admin CRUD namespace: add gates on admin and delegates; the accessor scopes to one promotion. */
@OptIn(ExperimentalUuidApi::class)
class PromotionsMutationControllerTest {

    private val promotionService = mockk<PromotionService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = PromotionsMutationController(promotionService, groups)

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
    fun `add gates on admin then creates via the service`() = runTest {
        coEvery { promotionService.create(any(), null) } returns promotion()

        assertEquals(promotionId, controller.add(auth, input()).id)

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { promotionService.create(any(), null) }
    }

    @Test
    fun `add forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { promotionService.create(any(), principalId) } returns promotion()

        controller.add(auth, input())

        coVerify(exactly = 1) { promotionService.create(any(), principalId) }
    }

    @Test
    fun `promotion accessor returns the id-scoped mutation namespace`() {
        assertEquals(promotionId, controller.promotion(promotionId).id)
    }
}
