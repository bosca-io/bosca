package bosca.content.metadata.jobs

import bosca.content.metadata.service.DocumentService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
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
import kotlin.test.assertFailsWith

/**
 * Unit coverage for [DocumentClearCollaborationExecutor.execute].
 *
 * The executor decodes its [DocumentClearCollaborationJob] payload (via
 * [bosca.sharedqueue.jobs.AbstractJobExecutor.getJobDefinition]) and then calls
 * [DocumentService.removeCollaboration] with the job's `id` and `version`, each of which is
 * elvis-guarded with `error(...)`. The tests exercise the happy path plus both `error(...)`
 * arms (missing id, missing version). The [DocumentClearCollaborationJob] data class itself is
 * covered by the sibling `DocumentClearCollaborationJobTest`.
 */
@OptIn(InternalDI::class)
class DocumentClearCollaborationExecutorCoverageTest {

    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val documentService = mockk<DocumentService>()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = DocumentClearCollaborationExecutor(documentService)

    @BeforeTest
    fun setupDi() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
        ProviderRegistry.clear()
    }

    private suspend fun run(job: DocumentClearCollaborationJob) {
        val internal = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = DocumentClearCollaborationExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(internal)) {
            executor.execute()
        }
    }

    @Test
    fun `execute removes collaboration when id and version present`() = runTest {
        val id = UUID.random()
        coEvery { documentService.removeCollaboration(id, 3) } returns Unit

        run(DocumentClearCollaborationJob(id = id, version = 3))

        coVerify(exactly = 1) { documentService.removeCollaboration(id, 3) }
    }

    @Test
    fun `execute fails when id missing`() = runTest {
        assertFailsWith<IllegalStateException> {
            run(DocumentClearCollaborationJob(id = null, version = 3))
        }

        coVerify(exactly = 0) { documentService.removeCollaboration(any(), any()) }
    }

    @Test
    fun `execute fails when version missing`() = runTest {
        val id = UUID.random()

        assertFailsWith<IllegalStateException> {
            run(DocumentClearCollaborationJob(id = id, version = null))
        }

        coVerify(exactly = 0) { documentService.removeCollaboration(any(), any()) }
    }
}
