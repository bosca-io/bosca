@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.ecommerce.model.Container
import bosca.ecommerce.model.ContainerInput
import bosca.ecommerce.repository.ContainerRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**container (box catalog) CRUD + audit. */
@OptIn(ExperimentalUuidApi::class)
class ContainerServiceImplTest {

    private val containerRepository = mockk<ContainerRepository>(relaxUnitFun = true)
    private val shipmentService = mockk<ShipmentService>(relaxed = true)
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: ContainerServiceImpl

    private val companyId = UUID.random()
    private val containerId = UUID.random()

    private fun input() = ContainerInput(
        companyId = companyId, name = "Small Box",
        width = 5.0, height = 5.0, length = 5.0, weight = 0.2,
        supportedWidth = 4.5, supportedHeight = 4.5, supportedLength = 4.5, supportedWeight = 10.0,
    )

    private fun container() = Container(
        id = containerId, companyId = companyId, name = "Small Box",
        width = 5.0, height = 5.0, length = 5.0, weight = 0.2,
        supportedWidth = 4.5, supportedHeight = 4.5, supportedLength = 4.5, supportedWeight = 10.0,
    )

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        service = ContainerServiceImpl(containerRepository, shipmentService, auditService)
    }

    @AfterTest
    fun teardown() = unmockkAll()

    @Test
    fun `add inserts the container from the input and audits created`() = runTest {
        val added = slot<Container>()
        coEvery { containerRepository.add(capture(added)) } answers { firstArg<Container>().copy(id = containerId) }

        val result = service.add(input(), principalId = null)

        assertEquals(companyId, added.captured.companyId)
        assertEquals("Small Box", added.captured.name)
        assertEquals(4.5, added.captured.supportedWidth)
        assertEquals(containerId, result.id)
        coVerify { auditService.record<Container>(entityType = "shipping_container", entityId = containerId, action = "created", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = any(), details = any()) }
        // A new box type re-packs the company's unable-to-package shipments.
        coVerify(exactly = 1) { shipmentService.repackUnpackable(companyId, null) }
    }

    @Test
    fun `edit updates the existing container, preserving its id`() = runTest {
        coEvery { containerRepository.get(containerId) } returns container()
        val updated = slot<Container>()
        coEvery { containerRepository.update(capture(updated)) } answers { firstArg() }

        service.edit(containerId, input().copy(name = "Medium Box", supportedWeight = 25.0), principalId = null)

        assertEquals(containerId, updated.captured.id)
        assertEquals("Medium Box", updated.captured.name)
        assertEquals(25.0, updated.captured.supportedWeight)
    }

    @Test
    fun `edit throws when the container is absent`() = runTest {
        coEvery { containerRepository.get(containerId) } returns null
        assertFailsWith<IllegalStateException> { service.edit(containerId, input(), principalId = null) }
        coVerify(exactly = 0) { containerRepository.update(any()) }
    }

    @Test
    fun `delete soft-deletes an existing container`() = runTest {
        coEvery { containerRepository.get(containerId) } returns container()
        assertTrue(service.delete(containerId, principalId = null))
        coVerify(exactly = 1) { containerRepository.softDelete(containerId) }
    }

    @Test
    fun `delete returns false when the container is absent`() = runTest {
        coEvery { containerRepository.get(containerId) } returns null
        assertFalse(service.delete(containerId, principalId = null))
        coVerify(exactly = 0) { containerRepository.softDelete(any()) }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        val c = container()
        coEvery { containerRepository.get(containerId) } returns c
        assertEquals(c, service.get(containerId))
    }

    @Test
    fun `get returns null when the container is absent`() = runTest {
        coEvery { containerRepository.get(containerId) } returns null
        assertNull(service.get(containerId))
    }

    @Test
    fun `getByCompany delegates to the repository`() = runTest {
        val list = listOf(container())
        coEvery { containerRepository.getByCompany(companyId) } returns list
        assertEquals(list, service.getByCompany(companyId))
    }

    @Test
    fun `edit throws when the row vanishes mid-update`() = runTest {
        coEvery { containerRepository.get(containerId) } returns container()
        // The second `?: error(...)` — update returns null.
        coEvery { containerRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> { service.edit(containerId, input(), principalId = null) }
        // The post-update repack never runs when the update fails.
        coVerify(exactly = 0) { shipmentService.repackUnpackable(any(), any()) }
    }
}
