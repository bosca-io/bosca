@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.sharedqueue.jobs.configuration

import bosca.di.ProviderRegistry
import bosca.di.provide
import bosca.di.provides
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.pubsub.PubSubService
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.jobs.MultiJobExecutor
import bosca.sharedqueue.jobs.jobs.MultiJobExecutorEnqueuer
import bosca.sharedqueue.jobs.listeners.NotifyJobCompleteListener
import bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener
import bosca.sharedqueue.jobs.listeners.NotifyParentListener
import bosca.sharedqueue.jobs.listeners.RunChildOnCompleteListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * [RegisterJobsConfiguration] is the module's DI bootstrap: it registers the
 * listeners, the multi-job executor/enqueuer, the common [JobQueue] (built from a
 * [JobQueueFactory]) and the common [JobRunner]. This resolves every registration
 * so both the `provides` call sites and each provider lambda body run — the queue
 * name it looks up must match [JobQueueNames.commonQueue].
 */
class RegisterJobsConfigurationTest {

    private val lockFactory = mockk<DistributedLockFactory>(relaxed = true)
    private val pubsub = mockk<PubSubService>(relaxed = true)
    private val queueFactory = mockk<JobQueueFactory>()
    private val commonQueue = mockk<JobQueue>(relaxed = true)

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        // Dependencies the registration lambdas resolve.
        provides<DistributedLockFactory> { lockFactory }
        provides<PubSubService> { pubsub }
        provides<ErrorCapture> { ErrorCapture.Noop }
        provides<JobQueueFactory> { queueFactory }
        every { queueFactory.create(any()) } returns commonQueue

        RegisterJobsConfiguration()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    @Test
    fun `registers the job listeners`() = runTest {
        assertNotNull(provide<NotifyParentListener>())
        assertNotNull(provide<RunChildOnCompleteListener>())
        assertNotNull(provide<NotifyJobCompleteListener>())
        assertNotNull(provide<NotifyJobStatusListener>())
    }

    @Test
    fun `registers the multi-job executor and enqueuer`() = runTest {
        assertNotNull(provide<MultiJobExecutor>())
        assertNotNull(provide<MultiJobExecutorEnqueuer>())
        // The named enqueuer is an alias for the singleton MultiJobExecutorEnqueuer.
        val named = provide<JobConfigurationEnqueuer>(name = "multi-job")
        assertSame(provide<MultiJobExecutorEnqueuer>(), named)
    }

    @Test
    fun `builds the common queue from the factory`() = runTest {
        val queue = provide<JobQueue>(name = JobQueueNames.commonJobQueue)
        assertSame(commonQueue, queue)
    }

    @Test
    fun `builds the common runner`() = runTest {
        assertNotNull(provide<JobRunner>(name = JobQueueNames.commonRunner))
    }

    @Test
    fun `queue name constants are stable`() = runTest {
        // The factory is invoked with the common queue name; guard the constant
        // used at that call site so a rename can't silently repoint the runner.
        provide<JobQueue>(name = JobQueueNames.commonJobQueue)
        io.mockk.verify { queueFactory.create(JobQueueNames.commonQueue) }
    }
}
