@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.InventoryInput
import bosca.ecommerce.repository.InventoryRepository
import bosca.serialization.UUID
import io.mockk.CapturingSlot
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

/**reservation state-machine math, cross-column guards, and audit (unit-level, mocked repo). */
@OptIn(ExperimentalUuidApi::class)
class InventoryServiceImplTest {

    private val repository = mockk<InventoryRepository>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: InventoryServiceImpl
    private val id = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { io.mockk.mockk(relaxed = true) }
        service = InventoryServiceImpl(repository, auditService)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        bosca.di.ProviderRegistry.clear()
    }

    private fun inventory(quantity: Int, pending: Int = 0, inCart: Int = 0) =
        Inventory(id = id, productId = UUID.random(), fulfillmentCenterId = UUID.random(), sku = "SKU", quantity = quantity, pending = pending, inCart = inCart)

    private fun stubUpdate(): CapturingSlot<Inventory> {
        val slot = slot<Inventory>()
        coEvery { repository.update(capture(slot)) } answers { slot.captured }
        return slot
    }

    @Test
    fun `reserve moves available into inCart`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 1, inCart = 1)
        val updated = stubUpdate()
        val result = service.reserve(id, 2)
        assertEquals(3, updated.captured.inCart)
        assertEquals(3, result.inCart)
    }

    @Test
    fun `reserve fails when available is insufficient`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 3, inCart = 1) // available = 1
        assertFailsWith<IllegalStateException> { service.reserve(id, 2) }
    }

    @Test
    fun `commitToPending moves inCart into pending`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 0, inCart = 2)
        val updated = stubUpdate()
        service.commitToPending(id, 2)
        assertEquals(0, updated.captured.inCart)
        assertEquals(2, updated.captured.pending)
    }

    @Test
    fun `commitToPending fails without enough inCart`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, inCart = 1)
        assertFailsWith<IllegalStateException> { service.commitToPending(id, 2) }
    }

    @Test
    fun `ship decrements quantity and pending and audits`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 2)
        val updated = stubUpdate()
        service.ship(id, 1)
        assertEquals(4, updated.captured.quantity)
        assertEquals(1, updated.captured.pending)
        coVerify(exactly = 1) {
            auditService.record<Inventory>(eq("inventory"), eq(id), eq("shipped"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `releaseInCart clamps to what is held`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, inCart = 1)
        val updated = stubUpdate()
        service.releaseInCart(id, 5)
        assertEquals(0, updated.captured.inCart)
    }

    @Test
    fun `adjust sets quantity and audits`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 3, pending = 1)
        val updated = stubUpdate()
        service.adjust(id, 10, principalId = null)
        assertEquals(10, updated.captured.quantity)
        assertEquals(true, updated.captured.manualQuantity)
        coVerify(exactly = 1) {
            auditService.record<Inventory>(eq("inventory"), eq(id), eq("quantity_adjusted"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `adjust below pending fails`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 3)
        assertFailsWith<IllegalStateException> { service.adjust(id, 1, principalId = null) }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        coEvery { repository.get(id) } returns inventory(quantity = 5)
        assertEquals(5, service.get(id)?.quantity)
    }

    @Test
    fun `get returns null when the row is absent`() = runTest {
        coEvery { repository.get(id) } returns null
        assertEquals(null, service.get(id))
    }

    @Test
    fun `getByProduct delegates to the repository`() = runTest {
        val productId = UUID.random()
        coEvery { repository.getByProduct(productId) } returns listOf(inventory(quantity = 1), inventory(quantity = 2))
        assertEquals(2, service.getByProduct(productId).size)
    }

    @Test
    fun `getByProduct returns empty when no rows exist`() = runTest {
        val productId = UUID.random()
        coEvery { repository.getByProduct(productId) } returns emptyList()
        assertEquals(emptyList(), service.getByProduct(productId))
    }

    @Test
    fun `addInventory persists the new row and audits creation`() = runTest {
        val productId = UUID.random()
        val centerId = UUID.random()
        val slot = slot<Inventory>()
        coEvery { repository.add(capture(slot)) } answers { slot.captured.copy(id = id) }
        val result = service.addInventory(
            InventoryInput(productId = productId, fulfillmentCenterId = centerId, sku = "NEW-SKU", quantity = 7),
            principalId = null,
        )
        assertEquals(productId, slot.captured.productId)
        assertEquals(centerId, slot.captured.fulfillmentCenterId)
        assertEquals("NEW-SKU", slot.captured.sku)
        assertEquals(7, slot.captured.quantity)
        assertEquals(id, result.id)
        coVerify(exactly = 1) {
            auditService.record<Inventory>(eq("inventory"), eq(id), eq("created"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `adjust rejects a negative quantity`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.adjust(id, -1, principalId = null) }
        coVerify(exactly = 0) { repository.getForUpdate(any()) }
    }

    @Test
    fun `adjust throws when the row is absent`() = runTest {
        coEvery { repository.getForUpdate(id) } returns null
        assertFailsWith<IllegalStateException> { service.adjust(id, 5, principalId = null) }
    }

    @Test
    fun `adjust throws when the update returns null`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 3, pending = 1)
        coEvery { repository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.adjust(id, 10, principalId = null) }
    }

    @Test
    fun `reserve rejects a non-positive quantity`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.reserve(id, 0) }
        coVerify(exactly = 0) { repository.getForUpdate(any()) }
    }

    @Test
    fun `reserve throws when the row is absent`() = runTest {
        coEvery { repository.getForUpdate(id) } returns null
        assertFailsWith<IllegalStateException> { service.reserve(id, 1) }
    }

    @Test
    fun `reserve throws when the update returns null`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5)
        coEvery { repository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.reserve(id, 1) }
    }

    @Test
    fun `commitToPending rejects a non-positive quantity`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.commitToPending(id, 0) }
        coVerify(exactly = 0) { repository.getForUpdate(any()) }
    }

    @Test
    fun `commitToPending throws when the row is absent`() = runTest {
        coEvery { repository.getForUpdate(id) } returns null
        assertFailsWith<IllegalStateException> { service.commitToPending(id, 1) }
    }

    @Test
    fun `commitToPending throws when the update returns null`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, inCart = 2)
        coEvery { repository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.commitToPending(id, 2) }
    }

    @Test
    fun `releaseInCart rejects a non-positive quantity`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.releaseInCart(id, 0) }
        coVerify(exactly = 0) { repository.getForUpdate(any()) }
    }

    @Test
    fun `releaseInCart throws when the row is absent`() = runTest {
        coEvery { repository.getForUpdate(id) } returns null
        assertFailsWith<IllegalStateException> { service.releaseInCart(id, 1) }
    }

    @Test
    fun `releaseInCart throws when the update returns null`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, inCart = 2)
        coEvery { repository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.releaseInCart(id, 1) }
    }

    @Test
    fun `releaseInCart releases the requested amount when below what is held`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, inCart = 4)
        val updated = stubUpdate()
        service.releaseInCart(id, 1)
        assertEquals(3, updated.captured.inCart)
    }

    @Test
    fun `releaseInCart with nothing held releases zero and emits no event`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, inCart = 0)
        val updated = stubUpdate()
        service.releaseInCart(id, 3)
        assertEquals(0, updated.captured.inCart)
    }

    @Test
    fun `releasePending clamps to what is pending`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 2)
        val updated = stubUpdate()
        service.releasePending(id, 5)
        assertEquals(0, updated.captured.pending)
    }

    @Test
    fun `releasePending releases the requested amount when below what is pending`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 4)
        val updated = stubUpdate()
        service.releasePending(id, 1)
        assertEquals(3, updated.captured.pending)
    }

    @Test
    fun `releasePending with nothing pending releases zero and emits no event`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 0)
        val updated = stubUpdate()
        service.releasePending(id, 3)
        assertEquals(0, updated.captured.pending)
    }

    @Test
    fun `releasePending rejects a non-positive quantity`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.releasePending(id, 0) }
        coVerify(exactly = 0) { repository.getForUpdate(any()) }
    }

    @Test
    fun `releasePending throws when the row is absent`() = runTest {
        coEvery { repository.getForUpdate(id) } returns null
        assertFailsWith<IllegalStateException> { service.releasePending(id, 1) }
    }

    @Test
    fun `releasePending throws when the update returns null`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 2)
        coEvery { repository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.releasePending(id, 1) }
    }

    @Test
    fun `ship rejects a non-positive quantity`() = runTest {
        assertFailsWith<IllegalArgumentException> { service.ship(id, 0, principalId = null) }
        coVerify(exactly = 0) { repository.getForUpdate(any()) }
    }

    @Test
    fun `ship throws when the row is absent`() = runTest {
        coEvery { repository.getForUpdate(id) } returns null
        assertFailsWith<IllegalStateException> { service.ship(id, 1, principalId = null) }
    }

    @Test
    fun `ship fails when more is requested than pending`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 1)
        assertFailsWith<IllegalStateException> { service.ship(id, 2, principalId = null) }
    }

    @Test
    fun `ship fails when more is requested than on hand`() = runTest {
        // pending guard passes (pending >= quantity) but on-hand quantity is short.
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 1, pending = 3)
        assertFailsWith<IllegalStateException> { service.ship(id, 2, principalId = null) }
    }

    @Test
    fun `ship throws when the update returns null`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 5, pending = 2)
        coEvery { repository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.ship(id, 1, principalId = null) }
    }

    @Test
    fun `getByCenter delegates to the repository`() = runTest {
        val centerId = UUID.random()
        coEvery { repository.getByCenter(centerId) } returns listOf(inventory(quantity = 1))
        assertEquals(1, service.getByCenter(centerId).size)
    }

    @Test
    fun `syncQuantity sets quantity without pinning manual and audits quantity_synced`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 3, pending = 1)
        val updated = stubUpdate()
        val result = service.syncQuantity(id, 10, principalId = null)
        assertEquals(10, updated.captured.quantity)
        assertEquals(false, updated.captured.manualQuantity) // sync never pins the row manual
        assertEquals(10, result?.quantity)
        coVerify(exactly = 1) {
            auditService.record<Inventory>(eq("inventory"), eq(id), eq("quantity_synced"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `syncQuantity is a no-op for a manually-pinned row`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 3).copy(manualQuantity = true)
        assertEquals(null, service.syncQuantity(id, 10, principalId = null))
        coVerify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `syncQuantity is a no-op when the quantity is unchanged`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 10)
        assertEquals(null, service.syncQuantity(id, 10, principalId = null))
        coVerify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `syncQuantity is a no-op when it would drop below pending`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 10, pending = 5)
        assertEquals(null, service.syncQuantity(id, 3, principalId = null))
        coVerify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `syncQuantity is a no-op for a negative reading`() = runTest {
        assertEquals(null, service.syncQuantity(id, -1, principalId = null))
        coVerify(exactly = 0) { repository.getForUpdate(any()) }
    }

    @Test
    fun `syncQuantity is a no-op when the row is absent`() = runTest {
        coEvery { repository.getForUpdate(id) } returns null
        assertEquals(null, service.syncQuantity(id, 10, principalId = null))
    }

    @Test
    fun `syncQuantity is a no-op when the update returns null`() = runTest {
        coEvery { repository.getForUpdate(id) } returns inventory(quantity = 3)
        coEvery { repository.update(any()) } returns null
        assertEquals(null, service.syncQuantity(id, 10, principalId = null))
    }
}
