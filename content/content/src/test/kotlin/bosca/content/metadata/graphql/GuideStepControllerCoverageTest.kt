package bosca.content.metadata.graphql

import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepContext
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.GuideService
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

class GuideStepControllerCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val guideService = mockk<GuideService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = GuideStepController(metadataService, guideService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun guide(
        metadataId: UUID = UUID.random(),
        version: Int = 1
    ) = Guide(
        metadataId = metadataId,
        version = version,
        rrule = null,
        type = GuideType.LINEAR,
        templateMetadataId = null,
        templateMetadataVersion = null
    )

    private fun step(
        id: Long = 1L,
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        stepMetadataId: UUID? = null,
        stepMetadataVersion: Int? = null,
        sort: Int = 0
    ) = GuideStep(
        id = id,
        metadataId = metadataId,
        version = version,
        stepMetadataId = stepMetadataId,
        stepMetadataVersion = stepMetadataVersion,
        sort = sort
    )

    private fun context(
        guide: Guide = guide(),
        guideStep: GuideStep = step(),
        date: java.time.OffsetDateTime? = null
    ) = GuideStepContext(guide = guide, guideStep = guideStep, date = date)

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
    fun `id returns guide step id`() {
        val ctx = context(guideStep = step(id = 42L))
        assertEquals(42L, controller.id(ctx))
    }

    // ---------- date ----------

    @Test
    fun `date returns context date when present`() {
        val d = java.time.OffsetDateTime.parse("2025-01-01T00:00:00Z")
        val ctx = context(date = d)
        assertEquals(d, controller.date(ctx))
    }

    @Test
    fun `date returns null when context has no date`() {
        val ctx = context(date = null)
        assertNull(controller.date(ctx))
    }

    // ---------- metadata ----------

    @Test
    fun `metadata populates batch with resolved templates`() = runTest {
        val stepMetadataId = UUID.random()
        val ctx = context(
            guideStep = step(stepMetadataId = stepMetadataId, stepMetadataVersion = 2)
        )
        val key = MetadataCacheKeyId(ctx)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), ctx))

        val templateMetadata = metadata(id = stepMetadataId, version = 2)
        coEvery { metadataService.getByIdBatched(any()) } coAnswers {
            val templateBatch = firstArg<Batch<MetadataCacheKeyId, Metadata>>()
            templateBatch.setData(MetadataCacheKeyId(stepMetadataId, 2), templateMetadata)
        }

        controller.metadata(authentication, batch, env)

        assertEquals(templateMetadata, batch.getData(key))
        assertNotNull(batch.filter)
        coVerify { metadataService.getByIdBatched(any()) }
    }

    @Test
    fun `metadata defaults version to 1 when stepMetadataVersion is null`() = runTest {
        val stepMetadataId = UUID.random()
        val ctx = context(
            guideStep = step(stepMetadataId = stepMetadataId, stepMetadataVersion = null)
        )
        val key = MetadataCacheKeyId(ctx)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), ctx))

        val templateBatchSlot = slot<Batch<MetadataCacheKeyId, Metadata>>()
        val templateMetadata = metadata(id = stepMetadataId, version = 1)
        coEvery { metadataService.getByIdBatched(capture(templateBatchSlot)) } coAnswers {
            templateBatchSlot.captured.setData(MetadataCacheKeyId(stepMetadataId, 1), templateMetadata)
        }

        controller.metadata(authentication, batch, env)

        // Version defaulted to 1 -> the template batch key carries version 1.
        assertEquals(listOf(MetadataCacheKeyId(stepMetadataId, 1)), templateBatchSlot.captured.keys)
        assertEquals(templateMetadata, batch.getData(key))
    }

    @Test
    fun `metadata skips keys with no resolved template data`() = runTest {
        val stepMetadataId = UUID.random()
        val ctx = context(
            guideStep = step(stepMetadataId = stepMetadataId, stepMetadataVersion = 1)
        )
        val key = MetadataCacheKeyId(ctx)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), ctx))

        // The template batch is never populated -> getData returns null -> skip.
        coJustRun { metadataService.getByIdBatched(any()) }

        controller.metadata(authentication, batch, env)

        assertNull(batch.getData(key))
        assertNotNull(batch.filter)
    }

    @Test
    fun `metadata errors when guide step has no stepMetadataId`() = runTest {
        val ctx = context(
            guideStep = step(stepMetadataId = null, stepMetadataVersion = 1)
        )
        val key = MetadataCacheKeyId(ctx)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), ctx))

        assertFailsWith<IllegalStateException> {
            controller.metadata(authentication, batch, env)
        }
    }

    // ---------- modules ----------

    @Test
    fun `modules populates batch via guide service`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1, null, 1L)
        val batch = Batch<MetadataCacheKeyId, List<GuideStepModule>>(listOf(key))
        val modules = listOf(stepModule())

        coEvery { guideService.addGuideStepModulesToBatch(any()) } coAnswers {
            firstArg<Batch<MetadataCacheKeyId, List<GuideStepModule>>>().setData(key, modules)
        }

        controller.modules(batch)

        assertEquals(modules, batch.getData(key))
        coVerify { guideService.addGuideStepModulesToBatch(batch) }
    }

    @Test
    fun `modules defaults unpopulated keys to empty list`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1, null, 1L)
        val batch = Batch<MetadataCacheKeyId, List<GuideStepModule>>(listOf(key))

        // Batch left unpopulated -> ensureNotNull fills with empty list.
        coJustRun { guideService.addGuideStepModulesToBatch(any()) }

        controller.modules(batch)

        val result = batch.getData(key)
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }
}
