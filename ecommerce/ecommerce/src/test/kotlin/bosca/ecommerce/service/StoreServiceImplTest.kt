package bosca.ecommerce.service

import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreInput
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.repository.StoreRepository
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
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**store orchestration — create/edit audited (store-scoped). */
@OptIn(ExperimentalUuidApi::class)
class StoreServiceImplTest {

    private val storeRepository = mockk<StoreRepository>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: StoreServiceImpl

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        service = StoreServiceImpl(storeRepository, auditService)
    }

    @AfterTest
    fun teardown() = unmockkAll()

    private fun input() = StoreInput(
        companyId = UUID.random(), identifier = "shop", name = "Shop", catalogId = UUID.random(),
        type = StoreType.VIRTUAL, paymentProviderId = UUID.random(), shippingCatalogProductId = UUID.random(),
    )

    private fun store(id: UUID = UUID.random()) = Store(
        id = id, identifier = "old", name = "Old", companyId = UUID.random(), catalogId = UUID.random(),
        type = StoreType.VIRTUAL, paymentProviderId = UUID.random(), shippingCatalogProductId = UUID.random(),
    )

    @Test
    fun `create persists and audits store-scoped`() = runTest {
        val storeId = UUID.random()
        val added = slot<Store>()
        coEvery { storeRepository.add(capture(added)) } answers { added.captured.copy(id = storeId) }

        val result = service.create(input(), principalId = null)

        assertEquals(storeId, result.id)
        assertEquals("shop", added.captured.identifier)
        coVerify(exactly = 1) {
            auditService.record<Store>(eq("store"), eq(storeId), eq("created"), any(), any(), any(), any(), any(), eq(storeId), any())
        }
    }

    @Test
    fun `edit updates and audits`() = runTest {
        val id = UUID.random()
        val existing = Store(
            id = id, identifier = "old", name = "Old", companyId = UUID.random(), catalogId = UUID.random(),
            type = StoreType.VIRTUAL, paymentProviderId = UUID.random(), shippingCatalogProductId = UUID.random(),
        )
        coEvery { storeRepository.get(id) } returns existing
        val updated = slot<Store>()
        coEvery { storeRepository.update(capture(updated)) } answers { updated.captured }

        val result = service.edit(id, input(), principalId = null)

        assertEquals("shop", result.identifier)
        coVerify(exactly = 1) {
            auditService.record<Store>(eq("store"), eq(id), eq("updated"), any(), any(), any(), any(), any(), eq(id), any())
        }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        val id = UUID.random()
        val s = store(id)
        coEvery { storeRepository.get(id) } returns s
        assertEquals(s, service.get(id))
    }

    @Test
    fun `get returns null when the store is absent`() = runTest {
        val id = UUID.random()
        coEvery { storeRepository.get(id) } returns null
        assertNull(service.get(id))
    }

    @Test
    fun `getByIds delegates to the repository`() = runTest {
        val id = UUID.random()
        val s = store(id)
        coEvery { storeRepository.getByIds(listOf(id)) } returns listOf(s)
        assertEquals(listOf(s), service.getByIds(listOf(id)))
    }

    @Test
    fun `getByIdentifier delegates to the repository`() = runTest {
        val s = store()
        coEvery { storeRepository.getByIdentifier("shop") } returns s
        assertEquals(s, service.getByIdentifier("shop"))
    }

    @Test
    fun `getByIdentifier returns null when none matches`() = runTest {
        coEvery { storeRepository.getByIdentifier("missing") } returns null
        assertNull(service.getByIdentifier("missing"))
    }

    @Test
    fun `getByCompany delegates to the repository`() = runTest {
        val companyId = UUID.random()
        val list = listOf(store())
        coEvery { storeRepository.getByCompany(companyId) } returns list
        assertEquals(list, service.getByCompany(companyId))
    }

    @Test
    fun `edit throws when the store is absent`() = runTest {
        val id = UUID.random()
        coEvery { storeRepository.get(id) } returns null

        assertFailsWith<IllegalStateException> { service.edit(id, input(), principalId = null) }
        coVerify(exactly = 0) { storeRepository.update(any()) }
        coVerify(exactly = 0) {
            auditService.record<Store>(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `edit throws when the row vanishes mid-update`() = runTest {
        val id = UUID.random()
        coEvery { storeRepository.get(id) } returns store(id)
        // The second `?: error(...)` — update returns null.
        coEvery { storeRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> { service.edit(id, input(), principalId = null) }
        coVerify(exactly = 0) {
            auditService.record<Store>(any(), any(), eq("updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
