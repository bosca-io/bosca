package bosca.content.metadata.graphql

import bosca.content.graphql.MetadataBatchFilter
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuideStepModuleControllerCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = GuideStepModuleController(metadataService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun stepModule(
        id: Long = 1L,
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        step: Long = 1L,
        moduleMetadataId: UUID? = null,
        moduleMetadataVersion: Int? = null,
        sort: Int = 0
    ) = GuideStepModule(
        id = id,
        metadataId = metadataId,
        version = version,
        step = step,
        moduleMetadataId = moduleMetadataId,
        moduleMetadataVersion = moduleMetadataVersion,
        sort = sort
    )

    // ---------- id ----------

    @Test
    fun `id returns module id`() {
        val module = stepModule(id = 99L)
        assertEquals(99L, controller.id(module))
    }

    @Test
    fun `id returns default zero id`() {
        val module = stepModule(id = 0L)
        assertEquals(0L, controller.id(module))
    }

    // ---------- metadata ----------

    @Test
    fun `metadata sets batch filter and delegates to service`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1, null, 1L)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))

        coJustRun { metadataService.getByIdBatched(batch) }

        controller.metadata(authentication, batch)

        val filter = batch.filter
        assertNotNull(filter)
        assertTrue(filter is MetadataBatchFilter)
        coVerify { metadataService.getByIdBatched(batch) }
    }

    @Test
    fun `metadata delegates with empty batch`() = runTest {
        val batch = Batch<MetadataCacheKeyId, Metadata>(emptyList())

        coJustRun { metadataService.getByIdBatched(batch) }

        controller.metadata(authentication, batch)

        assertNotNull(batch.filter)
        coVerify { metadataService.getByIdBatched(batch) }
    }
}
