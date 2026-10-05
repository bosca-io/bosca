@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.ecommerce.model.AmountOffCartRule
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.FrequencyLimit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionAvailability
import bosca.ecommerce.model.PromotionInput
import bosca.ecommerce.model.PromotionRedemption
import bosca.ecommerce.model.PromotionType
import bosca.ecommerce.repository.PromotionAvailabilityRepository
import bosca.ecommerce.repository.PromotionRedemptionRepository
import bosca.ecommerce.repository.PromotionRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**promotion application orchestration — account requirement, sold-out guard, reprice. */
@OptIn(ExperimentalUuidApi::class)
class PromotionServiceImplTest {

    private val promotionRepository = mockk<PromotionRepository>(relaxUnitFun = true)
    private val availabilityRepository = mockk<PromotionAvailabilityRepository>()
    private val redemptionRepository = mockk<PromotionRedemptionRepository>(relaxUnitFun = true)
    private val cartService = mockk<CartService>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: PromotionServiceImpl

    private val cartId = UUID.random()
    private val storeId = UUID.random()
    private val accountId = UUID.random()
    private val promotionId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { io.mockk.mockk(relaxed = true) }
        service = PromotionServiceImpl(promotionRepository, availabilityRepository, redemptionRepository, cartService, auditService)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        bosca.di.ProviderRegistry.clear()
    }

    private fun cart(accountId: UUID?) = Cart(id = cartId, companyId = UUID.random(), storeId = storeId, accountId = accountId, expires = OffsetDateTime.now().plusSeconds(3600))

    private fun activePromotion() = Promotion(
        id = promotionId, storeId = storeId, code = "SAVE", name = "Save", type = PromotionType.CART,
        rule = AmountOffCartRule(Money.of("5.00")), starts = OffsetDateTime.now().minusSeconds(60), ends = OffsetDateTime.now().plusSeconds(3600),
    )

    @Test
    fun `create persists availability when a quantity is set`() = runTest {
        coEvery { promotionRepository.add(any()) } answers { firstArg<Promotion>().copy(id = promotionId) }
        coEvery { availabilityRepository.add(any()) } answers { firstArg() }
        service.create(
            PromotionInput(storeId = storeId, code = "SAVE", name = "Save", type = PromotionType.CART, rule = AmountOffCartRule(Money.of("5.00")), starts = OffsetDateTime.now(), ends = OffsetDateTime.now().plusSeconds(60), quantity = 100),
            principalId = null,
        )
        coVerify(exactly = 1) { availabilityRepository.add(match { it.quantity == 100L }) }
    }

    @Test
    fun `applyToCart requires an account on the cart`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = null)
        assertFailsWith<IllegalStateException> { service.applyToCart(cartId, "SAVE", principalId = null) }
    }

    @Test
    fun `applyToCart errors when the promotion is fully redeemed`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns activePromotion()
        coEvery { availabilityRepository.get(promotionId) } returns PromotionAvailability(promotionId, quantity = 1, redeemed = 1)
        coEvery { availabilityRepository.redeem(promotionId) } returns null // sold out

        assertFailsWith<IllegalStateException> { service.applyToCart(cartId, "SAVE", principalId = null) }
        coVerify(exactly = 0) { redemptionRepository.add(any()) }
    }

    @Test
    fun `applyToCart redeems, records, and reprices`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns activePromotion()
        coEvery { availabilityRepository.get(promotionId) } returns PromotionAvailability(promotionId, quantity = 10, redeemed = 0)
        coEvery { availabilityRepository.redeem(promotionId) } returns PromotionAvailability(promotionId, quantity = 10, redeemed = 1)
        coEvery { redemptionRepository.add(any()) } answers { firstArg<PromotionRedemption>().copy(id = UUID.random()) }
        coEvery { cartService.reprice(cartId) } returns cart(accountId = accountId)

        service.applyToCart(cartId, "SAVE", principalId = null)

        coVerify(exactly = 1) { availabilityRepository.redeem(promotionId) }
        coVerify(exactly = 1) { redemptionRepository.add(match { it.promotionId == promotionId && it.accountId == accountId && it.cartId == cartId }) }
        coVerify(exactly = 1) { cartService.reprice(cartId) }
    }

    @Test
    fun `applyToCart rejects a second redemption by the same account within the limit window`() = runTest {
        val promotion = activePromotion().copy(perAccountLimit = 1, frequencyLimit = FrequencyLimit.WEEKLY)
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns promotion
        coEvery { redemptionRepository.getByPromotionAndAccountSince(eq(promotionId), eq(accountId), any()) } returns
            listOf(PromotionRedemption(id = UUID.random(), promotionId = promotionId, accountId = accountId, cartId = cartId))

        assertFailsWith<IllegalStateException> { service.applyToCart(cartId, "SAVE", principalId = null) }
        coVerify(exactly = 0) { redemptionRepository.add(any()) }
        coVerify(exactly = 0) { availabilityRepository.redeem(any()) }
    }

    @Test
    fun `applyToCart allows a redemption under the per-account limit`() = runTest {
        val promotion = activePromotion().copy(perAccountLimit = 2, frequencyLimit = FrequencyLimit.MONTHLY)
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns promotion
        coEvery { redemptionRepository.getByPromotionAndAccountSince(eq(promotionId), eq(accountId), any()) } returns
            listOf(PromotionRedemption(id = UUID.random(), promotionId = promotionId, accountId = accountId, cartId = cartId))
        coEvery { availabilityRepository.get(promotionId) } returns PromotionAvailability(promotionId, quantity = 10, redeemed = 0)
        coEvery { availabilityRepository.redeem(promotionId) } returns PromotionAvailability(promotionId, quantity = 10, redeemed = 1)
        coEvery { redemptionRepository.add(any()) } answers { firstArg<PromotionRedemption>().copy(id = UUID.random()) }
        coEvery { cartService.reprice(cartId) } returns cart(accountId = accountId)

        service.applyToCart(cartId, "SAVE", principalId = null)

        coVerify(exactly = 1) { redemptionRepository.add(match { it.promotionId == promotionId && it.accountId == accountId }) }
        coVerify(exactly = 1) { cartService.reprice(cartId) }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        coEvery { promotionRepository.get(promotionId) } returns activePromotion()
        assertEquals(promotionId, service.get(promotionId)?.id)
    }

    @Test
    fun `get returns null when absent`() = runTest {
        coEvery { promotionRepository.get(promotionId) } returns null
        assertEquals(null, service.get(promotionId))
    }

    @Test
    fun `getByCode delegates to the repository`() = runTest {
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns activePromotion()
        assertEquals(promotionId, service.getByCode(storeId, "SAVE")?.id)
    }

    @Test
    fun `getByStore delegates to the repository`() = runTest {
        coEvery { promotionRepository.getByStore(storeId, 0, 10) } returns listOf(activePromotion())
        assertEquals(1, service.getByStore(storeId, 0, 10).size)
    }

    @Test
    fun `getByStore returns empty when there are none`() = runTest {
        coEvery { promotionRepository.getByStore(storeId, 0, 10) } returns emptyList()
        assertEquals(emptyList(), service.getByStore(storeId, 0, 10))
    }

    @Test
    fun `create skips availability when no quantity is set`() = runTest {
        coEvery { promotionRepository.add(any()) } answers { firstArg<Promotion>().copy(id = promotionId) }
        service.create(
            PromotionInput(storeId = storeId, code = "SAVE", name = "Save", type = PromotionType.CART, rule = AmountOffCartRule(Money.of("5.00")), starts = OffsetDateTime.now(), ends = OffsetDateTime.now().plusSeconds(60), quantity = null),
            principalId = null,
        )
        coVerify(exactly = 0) { availabilityRepository.add(any()) }
        coVerify(exactly = 1) { auditService.record<Promotion>(eq("promotion"), eq(promotionId), eq("created"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `edit updates the promotion and audits with the prior state`() = runTest {
        val existing = activePromotion()
        coEvery { promotionRepository.get(promotionId) } returns existing
        val slot = slot<Promotion>()
        coEvery { promotionRepository.update(capture(slot)) } answers { slot.captured }

        service.edit(
            promotionId,
            PromotionInput(storeId = storeId, code = "SAVE2", name = "Save More", type = PromotionType.CART, rule = AmountOffCartRule(Money.of("9.00")), starts = existing.starts, ends = existing.ends),
            principalId = null,
        )

        assertEquals("SAVE2", slot.captured.code)
        assertEquals("Save More", slot.captured.name)
        coVerify(exactly = 1) { auditService.record<Promotion>(eq("promotion"), eq(promotionId), eq("updated"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `edit throws when the promotion is absent`() = runTest {
        coEvery { promotionRepository.get(promotionId) } returns null
        assertFailsWith<IllegalStateException> {
            service.edit(promotionId, PromotionInput(storeId = storeId, code = "X", name = "X", type = PromotionType.CART, rule = AmountOffCartRule(Money.of("1.00")), starts = OffsetDateTime.now(), ends = OffsetDateTime.now().plusSeconds(60)), principalId = null)
        }
    }

    @Test
    fun `edit throws when the update returns null`() = runTest {
        coEvery { promotionRepository.get(promotionId) } returns activePromotion()
        coEvery { promotionRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> {
            service.edit(promotionId, PromotionInput(storeId = storeId, code = "X", name = "X", type = PromotionType.CART, rule = AmountOffCartRule(Money.of("1.00")), starts = OffsetDateTime.now(), ends = OffsetDateTime.now().plusSeconds(60)), principalId = null)
        }
    }

    @Test
    fun `delete soft-deletes and audits`() = runTest {
        coEvery { promotionRepository.get(promotionId) } returns activePromotion()
        service.delete(promotionId, principalId = null)
        coVerify(exactly = 1) { promotionRepository.softDelete(promotionId) }
        coVerify(exactly = 1) { auditService.record<Promotion>(eq("promotion"), eq(promotionId), eq("deleted"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `delete throws when the promotion is absent`() = runTest {
        coEvery { promotionRepository.get(promotionId) } returns null
        assertFailsWith<IllegalStateException> { service.delete(promotionId, principalId = null) }
        coVerify(exactly = 0) { promotionRepository.softDelete(any()) }
    }

    @Test
    fun `applyToCart throws when the cart is absent`() = runTest {
        coEvery { cartService.get(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.applyToCart(cartId, "SAVE", principalId = null) }
    }

    @Test
    fun `applyToCart throws when the promotion code is unknown`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns null
        assertFailsWith<IllegalStateException> { service.applyToCart(cartId, "SAVE", principalId = null) }
    }

    @Test
    fun `applyToCart rejects a promotion that has not started`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns activePromotion().copy(
            starts = OffsetDateTime.now().plusSeconds(60), ends = OffsetDateTime.now().plusSeconds(3600),
        )
        assertFailsWith<IllegalStateException> { service.applyToCart(cartId, "SAVE", principalId = null) }
    }

    @Test
    fun `applyToCart rejects a promotion that has ended`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns activePromotion().copy(
            starts = OffsetDateTime.now().minusSeconds(3600), ends = OffsetDateTime.now().minusSeconds(60),
        )
        assertFailsWith<IllegalStateException> { service.applyToCart(cartId, "SAVE", principalId = null) }
    }

    @Test
    fun `applyToCart skips the redeem guard for an uncapped promotion`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns activePromotion()
        coEvery { availabilityRepository.get(promotionId) } returns null // no cap
        coEvery { redemptionRepository.add(any()) } answers { firstArg<PromotionRedemption>().copy(id = UUID.random()) }
        coEvery { cartService.reprice(cartId) } returns cart(accountId = accountId)

        service.applyToCart(cartId, "SAVE", principalId = null)

        coVerify(exactly = 0) { availabilityRepository.redeem(any()) }
        coVerify(exactly = 1) { redemptionRepository.add(any()) }
        coVerify(exactly = 1) { cartService.reprice(cartId) }
    }

    @Test
    fun `removeFromCart deletes the redemption and reprices for an uncapped promotion`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns activePromotion()
        coEvery { availabilityRepository.get(promotionId) } returns null // no cap -> no release
        coEvery { cartService.reprice(cartId) } returns cart(accountId = accountId)

        service.removeFromCart(cartId, "SAVE", principalId = null)

        coVerify(exactly = 1) { redemptionRepository.deleteByCartAndPromotion(cartId, promotionId) }
        coVerify(exactly = 0) { availabilityRepository.release(any()) }
        coVerify(exactly = 1) { cartService.reprice(cartId) }
    }

    @Test
    fun `removeFromCart releases the availability for a capped promotion`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns activePromotion()
        coEvery { availabilityRepository.get(promotionId) } returns PromotionAvailability(promotionId, quantity = 10, redeemed = 1)
        coEvery { availabilityRepository.release(promotionId) } returns PromotionAvailability(promotionId, quantity = 10, redeemed = 0)
        coEvery { cartService.reprice(cartId) } returns cart(accountId = accountId)

        service.removeFromCart(cartId, "SAVE", principalId = null)

        coVerify(exactly = 1) { availabilityRepository.release(promotionId) }
        coVerify(exactly = 1) { redemptionRepository.deleteByCartAndPromotion(cartId, promotionId) }
    }

    @Test
    fun `removeFromCart throws when the cart is absent`() = runTest {
        coEvery { cartService.get(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.removeFromCart(cartId, "SAVE", principalId = null) }
    }

    @Test
    fun `removeFromCart throws when the promotion code is unknown`() = runTest {
        coEvery { cartService.get(cartId) } returns cart(accountId = accountId)
        coEvery { promotionRepository.getByCode(storeId, "SAVE") } returns null
        assertFailsWith<IllegalStateException> { service.removeFromCart(cartId, "SAVE", principalId = null) }
    }
}
