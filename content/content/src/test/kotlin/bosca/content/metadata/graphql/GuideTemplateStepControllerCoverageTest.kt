package bosca.content.metadata.graphql

import bosca.content.metadata.model.GuideTemplateStep
import bosca.content.metadata.model.GuideTemplateStepModule
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.BatchContext
import bosca.graphql.BatchLoaderEnvironment
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideTemplateStepControllerCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val guideTemplateService = mockk<GuideTemplateService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = GuideTemplateStepController(metadataService, guideTemplateService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun step(
        id: Long = 1L,
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        templateMetadataId: UUID? = null,
        templateMetadataVersion: Int? = null,
        sort: Int = 0
    ) = GuideTemplateStep(
        metadataId = metadataId,
        version = version,
        id = id,
        templateMetadataId = templateMetadataId,
        templateMetadataVersion = templateMetadataVersion,
        sort = sort
    )

    private fun metadata(id: UUID = UUID.random(), version: Int = 1) = Metadata(
        id = id,
        name = "template",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        version = version
    )

    private fun stepModule(
        id: Long = 1L,
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
    fun `id returns template step id`() {
        assertEquals(42L, controller.id(step(id = 42L)))
    }

    // ---------- metadata ----------

    @Test
    fun `metadata populates batch with resolved templates`() = runTest {
        val templateMetadataId = UUID.random()
        val guideStep = step(templateMetadataId = templateMetadataId, templateMetadataVersion = 2)
        val key = MetadataCacheKeyId(templateMetadataId, 2)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), guideStep))

        val templateMetadata = metadata(id = templateMetadataId, version = 2)
        coEvery { metadataService.getByIdBatched(any()) } coAnswers {
            val templateBatch = firstArg<Batch<MetadataCacheKeyId, Metadata>>()
            templateBatch.setData(MetadataCacheKeyId(templateMetadataId, 2), templateMetadata)
        }

        controller.metadata(authentication, env, batch)

        assertEquals(templateMetadata, batch.getData(key))
        assertNotNull(batch.filter)
        coVerify { metadataService.getByIdBatched(any()) }
    }

    @Test
    fun `metadata defaults version to 1 when templateMetadataVersion is null`() = runTest {
        val templateMetadataId = UUID.random()
        val guideStep = step(templateMetadataId = templateMetadataId, templateMetadataVersion = null)
        val key = MetadataCacheKeyId(templateMetadataId, 1)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), guideStep))

        val templateBatchSlot = slot<Batch<MetadataCacheKeyId, Metadata>>()
        val templateMetadata = metadata(id = templateMetadataId, version = 1)
        coEvery { metadataService.getByIdBatched(capture(templateBatchSlot)) } coAnswers {
            templateBatchSlot.captured.setData(MetadataCacheKeyId(templateMetadataId, 1), templateMetadata)
        }

        controller.metadata(authentication, env, batch)

        // Version defaulted to 1 -> the template batch key carries version 1.
        assertEquals(listOf(MetadataCacheKeyId(templateMetadataId, 1)), templateBatchSlot.captured.keys)
        assertEquals(templateMetadata, batch.getData(key))
    }

    @Test
    fun `metadata skips keys with no resolved template data`() = runTest {
        val templateMetadataId = UUID.random()
        val guideStep = step(templateMetadataId = templateMetadataId, templateMetadataVersion = 1)
        val key = MetadataCacheKeyId(templateMetadataId, 1)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), guideStep))

        // The template batch is never populated -> getData returns null -> skip via return@forEachIndexed.
        coJustRun { metadataService.getByIdBatched(any()) }

        controller.metadata(authentication, env, batch)

        assertNull(batch.getData(key))
        assertNotNull(batch.filter)
    }

    @Test
    fun `metadata errors when template step has no templateMetadataId`() = runTest {
        val guideStep = step(templateMetadataId = null, templateMetadataVersion = 1)
        val key = MetadataCacheKeyId(UUID.random(), 1)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), guideStep))

        assertFailsWith<IllegalStateException> {
            controller.metadata(authentication, env, batch)
        }
    }

    // ---------- modules ----------

    @Test
    fun `modules populates batch via guide template service`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1, null, 1L)
        val batch = Batch<MetadataCacheKeyId, List<GuideTemplateStepModule>>(listOf(key))
        val modules = listOf(stepModule())

        coEvery { guideTemplateService.addTemplateStepModulesToBatch(any()) } coAnswers {
            firstArg<Batch<MetadataCacheKeyId, List<GuideTemplateStepModule>>>().setData(key, modules)
        }

        controller.modules(batch)

        assertEquals(modules, batch.getData(key))
        coVerify { guideTemplateService.addTemplateStepModulesToBatch(batch) }
    }

    @Test
    fun `modules defaults unpopulated keys to empty list`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1, null, 1L)
        val batch = Batch<MetadataCacheKeyId, List<GuideTemplateStepModule>>(listOf(key))

        // Batch left unpopulated -> ensureNotNull fills with empty list.
        coJustRun { guideTemplateService.addTemplateStepModulesToBatch(any()) }

        controller.modules(batch)

        val result = batch.getData(key)
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }
}
