package bosca.content.metadata.jobs

import bosca.content.image.service.ImageService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.video.service.VideoService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit coverage for [MetadataProcessContentExecutor.execute]. Drives every branch of the
 * content-type dispatch (image / video / relationship-derived) with mocked services; the sibling
 * [MetadataProcessContentJobTest] only covers the job payload data class.
 */
@OptIn(InternalDI::class)
class MetadataProcessContentExecutorCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val imageService = mockk<ImageService>(relaxed = true)
    private val videoService = mockk<VideoService>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = MetadataProcessContentExecutor(metadataService, imageService, videoService)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        contentType: String = "text/plain",
        uploaded: OffsetDateTime? = OffsetDateTime.now(),
    ): Metadata = Metadata(
        id = id,
        version = 1,
        name = "meta-$id",
        type = MetadataType.STANDARD,
        contentType = contentType,
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        uploaded = uploaded,
    )

    private fun relationship(id1: UUID, id2: UUID): MetadataRelationship = MetadataRelationship(
        metadataId1 = id1,
        metadataId2 = id2,
        relationship = "related",
    )

    private suspend fun run(id: UUID, version: Int) {
        val config = MetadataProcessContentJob(id = id, version = version)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = MetadataProcessContentExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    @Test
    fun `throws FailException when metadata not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null

        val ex = assertFailsWith<FailException> { run(id, 1) }
        assertEquals("Metadata not found", ex.message)

        coVerify(exactly = 1) { metadataService.getById(id, 1) }
        coVerify(exactly = 0) { imageService.optimize(any<Metadata>()) }
        coVerify(exactly = 0) { videoService.process(any(), any()) }
    }

    @Test
    fun `optimizes image when uploaded present`() = runTest {
        val id = UUID.random()
        val meta = metadata(id = id, contentType = "IMAGE/PNG", uploaded = OffsetDateTime.now())
        coEvery { metadataService.getById(id, 1) } returns meta
        coEvery { imageService.optimize(meta) } returns emptyList()

        run(id, 1)

        coVerify(exactly = 1) { imageService.optimize(meta) }
        coVerify(exactly = 0) { videoService.process(any(), any()) }
        coVerify(exactly = 0) { metadataService.getRelationships(any()) }
    }

    @Test
    fun `image returns early when not uploaded`() = runTest {
        val id = UUID.random()
        val meta = metadata(id = id, contentType = "image/jpeg", uploaded = null)
        coEvery { metadataService.getById(id, 1) } returns meta

        run(id, 1)

        coVerify(exactly = 0) { imageService.optimize(any<Metadata>()) }
        coVerify(exactly = 0) { videoService.process(any(), any()) }
    }

    @Test
    fun `processes video when uploaded present`() = runTest {
        val id = UUID.random()
        val meta = metadata(id = id, contentType = "video/mp4", uploaded = OffsetDateTime.now())
        coEvery { metadataService.getById(id, 1) } returns meta

        run(id, 1)

        coVerify(exactly = 1) { videoService.process(meta) }
        coVerify(exactly = 0) { imageService.optimize(any<Metadata>()) }
    }

    @Test
    fun `video returns early when not uploaded`() = runTest {
        val id = UUID.random()
        val meta = metadata(id = id, contentType = "video/quicktime", uploaded = null)
        coEvery { metadataService.getById(id, 1) } returns meta

        run(id, 1)

        coVerify(exactly = 0) { videoService.process(any(), any()) }
        coVerify(exactly = 0) { imageService.optimize(any<Metadata>()) }
    }

    @Test
    fun `optimizes when a related metadata has been uploaded`() = runTest {
        val id = UUID.random()
        val relatedId = UUID.random()
        val meta = metadata(id = id, contentType = "application/pdf")
        val related = metadata(id = relatedId, uploaded = OffsetDateTime.now())

        coEvery { metadataService.getById(id, 1) } returns meta
        coEvery { metadataService.getRelationships(id) } returns listOf(relationship(id, relatedId))
        coEvery { metadataService.getById(relatedId) } returns related
        coEvery { imageService.optimize(meta) } returns emptyList()

        run(id, 1)

        coVerify(exactly = 1) { metadataService.getRelationships(id) }
        coVerify(exactly = 1) { metadataService.getById(relatedId) }
        coVerify(exactly = 1) { imageService.optimize(meta) }
        coVerify(exactly = 0) { videoService.process(any(), any()) }
    }

    @Test
    fun `does not optimize when related metadata is null`() = runTest {
        val id = UUID.random()
        val relatedId = UUID.random()
        val meta = metadata(id = id, contentType = "application/octet-stream")

        coEvery { metadataService.getById(id, 1) } returns meta
        coEvery { metadataService.getRelationships(id) } returns listOf(relationship(id, relatedId))
        coEvery { metadataService.getById(relatedId) } returns null

        run(id, 1)

        coVerify(exactly = 1) { metadataService.getById(relatedId) }
        coVerify(exactly = 0) { imageService.optimize(any<Metadata>()) }
    }

    @Test
    fun `does not optimize when related metadata is not uploaded`() = runTest {
        val id = UUID.random()
        val relatedId = UUID.random()
        val meta = metadata(id = id, contentType = "text/html")
        val related = metadata(id = relatedId, uploaded = null)

        coEvery { metadataService.getById(id, 1) } returns meta
        coEvery { metadataService.getRelationships(id) } returns listOf(relationship(id, relatedId))
        coEvery { metadataService.getById(relatedId) } returns related

        run(id, 1)

        coVerify(exactly = 1) { metadataService.getById(relatedId) }
        coVerify(exactly = 0) { imageService.optimize(any<Metadata>()) }
    }

    @Test
    fun `does not optimize when there are no relationships`() = runTest {
        val id = UUID.random()
        val meta = metadata(id = id, contentType = "application/json")

        coEvery { metadataService.getById(id, 1) } returns meta
        coEvery { metadataService.getRelationships(id) } returns emptyList()

        run(id, 1)

        coVerify(exactly = 1) { metadataService.getRelationships(id) }
        coVerify(exactly = 0) { metadataService.getById(any<UUID>()) }
        coVerify(exactly = 0) { imageService.optimize(any<Metadata>()) }
    }
}
