package bosca.content.metadata.jobs

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
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
 * Unit coverage for [ProcessTraitExecutor.execute] and the [ProcessTraitJob] payload data class.
 *
 * [ProcessTraitExecutor.execute] first decodes the job definition (via the base
 * [bosca.sharedqueue.jobs.AbstractJobExecutor.getJobDefinition]) and then hits an unimplemented
 * `TODO()`, so a successful decode followed by a thrown [NotImplementedError] exercises both
 * executable lines of the body. There is no sibling `ProcessTraitJobTest`, so the data-class
 * assertions live here too.
 */
@OptIn(InternalDI::class)
class ProcessTraitExecutorCoverageTest {

    private val jobQueue = mockk<JobQueue>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = ProcessTraitExecutor()

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

    private suspend fun run(job: ProcessTraitJob) {
        val internal = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = ProcessTraitExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(internal)) {
            executor.execute()
        }
    }

    @Test
    fun `execute decodes definition then hits TODO`() = runTest {
        val job = ProcessTraitJob(metadataId = UUID.random(), version = 1, traitId = "trait-a")

        assertFailsWith<NotImplementedError> { run(job) }
    }

    @Test
    fun `field preservation`() {
        val id = UUID.random()
        val job = ProcessTraitJob(metadataId = id, version = 7, traitId = "trait-b")
        assertEquals(id, job.metadataId)
        assertEquals(7, job.version)
        assertEquals("trait-b", job.traitId)
    }
}
