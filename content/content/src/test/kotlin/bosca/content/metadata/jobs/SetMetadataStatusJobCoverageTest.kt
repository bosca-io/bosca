package bosca.content.metadata.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
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

/**
 * Unit coverage for [SetMetadataStatusJobExecutor.execute].
 *
 * Exercises the early-return branch (missing metadata), and both sides of each of the three
 * nullable `?.let` branches (public, publicContent, publicSupplementary) — the present side
 * dispatches the corresponding setter, the null side skips it. The sibling
 * [SetMetadataStatusJobTest] already covers the [SetMetadataStatusJob] data-class members.
 */
@OptIn(InternalDI::class)
class SetMetadataStatusJobCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxed = true)

    private val json = Json { ignoreUnknownKeys = true }

    private val executor = SetMetadataStatusJobExecutor(metadataService)

    @BeforeTest
    fun setup() {
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        version: Int = 1,
    ) = Metadata(
        id = id,
        version = version,
        name = "item",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
        workflowStatePendingId = null,
        ready = OffsetDateTime.now(),
    )

    private suspend fun run(job: SetMetadataStatusJob) {
        val jobQueue = mockk<JobQueue>()
        val jobObject = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = SetMetadataStatusJobExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObject)) {
            executor.execute()
        }
    }

    @Test
    fun `returns early when metadata is missing`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id, 1) } returns null

        run(SetMetadataStatusJob(id = id, version = 1, public = true, publicContent = true, publicSupplementary = true))

        coVerify(exactly = 1) { metadataService.getById(id, 1) }
        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicContent(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicSupplementary(any(), any()) }
    }

    @Test
    fun `applies all three status flags when present`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, version = 2)
        coEvery { metadataService.getById(id, 2) } returns md

        run(
            SetMetadataStatusJob(
                id = id,
                version = 2,
                public = true,
                publicContent = false,
                publicSupplementary = true,
            )
        )

        coVerify(exactly = 1) { metadataService.setPublic(md, true) }
        coVerify(exactly = 1) { metadataService.setPublicContent(md, false) }
        coVerify(exactly = 1) { metadataService.setPublicSupplementary(md, true) }
    }

    @Test
    fun `skips all setters when every flag is null`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, version = 1)
        coEvery { metadataService.getById(id, 1) } returns md

        run(
            SetMetadataStatusJob(
                id = id,
                version = 1,
                public = null,
                publicContent = null,
                publicSupplementary = null,
            )
        )

        coVerify(exactly = 1) { metadataService.getById(id, 1) }
        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicContent(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicSupplementary(any(), any()) }
    }

    @Test
    fun `applies only public when others are null`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, version = 1)
        coEvery { metadataService.getById(id, 1) } returns md

        run(
            SetMetadataStatusJob(
                id = id,
                version = 1,
                public = false,
                publicContent = null,
                publicSupplementary = null,
            )
        )

        coVerify(exactly = 1) { metadataService.setPublic(md, false) }
        coVerify(exactly = 0) { metadataService.setPublicContent(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicSupplementary(any(), any()) }
    }

    @Test
    fun `applies only publicContent when others are null`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, version = 1)
        coEvery { metadataService.getById(id, 1) } returns md

        run(
            SetMetadataStatusJob(
                id = id,
                version = 1,
                public = null,
                publicContent = true,
                publicSupplementary = null,
            )
        )

        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 1) { metadataService.setPublicContent(md, true) }
        coVerify(exactly = 0) { metadataService.setPublicSupplementary(any(), any()) }
    }

    @Test
    fun `applies only publicSupplementary when others are null`() = runTest {
        val id = UUID.random()
        val md = metadata(id = id, version = 1)
        coEvery { metadataService.getById(id, 1) } returns md

        run(
            SetMetadataStatusJob(
                id = id,
                version = 1,
                public = null,
                publicContent = null,
                publicSupplementary = false,
            )
        )

        coVerify(exactly = 0) { metadataService.setPublic(any(), any()) }
        coVerify(exactly = 0) { metadataService.setPublicContent(any(), any()) }
        coVerify(exactly = 1) { metadataService.setPublicSupplementary(md, false) }
    }
}
