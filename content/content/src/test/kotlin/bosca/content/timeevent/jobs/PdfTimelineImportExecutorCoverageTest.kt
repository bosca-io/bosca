package bosca.content.timeevent.jobs

import bosca.attributes.AttributeType
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventInput
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.model.TimeEventMetadataRelationshipInput
import bosca.content.timeevent.model.TimeEventTypeAttribute
import bosca.content.timeevent.service.TimeEventService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pubsub.PubSubService
import bosca.security.model.Group
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Coverage tests for [PdfTimelineImportExecutor] that exercise the happy-path
 * page-rendering loop, the type-attribute METADATA branch, the null-attribute
 * fallback branch, the single-page duration branch, [getLockId], and
 * `clearPriorImport` (both the empty and populated paths). Error-condition
 * paths (missing PDF, wrong content type, missing target) are covered by the
 * sibling [PdfTimelineImportExecutorTest]; they are not duplicated here.
 *
 * A real (in-memory) multi-page PDF is produced with PDFBox and returned from
 * the storage stub so the executor genuinely loads and renders it — the static
 * [org.apache.pdfbox.Loader.loadPDF] method is thereby exercised rather than
 * mocked.
 */
@OptIn(InternalDI::class)
class PdfTimelineImportExecutorCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val objectStorageService = mockk<ObjectStorageService>(relaxed = true)
    private val timeEventService = mockk<TimeEventService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = PdfTimelineImportExecutor(
        metadataService,
        objectStorageService,
        timeEventService,
        pubSubService,
        securityService,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        unmockkAll()
    }

    // ---- helpers ------------------------------------------------------------

    private fun createMetadata(
        id: Uuid,
        name: String = "test-file",
        contentType: String = "application/pdf",
    ) = Metadata(
        id = id,
        name = name,
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = 1024L,
        languageTag = "en",
        workflowStateId = "published",
        created = OffsetDateTime.now(),
    )

    /** Builds a valid multi-page PDF entirely in memory and returns its bytes. */
    private fun buildPdfBytes(pages: Int): ByteArray {
        val document = PDDocument()
        try {
            repeat(pages) {
                document.addPage(PDPage(PDRectangle.LETTER))
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    /**
     * Wires the storage download extension (getPath + getInputStream) to return
     * a fresh PDF stream on each call so the executor can copy it to a temp file.
     */
    private fun stubPdfDownload(pages: Int) {
        val bytes = buildPdfBytes(pages)
        coEvery { objectStorageService.getPath(any<Metadata>(), any()) } returns StringObjectPath("pdf")
        coEvery { objectStorageService.getInputStream(any()) } answers {
            ByteArrayInputStream(bytes) as InputStream
        }
    }

    /** Stubs metadataService.add to return a distinct real Metadata per invocation. */
    private fun stubPageAdds() {
        coEvery { metadataService.add(any(), any(), any<MetadataInput>()) } answers {
            val input = thirdArg<MetadataInput>()
            createMetadata(Uuid.random(), name = input.name, contentType = "image/png")
        }
    }

    private fun typeAttributeWithMetadata(
        key: String,
        relationship: String?,
    ): TemplateAttribute {
        val configuration = relationship?.let {
            JsonObject(mapOf("relationship" to JsonPrimitive(it)))
        }
        return TemplateAttribute(
            timeEventTypeAttribute = TimeEventTypeAttribute(
                typeId = "slide",
                key = key,
                name = "Page",
                description = "Page image",
                configuration = configuration,
                type = AttributeType.METADATA,
            )
        )
    }

    private fun buildJob(
        pdfId: Uuid = Uuid.random(),
        targetId: Uuid = Uuid.random(),
        targetVersion: Int = 1,
        eventTypeId: String = "slide",
        relationship: String = "default",
        durationMs: Long = 60000,
    ) = PdfTimelineImportJob(
        pdfMetadataId = pdfId,
        targetMetadataId = targetId,
        targetMetadataVersion = targetVersion,
        eventTypeId = eventTypeId,
        relationship = relationship,
        durationMs = durationMs,
    )

    @OptIn(Internal::class)
    private suspend fun run(job: PdfTimelineImportJob) {
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val constructed = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = PdfTimelineImportExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(constructed)) {
            executor.execute()
        }
    }

    private fun stubImpersonation() {
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns Principal(id = Uuid.random())
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns emptyList<Group>()
    }

    // ---- getLockId ----------------------------------------------------------

    @OptIn(Internal::class)
    @Test
    fun `getLockId combines target metadata id and version`() = runTest {
        val targetId = Uuid.random()
        val job = buildJob(targetId = targetId, targetVersion = 7)
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val constructed = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = PdfTimelineImportExecutor::class,
        )
        val lockId = withContext(jobQueue.asCoroutineContext(constructed)) {
            executor.getLockId()
        }
        assertEquals("$targetId-7", lockId)
    }

    // ---- happy path: multi-page with METADATA attribute ---------------------

    @OptIn(Internal::class)
    @Test
    fun `imports multi-page pdf using type attribute key and configuration relationship`() = runTest {
        val pdfId = Uuid.random()
        val targetId = Uuid.random()
        val job = buildJob(pdfId = pdfId, targetId = targetId, targetVersion = 1, durationMs = 60000)

        coEvery { metadataService.getById(pdfId) } returns createMetadata(pdfId, contentType = "application/pdf")
        coEvery { metadataService.getById(targetId, 1) } returns createMetadata(targetId)
        coEvery { timeEventService.getTimeEventsByType(targetId, 1, "slide") } returns emptyList()
        coEvery { timeEventService.getTypeAttributes("slide") } returns
            listOf(typeAttributeWithMetadata("pageImage", "slide-rel"))

        stubPdfDownload(pages = 2)
        stubPageAdds()

        val timeEventInputSlot = mutableListOf<TimeEventInput>()
        coEvery {
            timeEventService.addTimeEvent(eq(targetId), eq(1), capture(timeEventInputSlot))
        } answers {
            val input = timeEventInputSlot.last()
            TimeEvent(
                id = Uuid.random(),
                metadataId = targetId,
                metadataVersion = 1,
                type = input.type,
                startOffsetMs = input.startOffsetMs,
                endOffsetMs = input.endOffsetMs,
                sort = input.sort ?: 0,
            )
        }

        val relationshipSlot = mutableListOf<TimeEventMetadataRelationshipInput>()
        coEvery {
            timeEventService.addMetadataRelationship(any(), capture(relationshipSlot), any())
        } answers {
            val input = relationshipSlot.last()
            TimeEventMetadataRelationship(
                timeEventId = Uuid.random(),
                metadataId = input.metadataId,
                relationship = input.relationship,
            )
        }

        stubImpersonation()

        run(job)

        // Two pages => two time events created and two adds.
        coVerify(exactly = 2) { metadataService.add(any(), any(), any<MetadataInput>()) }
        coVerify(exactly = 2) { timeEventService.addTimeEvent(eq(targetId), eq(1), any()) }
        // Per page: one linked (page) + one original relationship.
        coVerify(exactly = 4) { timeEventService.addMetadataRelationship(any(), any(), any()) }
        coVerify(exactly = 2) { metadataService.setReady(any(), any()) }
        coVerify(exactly = 1) {
            pubSubService.publish(any(), any(), any<bosca.content.timeevent.events.TimeEventChanged>())
        }

        // interval = 60000 / 2 = 30000; page 0 => [0,30000], page 1 => [30000,60000]
        assertEquals(2, timeEventInputSlot.size)
        assertEquals(0L, timeEventInputSlot[0].startOffsetMs)
        assertEquals(30000L, timeEventInputSlot[0].endOffsetMs)
        assertEquals(0, timeEventInputSlot[0].sort)
        assertEquals(30000L, timeEventInputSlot[1].startOffsetMs)
        assertEquals(60000L, timeEventInputSlot[1].endOffsetMs)
        assertEquals(1, timeEventInputSlot[1].sort)
        // attrKey != null => attributes populated for every event.
        assertNotNull(timeEventInputSlot[0].attributes)
        assertNotNull(timeEventInputSlot[1].attributes)

        // attrRelationship comes from configuration ("slide-rel"), plus an "original" per page.
        val relationships = relationshipSlot.map { it.relationship }
        assertTrue(relationships.count { it == "slide-rel" } == 2)
        assertTrue(relationships.count { it == "original" } == 2)
    }

    // ---- happy path: attrKey null + relationship fallback -------------------

    @OptIn(Internal::class)
    @Test
    fun `imports single-page pdf with null attribute key and job relationship fallback`() = runTest {
        val pdfId = Uuid.random()
        val targetId = Uuid.random()
        val job = buildJob(
            pdfId = pdfId,
            targetId = targetId,
            targetVersion = 3,
            relationship = "job-rel",
            durationMs = 45000,
        )

        coEvery { metadataService.getById(pdfId) } returns createMetadata(pdfId, contentType = "application/pdf")
        coEvery { metadataService.getById(targetId, 3) } returns createMetadata(targetId)
        coEvery { timeEventService.getTimeEventsByType(targetId, 3, "slide") } returns emptyList()
        // No METADATA attribute => metadataAttr null => attrKey null, attrRelationship = job.relationship.
        coEvery { timeEventService.getTypeAttributes("slide") } returns emptyList()

        stubPdfDownload(pages = 1)
        stubPageAdds()

        val timeEventInputSlot = mutableListOf<TimeEventInput>()
        coEvery {
            timeEventService.addTimeEvent(eq(targetId), eq(3), capture(timeEventInputSlot))
        } answers {
            val input = timeEventInputSlot.last()
            TimeEvent(
                id = Uuid.random(),
                metadataId = targetId,
                metadataVersion = 3,
                type = input.type,
                startOffsetMs = input.startOffsetMs,
                endOffsetMs = input.endOffsetMs,
                sort = input.sort ?: 0,
            )
        }

        val relationshipSlot = mutableListOf<TimeEventMetadataRelationshipInput>()
        coEvery {
            timeEventService.addMetadataRelationship(any(), capture(relationshipSlot), any())
        } answers {
            val input = relationshipSlot.last()
            TimeEventMetadataRelationship(
                timeEventId = Uuid.random(),
                metadataId = input.metadataId,
                relationship = input.relationship,
            )
        }

        stubImpersonation()

        run(job)

        coVerify(exactly = 1) { metadataService.add(any(), any(), any<MetadataInput>()) }
        coVerify(exactly = 1) { timeEventService.addTimeEvent(eq(targetId), eq(3), any()) }

        // Single page: interval = 0, endMs = job.durationMs.
        assertEquals(1, timeEventInputSlot.size)
        assertEquals(0L, timeEventInputSlot[0].startOffsetMs)
        assertEquals(45000L, timeEventInputSlot[0].endOffsetMs)
        // attrKey == null => attributes null.
        assertNull(timeEventInputSlot[0].attributes)

        // attrRelationship falls back to job.relationship for the page link.
        val relationships = relationshipSlot.map { it.relationship }
        assertTrue(relationships.contains("job-rel"))
        assertTrue(relationships.contains("original"))
    }

    // ---- attribute present but configuration missing relationship ----------

    @OptIn(Internal::class)
    @Test
    fun `metadata attribute without configuration relationship falls back to job relationship`() = runTest {
        val pdfId = Uuid.random()
        val targetId = Uuid.random()
        val job = buildJob(
            pdfId = pdfId,
            targetId = targetId,
            targetVersion = 1,
            relationship = "fallback-rel",
            durationMs = 30000,
        )

        coEvery { metadataService.getById(pdfId) } returns createMetadata(pdfId, contentType = "application/pdf")
        coEvery { metadataService.getById(targetId, 1) } returns createMetadata(targetId)
        coEvery { timeEventService.getTimeEventsByType(targetId, 1, "slide") } returns emptyList()
        // METADATA attr present (so attrKey non-null) but no "relationship" in configuration.
        coEvery { timeEventService.getTypeAttributes("slide") } returns
            listOf(typeAttributeWithMetadata("pageImage", relationship = null))

        stubPdfDownload(pages = 1)
        stubPageAdds()

        val relationshipSlot = mutableListOf<TimeEventMetadataRelationshipInput>()
        coEvery {
            timeEventService.addMetadataRelationship(any(), capture(relationshipSlot), any())
        } answers {
            val input = relationshipSlot.last()
            TimeEventMetadataRelationship(
                timeEventId = Uuid.random(),
                metadataId = input.metadataId,
                relationship = input.relationship,
            )
        }

        val timeEventInputSlot = slot<TimeEventInput>()
        coEvery {
            timeEventService.addTimeEvent(any(), any(), capture(timeEventInputSlot))
        } answers {
            TimeEvent(
                id = Uuid.random(),
                metadataId = targetId,
                metadataVersion = 1,
                type = "slide",
                startOffsetMs = 0,
            )
        }

        stubImpersonation()

        run(job)

        // attrKey is non-null so event attributes are populated even though relationship falls back.
        assertTrue(timeEventInputSlot.isCaptured)
        assertNotNull(timeEventInputSlot.captured.attributes)
        val relationships = relationshipSlot.map { it.relationship }
        assertTrue(relationships.contains("fallback-rel"))
    }

    // ---- clearPriorImport: existing events + orphaned page metadata ---------

    @OptIn(Internal::class)
    @Test
    fun `clears prior events and orphaned page images before re-import`() = runTest {
        val pdfId = Uuid.random()
        val targetId = Uuid.random()
        val job = buildJob(pdfId = pdfId, targetId = targetId, targetVersion = 2, durationMs = 60000)

        coEvery { metadataService.getById(pdfId) } returns createMetadata(pdfId, contentType = "application/pdf")
        coEvery { metadataService.getById(targetId, 2) } returns createMetadata(targetId)

        val priorEventId = Uuid.random()
        val priorEvent = TimeEvent(
            id = priorEventId,
            metadataId = targetId,
            metadataVersion = 2,
            type = "slide",
            startOffsetMs = 0,
        )
        coEvery { timeEventService.getTimeEventsByType(targetId, 2, "slide") } returns listOf(priorEvent)

        // One page-image relationship (kept for deletion) and one "original" (filtered out).
        val orphanPageId = Uuid.random()
        val originalPageId = Uuid.random()
        val missingPageId = Uuid.random()
        coEvery { timeEventService.getMetadataRelationships(priorEventId) } returns listOf(
            TimeEventMetadataRelationship(priorEventId, orphanPageId, relationship = "slide-rel"),
            TimeEventMetadataRelationship(priorEventId, missingPageId, relationship = "slide-rel"),
            TimeEventMetadataRelationship(priorEventId, originalPageId, relationship = "original"),
        )

        // orphanPageId resolves -> delete; missingPageId returns null -> continue branch.
        val orphanMetadata = createMetadata(orphanPageId, contentType = "image/png")
        coEvery { metadataService.getById(orphanPageId) } returns orphanMetadata
        coEvery { metadataService.getById(missingPageId) } returns null

        coEvery { timeEventService.getTypeAttributes("slide") } returns emptyList()

        stubPdfDownload(pages = 1)
        stubPageAdds()
        coEvery { timeEventService.addTimeEvent(any(), any(), any()) } returns TimeEvent(
            id = Uuid.random(),
            metadataId = targetId,
            metadataVersion = 2,
            type = "slide",
            startOffsetMs = 0,
        )
        coEvery { timeEventService.addMetadataRelationship(any(), any(), any()) } returns
            TimeEventMetadataRelationship(Uuid.random(), Uuid.random(), relationship = "x")
        stubImpersonation()

        run(job)

        // Prior events of the type were deleted before re-import.
        coVerify(exactly = 1) { timeEventService.deleteTimeEventsByType(targetId, 2, "slide") }
        // Only the resolvable orphan page image is deleted; the missing one hits the continue branch.
        val deleteSlot = mutableListOf<Metadata>()
        coVerify { metadataService.delete(capture(deleteSlot)) }
        assertTrue(deleteSlot.any { it.id == orphanPageId })
        assertTrue(deleteSlot.none { it.id == missingPageId })
        // getById(missingPageId) was consulted (and returned null).
        coVerify(exactly = 1) { metadataService.getById(missingPageId) }
    }

    // ---- clearPriorImport: no existing events (early return) ----------------

    @OptIn(Internal::class)
    @Test
    fun `skips clearing when no prior events of the type exist`() = runTest {
        val pdfId = Uuid.random()
        val targetId = Uuid.random()
        val job = buildJob(pdfId = pdfId, targetId = targetId, targetVersion = 1, durationMs = 10000)

        coEvery { metadataService.getById(pdfId) } returns createMetadata(pdfId, contentType = "application/pdf")
        coEvery { metadataService.getById(targetId, 1) } returns createMetadata(targetId)
        coEvery { timeEventService.getTimeEventsByType(targetId, 1, "slide") } returns emptyList()
        coEvery { timeEventService.getTypeAttributes("slide") } returns emptyList()

        stubPdfDownload(pages = 1)
        stubPageAdds()
        coEvery { timeEventService.addTimeEvent(any(), any(), any()) } returns TimeEvent(
            id = Uuid.random(),
            metadataId = targetId,
            metadataVersion = 1,
            type = "slide",
            startOffsetMs = 0,
        )
        coEvery { timeEventService.addMetadataRelationship(any(), any(), any()) } returns
            TimeEventMetadataRelationship(Uuid.random(), Uuid.random(), relationship = "x")
        stubImpersonation()

        run(job)

        // Early return: no delete-by-type call and no relationship lookups for clearing.
        coVerify(exactly = 0) { timeEventService.deleteTimeEventsByType(any(), any(), any()) }
        coVerify(exactly = 0) { timeEventService.getMetadataRelationships(any()) }
    }
}
