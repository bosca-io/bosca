package bosca.sharedqueue.jobs

import bosca.core.annotations.Internal
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobTest {

    @OptIn(Internal::class)
    private fun job() = InternalJobConstructor(Json.parseToJsonElement("{}"), DummyExecutor::class)

    // ----- isFullyComplete + FAILED_AND_COMPLETE -----

    @Test
    fun isFullyComplete_true_for_complete_with_no_children() {
        val job = job().apply { setStatus(JobStatus.COMPLETE) }
        assertTrue(job.isFullyComplete())
    }

    @Test
    fun isFullyComplete_true_for_terminal_failure() {
        val job = job().apply { setStatus(JobStatus.FAILED_AND_COMPLETE) }
        assertTrue(job.isFullyComplete(), "a terminal failure (FAILED_AND_COMPLETE) counts as complete")
    }

    @Test
    fun isFullyComplete_false_for_retryable_failure() {
        val job = job().apply { setStatus(JobStatus.FAILED) }
        assertFalse(job.isFullyComplete(), "a retryable FAILED is not complete")
    }

    @Test
    fun isFullyComplete_false_for_running() {
        val job = job().apply { setStatus(JobStatus.RUNNING) }
        assertFalse(job.isFullyComplete())
    }

    @Test
    fun terminally_failed_child_does_not_block_parent() {
        val parent = job()
        val child = job()
        parent.addChild(child)
        parent.setStatus(JobStatus.COMPLETE)
        child.setStatus(JobStatus.FAILED_AND_COMPLETE)
        assertTrue(parent.areChildrenComplete())
        assertTrue(parent.isFullyComplete(), "a terminally-failed child must not hang the parent")
    }

    @Test
    fun retrying_child_keeps_parent_open() {
        val parent = job()
        val child = job()
        parent.addChild(child)
        parent.setStatus(JobStatus.COMPLETE)
        child.setStatus(JobStatus.FAILED)
        assertFalse(parent.areChildrenComplete(), "a still-retrying child keeps the parent open")
        assertFalse(parent.isFullyComplete())
    }

    // ----- runOnFailure -----

    @Test
    fun runOnFailure_defaults_false() {
        assertFalse(job().getRunOnFailure())
    }

    @Test
    fun setRunOnFailure_sets_the_flag() {
        val job = job()
        job.setRunOnFailure(true)
        assertTrue(job.getRunOnFailure())
    }


    @OptIn(Internal::class)
    @Test
    fun addCallback_deduplicates_same_listener() {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = DummyExecutor::class
        )

        job.addCallback(JobCallback(listener = DummyListener::class))
        job.addCallback(JobCallback(listener = DummyListener::class))

        assertEquals(1, job.callbacks.size)
    }

    @OptIn(Internal::class)
    @Test
    fun addCallback_allows_different_listeners() {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = DummyExecutor::class
        )

        job.addCallback(JobCallback(listener = DummyListener::class))
        job.addCallback(JobCallback(listener = AnotherDummyListener::class))

        assertEquals(2, job.callbacks.size)
    }

    @OptIn(Internal::class)
    @Test
    fun addCallback_allows_same_listener_with_different_names() {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = DummyExecutor::class
        )

        job.addCallback(JobCallback(listener = DummyListener::class, listenerName = "a"))
        job.addCallback(JobCallback(listener = DummyListener::class, listenerName = "b"))

        assertEquals(2, job.callbacks.size)
    }

    @OptIn(Internal::class)
    @Test
    fun addCallback_deduplicates_same_listener_and_name() {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = DummyExecutor::class
        )

        job.addCallback(JobCallback(listener = DummyListener::class, listenerName = "a"))
        job.addCallback(JobCallback(listener = DummyListener::class, listenerName = "a"))

        assertEquals(1, job.callbacks.size)
    }
}

private class DummyExecutor : JobExecutor {
    override suspend fun execute() {}
}

private class DummyListener : JobListener

private class AnotherDummyListener : JobListener
