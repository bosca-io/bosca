package bosca.scripting.jobs

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.scripting.repository.ScriptRepository
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(InternalDI::class)
class PurgeEphemeralScriptsExecutorTest {

    private val repository = mockk<ScriptRepository>()
    private val json = Json { ignoreUnknownKeys = true }
    private val executor = PurgeEphemeralScriptsExecutor(repository)

    @BeforeTest
    fun setup() {
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @OptIn(Internal::class)
    @Test
    fun `purges soft-deleted scripts older than retention period`() = runTest {
        val jobQueue = mockk<JobQueue>()
        coEvery { repository.purgeDeletedBefore(any()) } returns Unit

        val config = PurgeEphemeralScriptsJob(retentionDays = 7)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = PurgeEphemeralScriptsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { repository.purgeDeletedBefore(any()) }
    }
}
