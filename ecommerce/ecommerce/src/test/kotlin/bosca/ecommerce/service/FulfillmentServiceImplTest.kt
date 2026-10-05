@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.FulfillmentCenterInput
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.repository.FulfillmentCenterRepository
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

/**fulfillment-center orchestration — reads delegate; add/edit are audited; missing rows fail. */
@OptIn(ExperimentalUuidApi::class)
class FulfillmentServiceImplTest {

    private val fulfillmentCenterRepository = mockk<FulfillmentCenterRepository>()
    private val inventoryService = mockk<InventoryService>()
    private val connector = mockk<InventoryConnector>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: FulfillmentServiceImpl

    private val companyId = UUID.random()
    private val centerId = UUID.random()
    private val shippingProviderId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<InventoryConnector>(name = "wms", singleton = true) { connector }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        service = FulfillmentServiceImpl(fulfillmentCenterRepository, inventoryService, auditService)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun input() = FulfillmentCenterInput(
        companyId = companyId, name = "West DC", connectorKey = "manual", shippingProviderId = shippingProviderId,
        address1 = "1 Dock Rd", city = "Reno", state = "NV", country = "US", zip = "89500",
    )

    private fun center() = FulfillmentCenter(
        id = centerId, companyId = companyId, name = "West DC", connectorKey = "manual", shippingProviderId = shippingProviderId,
        address1 = "1 Dock Rd", city = "Reno", state = "NV", country = "US", zip = "89500",
    )

    /** A syncable center (connector "wms") that has never synced, so it is always due. */
    private fun syncCenter(connectorKey: String = "wms", lastSynced: OffsetDateTime? = null, intervalSeconds: Int = 60) =
        center().copy(connectorKey = connectorKey, lastSynced = lastSynced, syncIntervalSeconds = intervalSeconds)

    private fun inventory(sku: String, manual: Boolean = false) = Inventory(
        id = UUID.random(), productId = UUID.random(), fulfillmentCenterId = centerId, sku = sku, manualQuantity = manual,
    )

    @Test
    fun `getCenter and getCentersByCompany delegate to the repository`() = runTest {
        coEvery { fulfillmentCenterRepository.get(centerId) } returns center()
        coEvery { fulfillmentCenterRepository.getByCompany(companyId) } returns listOf(center())

        assertEquals(centerId, service.getCenter(centerId)?.id)
        assertEquals(1, service.getCentersByCompany(companyId).size)
    }

    @Test
    fun `addCenter persists the center from the input and audits created`() = runTest {
        val added = slot<FulfillmentCenter>()
        coEvery { fulfillmentCenterRepository.add(capture(added)) } answers { added.captured.copy(id = centerId) }

        val result = service.addCenter(input(), principalId = null)

        assertEquals(centerId, result.id)
        assertEquals("West DC", added.captured.name)
        assertEquals(shippingProviderId, added.captured.shippingProviderId)
        coVerify(exactly = 1) {
            auditService.record<FulfillmentCenter>(entityType = "fulfillment_center", entityId = centerId, action = "created", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = any(), details = any())
        }
    }

    @Test
    fun `editCenter updates the existing center and audits updated`() = runTest {
        coEvery { fulfillmentCenterRepository.get(centerId) } returns center()
        val updated = slot<FulfillmentCenter>()
        coEvery { fulfillmentCenterRepository.update(capture(updated)) } answers { updated.captured }

        val result = service.editCenter(centerId, input().copy(name = "East DC", city = "Atlanta"), principalId = null)

        assertEquals("East DC", result.name)
        assertEquals("Atlanta", updated.captured.city)
        coVerify(exactly = 1) {
            auditService.record<FulfillmentCenter>(entityType = "fulfillment_center", entityId = centerId, action = "updated", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = any(), details = any())
        }
    }

    @Test
    fun `editCenter throws when the center is absent`() = runTest {
        coEvery { fulfillmentCenterRepository.get(centerId) } returns null
        assertFailsWith<IllegalStateException> { service.editCenter(centerId, input(), principalId = null) }
        coVerify(exactly = 0) { fulfillmentCenterRepository.update(any()) }
    }

    @Test
    fun `editCenter throws when the update finds no row`() = runTest {
        coEvery { fulfillmentCenterRepository.get(centerId) } returns center()
        coEvery { fulfillmentCenterRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.editCenter(centerId, input(), principalId = null) }
    }

    @Test
    fun `syncInventory pulls connector quantities for a due center, skips manual rows, and stamps last_synced`() = runTest {
        val synced = inventory("A")
        val manual = inventory("B", manual = true)
        coEvery { fulfillmentCenterRepository.getSyncable() } returns listOf(syncCenter())
        coEvery { inventoryService.getByCenter(centerId) } returns listOf(synced, manual)
        coEvery { connector.getQuantity(any(), "A") } returns 50
        coEvery { inventoryService.syncQuantity(synced.id, 50, null) } returns synced.copy(quantity = 50)
        coEvery { fulfillmentCenterRepository.touchSynced(centerId) } returns 1

        assertEquals(1, service.syncInventory())

        coVerify(exactly = 0) { connector.getQuantity(any(), "B") } // manual row never hits the connector
        coVerify(exactly = 1) { fulfillmentCenterRepository.touchSynced(centerId) }
    }

    @Test
    fun `syncInventory syncs a center whose interval has elapsed`() = runTest {
        val synced = inventory("A")
        coEvery { fulfillmentCenterRepository.getSyncable() } returns
            listOf(syncCenter(lastSynced = OffsetDateTime.now().minusSeconds(120), intervalSeconds = 60))
        coEvery { inventoryService.getByCenter(centerId) } returns listOf(synced)
        coEvery { connector.getQuantity(any(), "A") } returns 7
        coEvery { inventoryService.syncQuantity(synced.id, 7, null) } returns synced.copy(quantity = 7)
        coEvery { fulfillmentCenterRepository.touchSynced(centerId) } returns 1

        assertEquals(1, service.syncInventory())
    }

    @Test
    fun `syncInventory skips a center whose interval has not elapsed`() = runTest {
        coEvery { fulfillmentCenterRepository.getSyncable() } returns
            listOf(syncCenter(lastSynced = OffsetDateTime.now(), intervalSeconds = 3600))

        assertEquals(0, service.syncInventory())

        coVerify(exactly = 0) { inventoryService.getByCenter(any()) }
        coVerify(exactly = 0) { fulfillmentCenterRepository.touchSynced(any()) }
    }

    @Test
    fun `syncInventory skips a center pointed at an unregistered connector`() = runTest {
        coEvery { fulfillmentCenterRepository.getSyncable() } returns listOf(syncCenter(connectorKey = "absent"))

        assertEquals(0, service.syncInventory())

        coVerify(exactly = 0) { inventoryService.getByCenter(any()) }
        coVerify(exactly = 0) { fulfillmentCenterRepository.touchSynced(any()) }
    }

    @Test
    fun `syncInventory does not count a row with no connector reading or an unchanged quantity`() = runTest {
        val noReading = inventory("A")
        val unchanged = inventory("B")
        coEvery { fulfillmentCenterRepository.getSyncable() } returns listOf(syncCenter())
        coEvery { inventoryService.getByCenter(centerId) } returns listOf(noReading, unchanged)
        coEvery { connector.getQuantity(any(), "A") } returns null // no reading -> skipped
        coEvery { connector.getQuantity(any(), "B") } returns 3
        coEvery { inventoryService.syncQuantity(unchanged.id, 3, null) } returns null // no-op (manual/below-pending/unchanged)
        coEvery { fulfillmentCenterRepository.touchSynced(centerId) } returns 1

        assertEquals(0, service.syncInventory())

        coVerify(exactly = 0) { inventoryService.syncQuantity(noReading.id, any(), any()) }
        coVerify(exactly = 1) { fulfillmentCenterRepository.touchSynced(centerId) }
    }
}
