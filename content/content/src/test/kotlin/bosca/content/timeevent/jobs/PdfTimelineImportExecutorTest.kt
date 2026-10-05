package bosca.content.timeevent.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.timeevent.service.TimeEventService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pubsub.PubSubService
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.service.ObjectStorageService
import bosca.security.service.SecurityService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit tests for [PdfTimelineImportExecutor] covering error-condition paths.
 * Happy-path tests require a real PDF fixture and are left as integration tests
 * because [org.apache.pdfbox.Loader.loadPDF] is a static method that cannot
 * be trivially mocked.
 */
@OptIn(InternalDI::class)
class PdfTimelineImportExecutorTest {

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
    }

    @OptIn(Internal::class)
    @Test
    fun `fails when PDF metadata not found`() = runTest {
        val pdfId = Uuid.random()
        coEvery { metadataService.getById(pdfId) } returns null

        val exception = assertFailsWith<FailException> {
            executeJob(pdfId = pdfId)
        }
        assertTrue(exception.message!!.contains("PDF metadata not found"))
    }

    @OptIn(Internal::class)
    @Test
    fun `fails when content type is not PDF`() = runTest {
        val pdfId = Uuid.random()
        val pdfMetadata = createMetadata(pdfId, contentType = "image/png")
        coEvery { metadataService.getById(pdfId) } returns pdfMetadata

        val exception = assertFailsWith<FailException> {
            executeJob(pdfId = pdfId)
        }
        assertTrue(exception.message!!.contains("not a PDF"))
    }

    @OptIn(Internal::class)
    @Test
    fun `fails when target metadata not found`() = runTest {
        val pdfId = Uuid.random()
        val targetId = Uuid.random()
        val pdfMetadata = createMetadata(pdfId, contentType = "application/pdf")

        coEvery { metadataService.getById(pdfId) } returns pdfMetadata
        coEvery { metadataService.getById(targetId, 1) } returns null

        val exception = assertFailsWith<FailException> {
            executeJob(pdfId = pdfId, targetId = targetId, targetVersion = 1)
        }
        assertTrue(exception.message!!.contains("Target metadata not found"))
    }

    @OptIn(Internal::class)
    private suspend fun executeJob(
        pdfId: Uuid = Uuid.random(),
        targetId: Uuid = Uuid.random(),
        targetVersion: Int = 1,
        eventTypeId: String = "slide",
        relationship: String = "default",
        durationMs: Long = 60000,
    ) {
        val jobDefinition = PdfTimelineImportJob(
            pdfMetadataId = pdfId,
            targetMetadataId = targetId,
            targetMetadataVersion = targetVersion,
            eventTypeId = eventTypeId,
            relationship = relationship,
            durationMs = durationMs,
        )
        val jobQueue = mockk<JobQueue>(relaxed = true)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(jobDefinition),
            executor = PdfTimelineImportExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    private fun createMetadata(
        id: Uuid,
        contentType: String = "application/pdf",
    ) = Metadata(
        id = id,
        name = "test-file",
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = 1024L,
        languageTag = "en",
        workflowStateId = "published",
        created = OffsetDateTime.now(),
    )
}
