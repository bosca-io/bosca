package bosca.sharedqueue.jobs.jobs

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.queue.annotations.IJobDefinition
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.prepare
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Unit coverage for [MultiJobExecutor]'s execute() contract:
 *  - Children are added via [Job.addChild] (and the caller relies on
 *    `RunChildOnCompleteListener` to enqueue them), not inline-enqueued.
 *  - Re-running execute() with the same parent state (retry after
 *    markFailed) does NOT duplicate children in the parent's list.
 */
@OptIn(InternalDI::class)
class MultiJobExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val mockQueue = mockk<JobQueue>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        // Each named enqueuer returns a distinct dummy executor so the
        // executor class acts as a natural dedup key for (executor + definition).
        provides<JobConfigurationEnqueuer>(name = "alpha", singleton = true) {
            DummyEnqueuer(AlphaExecutor::class)
        }
        provides<JobConfigurationEnqueuer>(name = "beta", singleton = true) {
            DummyEnqueuer(BetaExecutor::class)
        }
        coEvery { mockQueue.setJob(any()) } just Runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    @Test
    fun `execute adds a child per matching config entry without inline-enqueuing`() = runTest {
        val parent = newMultiJobParent(
            jobs = listOf(
                MultiJobJob(name = "alpha", type = "metadata"),
                MultiJobJob(name = "beta", type = "metadata"),
            ),
            type = "metadata",
        )

        runExecute(parent)

        assertEquals(
            listOf<Class<*>>(AlphaExecutor::class.java, BetaExecutor::class.java),
            parent.getChildren().map { it.executor.java },
            "both matching config entries should be attached as children"
        )
        // No inline NATS publish — children are only added to the parent's
        // list. RunChildOnCompleteListener is responsible for enqueueing.
        coVerify(exactly = 0) { mockQueue.enqueue(any<Job>()) }
        // setJob is called per iteration to persist each child addition.
        coVerify(exactly = 2) { mockQueue.setJob(parent) }
    }

    @Test
    fun `execute filters children whose type does not match definition type`() = runTest {
        val parent = newMultiJobParent(
            jobs = listOf(
                MultiJobJob(name = "alpha", type = "metadata"),
                MultiJobJob(name = "beta", type = "collection"),
            ),
            type = "metadata",
        )

        runExecute(parent)

        assertEquals(1, parent.getChildren().size)
        assertEquals(AlphaExecutor::class.java, parent.getChildren().single().executor.java)
    }

    @Test
    fun `re-running execute does not duplicate children from the previous attempt`() = runTest {
        val parent = newMultiJobParent(
            jobs = listOf(
                MultiJobJob(name = "alpha", type = "metadata"),
                MultiJobJob(name = "beta", type = "metadata"),
            ),
            type = "metadata",
        )

        // First attempt completes its fan-out.
        runExecute(parent)
        val afterFirstAttempt = parent.getChildren().map { it.executor.java }
        assertEquals(2, afterFirstAttempt.size, "first attempt attaches both matching children")

        // Simulate the retry path: markFailed persisted `children`, and the
        // JobRunner re-dequeues and re-runs execute() against the same parent.
        runExecute(parent)

        assertEquals(
            afterFirstAttempt,
            parent.getChildren().map { it.executor.java },
            "retry must not append a second copy of either child"
        )
    }

    @Test
    fun `partial-failure retry fills in the missing child without duplicating earlier ones`() = runTest {
        val parent = newMultiJobParent(
            jobs = listOf(
                MultiJobJob(name = "alpha", type = "metadata"),
                MultiJobJob(name = "beta", type = "metadata"),
            ),
            type = "metadata",
        )

        // Run execute() once to produce children whose definitions match
        // what a retry would compute — that's the state markFailed would
        // have persisted. Then drop the trailing child to simulate "crashed
        // after attaching alpha, before attaching beta". Finally re-run and
        // verify only beta is added.
        runExecute(parent)
        assertEquals(2, parent.getChildren().size)
        // Internal access is legal from the same gradle module.
        parent.children = parent.getChildren().take(1)
        assertEquals(1, parent.getChildren().size)
        assertEquals(AlphaExecutor::class.java, parent.getChildren().single().executor.java)

        runExecute(parent)

        val executorClasses = parent.getChildren().map { it.executor.java }
        assertEquals(
            listOf<Class<*>>(AlphaExecutor::class.java, BetaExecutor::class.java),
            executorClasses,
            "alpha (from first attempt) must remain exactly once, beta must be added"
        )
    }

    @Test
    fun `two config entries with the same executor but distinct definitions both attach`() = runTest {
        val parent = newMultiJobParent(
            jobs = listOf(
                MultiJobJob(
                    name = "alpha",
                    type = "metadata",
                    configuration = Json.parseToJsonElement("""{"variant":"a"}"""),
                ),
                MultiJobJob(
                    name = "alpha",
                    type = "metadata",
                    configuration = Json.parseToJsonElement("""{"variant":"b"}"""),
                ),
            ),
            type = "metadata",
        )

        runExecute(parent)

        // Dedup keys on (executor, definition) — same executor with distinct
        // definitions is a legitimate fan-out and must not be collapsed.
        assertEquals(2, parent.getChildren().size)
        assertTrue(parent.getChildren().all { it.executor.java == AlphaExecutor::class.java })
    }

    @OptIn(Internal::class)
    private fun newMultiJobParent(jobs: List<MultiJobJob>, type: String): Job {
        val definition = MultiJob(jobs = jobs, type = type)
        // Leave id as UUID.NIL so `isLocked` returns true (the "this job is
        // still being constructed" signal) and `addChild` succeeds without
        // us having to wire a real DistributedLock into the test. The Job's
        // children list and callbacks are what we're asserting on, not its id.
        return InternalJobConstructor(
            definition = json.encodeToJsonElement<MultiJob>(definition),
            executor = MultiJobExecutor::class,
        )
    }

    private suspend fun runExecute(parent: Job) {
        withContext(mockQueue.asCoroutineContext(parent)) {
            MultiJobExecutor().execute()
        }
    }

    // Each dummy enqueuer produces a Job whose `executor` identifies the
    // logical fan-out target — what MultiJobExecutor's dedup inspects.
    private class DummyEnqueuer(
        private val executor: kotlin.reflect.KClass<out JobExecutor>,
    ) : JobConfigurationEnqueuer {
        override val queueName: String = "test"

        @OptIn(Internal::class)
        override suspend fun prepare(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job {
            return InternalJobConstructor(definition = configuration, executor = executor)
        }

        override suspend fun enqueue(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job =
            error("enqueue must not be called from MultiJobExecutor.execute()")

        override suspend fun enqueueLater(
            configuration: JsonElement,
            timeout: Duration,
            initializer: suspend Job.() -> Unit,
        ): Job = error("enqueueLater must not be called from MultiJobExecutor.execute()")

        override suspend fun queue(): JobQueue = error("queue() must not be called from MultiJobExecutor.execute()")
    }

    @Serializable
    private data class DummyDefinition(val marker: String = "x") : IJobDefinition

    private class AlphaExecutor : JobExecutor {
        override suspend fun execute() {}
    }

    private class BetaExecutor : JobExecutor {
        override suspend fun execute() {}
    }
}
