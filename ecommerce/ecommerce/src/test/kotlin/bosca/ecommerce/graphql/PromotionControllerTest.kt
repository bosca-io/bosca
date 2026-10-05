package bosca.ecommerce.graphql

import bosca.ecommerce.model.AmountOffCartRule
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionType
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.service.StoreService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Promotion field wiring: scalar resolvers passthrough; [store] resolves via the service. */
@OptIn(ExperimentalUuidApi::class)
class PromotionControllerTest {

    private val storeService = mockk<StoreService>()
    private val controller = PromotionController(storeService)

    private val storeId = UUID.random()
    private val rule = AmountOffCartRule(Money.of("5.00"))
    private val promotion = Promotion(
        id = UUID.random(),
        storeId = storeId,
        code = "SAVE",
        name = "Save",
        type = PromotionType.CART,
        rule = rule,
        starts = OffsetDateTime.now().minusSeconds(60),
        ends = OffsetDateTime.now().plusSeconds(3600),
    )

    private fun store() = Store(
        id = storeId, identifier = "s", name = "S", companyId = UUID.random(), catalogId = UUID.random(),
        type = StoreType.VIRTUAL, paymentProviderId = UUID.random(), shippingCatalogProductId = UUID.random(),
    )

    @Test
    fun `scalar fields resolve from the source promotion`() {
        assertEquals(promotion.id, controller.id(promotion))
        assertEquals("SAVE", controller.code(promotion))
        assertEquals("Save", controller.name(promotion))
        assertEquals(PromotionType.CART, controller.type(promotion))
        assertEquals(rule, controller.rule(promotion))
        assertEquals(promotion.starts, controller.starts(promotion))
        assertEquals(promotion.ends, controller.ends(promotion))
        assertEquals(promotion.created, controller.created(promotion))
        assertEquals(promotion.modified, controller.modified(promotion))
    }

    @Test
    fun `store resolves via the store service`() = runTest {
        coEvery { storeService.get(storeId) } returns store()
        assertEquals(storeId, controller.store(promotion).id)
    }

    @Test
    fun `store errors when the store is missing`() = runTest {
        coEvery { storeService.get(storeId) } returns null
        assertFailsWith<IllegalStateException> { controller.store(promotion) }
    }
}
