package bosca.sharedqueue.jobs

import bosca.core.annotations.Internal
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The `job()` / `jobQueue()` accessors read the running job and its queue out of
 * the coroutine context that [JobQueue.asCoroutineContext] installs. The strict
 * accessors throw when called outside a job; the `*OrNull` variants return null.
 * Both sides of that contract are covered here.
 */
class JobQueueCoroutineContextTest {

    private val queue = mockk<JobQueue>(relaxed = true)

    @AfterTest
    fun tearDown() = unmockkAll()

    @OptIn(Internal::class)
    private fun newJob(): Job = InternalJobConstructor(Json.parseToJsonElement("{}"), CtxExecutor::class)

    @Test
    fun `job and jobQueue return the bound instances inside the context`() = runTest {
        val job = newJob()
        withContext(queue.asCoroutineContext(job)) {
            assertSame(job, job())
            assertSame(queue, jobQueue())
            assertSame(job, jobOrNull())
            assertSame(queue, jobQueueOrNull())
        }
    }

    @Test
    fun `jobOrNull and jobQueueOrNull return null outside a job context`() = runTest {
        assertNull(jobOrNull())
        assertNull(jobQueueOrNull())
    }

    @Test
    fun `job throws when not running inside a job`() = runTest {
        assertFailsWith<IllegalStateException> { job() }
    }

    @Test
    fun `jobQueue throws when not running inside a job`() = runTest {
        assertFailsWith<IllegalStateException> { jobQueue() }
    }
}

private class CtxExecutor : JobExecutor {
    override suspend fun execute() {}
}
