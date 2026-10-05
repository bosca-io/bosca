package bosca.content.metadata.graphql

import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideStep
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

class GuideControllerCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val service = mockk<GuideService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = GuideController(metadataService, service, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    // A properly formatted, multi-line iCalendar rrule that yields recurrence dates.
    private val calendarRrule = "DTSTART:20250101T000000Z\nRRULE:FREQ=DAILY"

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun guide(
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        rrule: String? = null,
        type: GuideType = GuideType.LINEAR,
        templateMetadataId: UUID? = null,
        templateMetadataVersion: Int? = null
    ) = Guide(
        metadataId = metadataId,
        version = version,
        rrule = rrule,
        type = type,
        templateMetadataId = templateMetadataId,
        templateMetadataVersion = templateMetadataVersion
    )

    private fun step(
        id: Long = 1L,
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        sort: Int = 0
    ) = GuideStep(
        id = id,
        metadataId = metadataId,
        version = version,
        stepMetadataId = null,
        stepMetadataVersion = null,
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

    // ---------- type ----------

    @Test
    fun `type returns guide type`() {
        val g = guide(type = GuideType.CALENDAR)
        assertEquals(GuideType.CALENDAR, controller.type(g))
    }

    // ---------- rrule ----------

    @Test
    fun `rrule returns guide rrule`() {
        val g = guide(rrule = calendarRrule)
        assertEquals(calendarRrule, controller.rrule(g))
    }

    @Test
    fun `rrule returns null when guide has no rrule`() {
        assertNull(controller.rrule(guide(rrule = null)))
    }

    // ---------- template ----------

    @Test
    fun `template populates batch with resolved templates`() = runTest {
        val templateId = UUID.random()
        val g = guide(templateMetadataId = templateId, templateMetadataVersion = 2)
        val key = MetadataCacheKeyId(g)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), g))

        val templateMetadata = metadata(id = templateId, version = 2)
        coEvery { metadataService.getByIdBatched(any()) } coAnswers {
            val templateBatch = firstArg<Batch<MetadataCacheKeyId, Metadata>>()
            templateBatch.setData(MetadataCacheKeyId(templateId, 2), templateMetadata)
        }

        controller.template(authentication, batch, env)

        assertEquals(templateMetadata, batch.getData(key))
        assertNotNull(batch.filter)
        coVerify { metadataService.getByIdBatched(any()) }
    }

    @Test
    fun `template defaults version to 1 when templateMetadataVersion is null`() = runTest {
        val templateId = UUID.random()
        val g = guide(templateMetadataId = templateId, templateMetadataVersion = null)
        val key = MetadataCacheKeyId(g)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), g))

        val templateBatchSlot = slot<Batch<MetadataCacheKeyId, Metadata>>()
        val templateMetadata = metadata(id = templateId, version = 1)
        coEvery { metadataService.getByIdBatched(capture(templateBatchSlot)) } coAnswers {
            templateBatchSlot.captured.setData(MetadataCacheKeyId(templateId, 1), templateMetadata)
        }

        controller.template(authentication, batch, env)

        // Version defaulted to 1 -> the key with version 1 exists in the template batch.
        assertEquals(listOf(MetadataCacheKeyId(templateId, 1)), templateBatchSlot.captured.keys)
        assertEquals(templateMetadata, batch.getData(key))
    }

    @Test
    fun `template skips keys with no resolved template data`() = runTest {
        val templateId = UUID.random()
        val g = guide(templateMetadataId = templateId, templateMetadataVersion = 1)
        val key = MetadataCacheKeyId(g)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), g))

        // The template batch is never populated -> getData returns null -> skip.
        coJustRun { metadataService.getByIdBatched(any()) }

        controller.template(authentication, batch, env)

        assertNull(batch.getData(key))
        assertNotNull(batch.filter)
    }

    @Test
    fun `template errors when guide has no template`() = runTest {
        val g = guide(templateMetadataId = null, templateMetadataVersion = null)
        val key = MetadataCacheKeyId(g)
        val batch = Batch<MetadataCacheKeyId, Metadata>(listOf(key))
        val env = mockk<BatchLoaderEnvironment>()
        every { env.keyContextsList } returns listOf(BatchContext(emptyMap(), g))

        assertFailsWith<IllegalStateException> {
            controller.template(authentication, batch, env)
        }
    }

    // ---------- recurrences ----------

    @Test
    fun `recurrences sets recurrence dates for keys with guide and count`() = runTest {
        val g = guide(rrule = calendarRrule)
        val key = MetadataCacheKeyId(g.metadataId, g.version)
        val batch = Batch<MetadataCacheKeyId, List<java.time.OffsetDateTime>>(listOf(key))

        coEvery { service.addGuidesToBatch(any()) } coAnswers {
            firstArg<Batch<MetadataCacheKeyId, Guide>>().setData(key, g)
        }
        coEvery { service.addStepCountsToBatch(any()) } coAnswers {
            firstArg<Batch<MetadataCacheKeyId, Long>>().setData(key, 3L)
        }

        controller.recurrences(batch)

        val result = batch.getData(key)
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `recurrences skips key when guide is missing`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1)
        val batch = Batch<MetadataCacheKeyId, List<java.time.OffsetDateTime>>(listOf(key))

        // Guides batch left unpopulated -> guide is null -> skip.
        coJustRun { service.addGuidesToBatch(any()) }
        coEvery { service.addStepCountsToBatch(any()) } coAnswers {
            firstArg<Batch<MetadataCacheKeyId, Long>>().setData(key, 3L)
        }

        controller.recurrences(batch)

        assertNull(batch.getData(key))
    }

    @Test
    fun `recurrences skips key when count is missing`() = runTest {
        val g = guide(rrule = calendarRrule)
        val key = MetadataCacheKeyId(g.metadataId, g.version)
        val batch = Batch<MetadataCacheKeyId, List<java.time.OffsetDateTime>>(listOf(key))

        coEvery { service.addGuidesToBatch(any()) } coAnswers {
            firstArg<Batch<MetadataCacheKeyId, Guide>>().setData(key, g)
        }
        // Counts batch left unpopulated -> count is null -> skip.
        coJustRun { service.addStepCountsToBatch(any()) }

        controller.recurrences(batch)

        assertNull(batch.getData(key))
    }

    // ---------- step ----------

    @Test
    fun `step by stepId returns context without date when no rrule`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = null)
        val theStep = step(id = 7L, metadataId = id, version = 1, sort = 0)
        coEvery { service.getGuideStep(id, 1, 7L) } returns theStep

        val result = controller.step(g, date = null, stepId = 7L)

        assertNotNull(result)
        assertEquals(theStep, result.guideStep)
        assertNull(result.date)
    }

    @Test
    fun `step by stepId returns context with date when rrule present`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = calendarRrule)
        val theStep = step(id = 7L, metadataId = id, version = 1, sort = 0)
        coEvery { service.getGuideStep(id, 1, 7L) } returns theStep

        val result = controller.step(g, date = null, stepId = 7L)

        assertNotNull(result)
        assertEquals(theStep, result.guideStep)
        assertNotNull(result.date)
    }

    @Test
    fun `step by stepId returns null when step not found`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = null)
        coEvery { service.getGuideStep(id, 1, 99L) } returns null

        assertNull(controller.step(g, date = null, stepId = 99L))
    }

    @Test
    fun `step by date breaks at first recurrence at or after date`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = calendarRrule)
        val theStep = step(id = 3L, metadataId = id, version = 1, sort = 0)
        coEvery { service.getStepCount(id, 1) } returns 5L
        // A date at/before the first recurrence (DTSTART is 2025-01-01) breaks the
        // offset loop on the first iteration -> offset 0.
        val date = java.time.OffsetDateTime.parse("2024-01-01T00:00:00Z")
        coEvery { service.getGuideSteps(id, 1, 0, 1) } returns listOf(theStep)

        val result = controller.step(g, date = date, stepId = null)

        assertNotNull(result)
        assertEquals(theStep, result.guideStep)
        coVerify { service.getGuideSteps(id, 1, 0, 1) }
    }

    @Test
    fun `step by date advances offset past earlier recurrences`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = calendarRrule)
        val theStep = step(id = 4L, metadataId = id, version = 1, sort = 0)
        coEvery { service.getStepCount(id, 1) } returns 10L
        // A date well after DTSTART forces the loop to increment offset past several
        // daily recurrences. The exact offset depends on ical4j's date generation, so
        // match any offset and assert the resolved step.
        val date = java.time.OffsetDateTime.parse("2025-01-05T00:00:00Z")
        val offsetSlot = slot<Int>()
        coEvery { service.getGuideSteps(id, 1, capture(offsetSlot), 1) } returns listOf(theStep)

        val result = controller.step(g, date = date, stepId = null)

        assertNotNull(result)
        assertEquals(theStep, result.guideStep)
        assertTrue(offsetSlot.captured > 0)
    }

    @Test
    fun `step by date errors when guide has no rrule`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = null)
        val date = java.time.OffsetDateTime.parse("2025-01-01T00:00:00Z")

        assertFailsWith<IllegalStateException> {
            controller.step(g, date = date, stepId = null)
        }
    }

    @Test
    fun `step by date returns null when no step at offset`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = calendarRrule)
        coEvery { service.getStepCount(id, 1) } returns 5L
        val date = java.time.OffsetDateTime.parse("2024-01-01T00:00:00Z")
        coEvery { service.getGuideSteps(id, 1, any(), 1) } returns emptyList()

        assertNull(controller.step(g, date = date, stepId = null))
    }

    @Test
    fun `step errors when neither stepId nor date provided`() = runTest {
        val g = guide()

        assertFailsWith<IllegalStateException> {
            controller.step(g, date = null, stepId = null)
        }
    }

    // ---------- stepByOffset ----------

    @Test
    fun `stepByOffset returns first step`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = null)
        val s0 = step(id = 1L, metadataId = id, version = 1, sort = 0)
        coEvery { service.getGuideSteps(id, 1, 2, 1) } returns listOf(s0)

        val result = controller.stepByOffset(g, offset = 2)

        assertNotNull(result)
        assertEquals(s0, result.guideStep)
    }

    @Test
    fun `stepByOffset returns null when no steps`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = null)
        coEvery { service.getGuideSteps(id, 1, 0, 1) } returns emptyList()

        assertNull(controller.stepByOffset(g, offset = 0))
    }

    // ---------- steps + getSteps branches ----------

    @Test
    fun `steps without rrule maps each step with null date`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = null)
        val s0 = step(id = 1L, metadataId = id, version = 1, sort = 0)
        val s1 = step(id = 2L, metadataId = id, version = 1, sort = 1)
        coEvery { service.getGuideSteps(id, 1, null, null) } returns listOf(s0, s1)

        val result = controller.steps(g, offset = null, limit = null)

        assertEquals(2, result.size)
        assertNull(result[0].date)
        assertNull(result[1].date)
        assertEquals(s0, result[0].guideStep)
        assertEquals(s1, result[1].guideStep)
    }

    @Test
    fun `steps with rrule attaches recurrence dates`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = calendarRrule)
        val s0 = step(id = 1L, metadataId = id, version = 1, sort = 0)
        val s1 = step(id = 2L, metadataId = id, version = 1, sort = 1)
        coEvery { service.getGuideSteps(id, 1, 0, 10) } returns listOf(s0, s1)

        val result = controller.steps(g, offset = 0, limit = 10)

        assertEquals(2, result.size)
        assertNotNull(result[0].date)
        assertNotNull(result[1].date)
    }

    @Test
    fun `steps with rrule but no steps returns empty`() = runTest {
        val id = UUID.random()
        val g = guide(metadataId = id, rrule = calendarRrule)
        coEvery { service.getGuideSteps(id, 1, null, null) } returns emptyList()

        val result = controller.steps(g, offset = null, limit = null)

        assertTrue(result.isEmpty())
    }

    // ---------- stepCount ----------

    @Test
    fun `stepCount delegates to service`() = runTest {
        val key = MetadataCacheKeyId(UUID.random(), 1)
        val batch = Batch<MetadataCacheKeyId, Long>(listOf(key))
        coJustRun { service.addStepCountsToBatch(batch) }

        controller.stepCount(batch)

        coVerify { service.addStepCountsToBatch(batch) }
    }
}
