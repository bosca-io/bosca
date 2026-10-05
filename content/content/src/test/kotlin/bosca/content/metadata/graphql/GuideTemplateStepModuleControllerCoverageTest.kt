package bosca.content.metadata.graphql

import bosca.content.metadata.model.GuideTemplateStepModule
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
import kotlin.test.assertNull

class GuideTemplateStepModuleControllerCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = GuideTemplateStepModuleController(metadataService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun stepModule(
        id: Long? = 1L,
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        step: Long = 1L,
        templateMetadataId: UUID? = null,
        templateMetadataVersion: Int? = null,
        sort: Int = 0
    ) = GuideTemplateStepModule(
        metadataId = metadataId,
        version = version,
        step = step,
        id = id,
        templateMetadataId = templateMetadataId,
        templateMetadataVersion = templateMetadataVersion,
        sort = sort
    )

    // ---------- id ----------

    @Test
    fun `id returns module id when present`() {
        assertEquals(42L, controller.id(stepModule(id = 42L)))
    }

    @Test
    fun `id returns null when module id is null`() {
        assertNull(controller.id(stepModule(id = null)))
    }

    // ---------- metadata ----------

    @Test
    fun `metadata sets filter and delegates to batched service`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))

        coJustRun { metadataService.getByIdBatched(any()) }

        controller.metadata(authentication, batch)

        assertNotNull(batch.filter)
        val delegated = batch
        coVerify { metadataService.getByIdBatched(delegated) }
    }

    @Test
    fun `metadata delegates same batch instance to service`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 2)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))

        coJustRun { metadataService.getByIdBatched(batch) }

        controller.metadata(authentication, batch)

        // The filter installed is the metadata permission filter, exercised via getResults path.
        assertNotNull(batch.filter)
        coVerify(exactly = 1) { metadataService.getByIdBatched(batch) }
    }

    @Test
    fun `metadata handles empty batch keys`() = runTest {
        val batch = Batch<MetadataCacheKeyId, Metadata>(emptyList())

        coJustRun { metadataService.getByIdBatched(any()) }

        controller.metadata(authentication, batch)

        assertNotNull(batch.filter)
        val delegated = batch
        coVerify { metadataService.getByIdBatched(delegated) }
    }
}
