package bosca.sharedqueue.jobs

import bosca.core.annotations.Internal
import bosca.lock.DistributedLock
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.listeners.NotifyParentListener
import bosca.sharedqueue.jobs.listeners.RunChildOnCompleteListener
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhaustive coverage of [Job]'s mutation guards. Every mutator refuses to run
 * unless the job [Job.isLocked] — either it still has the sentinel `UUID.NIL` id
 * (being constructed) or it holds a live [DistributedLock]. These tests drive
 * both the guarded (throwing) and permitted arms, plus the no-op short-circuits.
 */
@OptIn(Internal::class)
class JobInternalsTest {

    private fun freshJob(): Job = InternalJobConstructor(Json.parseToJsonElement("{}"), InternalsExecutor::class)

    /** A job that is no longer locked: a real id and no held lock. */
    private fun unlockedJob(): Job = freshJob().apply { setId(UUID.random()) }

    /** A job with a real id but a held lock, so mutation is still permitted. */
    private fun lockedJob(): Job = freshJob().apply {
        lock = mockk<DistributedLock> { every { isHeld } returns true }
        setId(UUID.random())
    }

    // ----- queueName / parentQueue (cross-queue parent notification) -----

    @Test
    fun `addChild stamps the parent's queueName onto the child as parentQueue`() {
        val parent = lockedJob().apply { queueName = "pipelines" }
        val child = freshJob()
        parent.addChild(child)
        assertEquals("pipelines", child.parentQueue)
    }

    @Test
    fun `addChild leaves parentQueue null when the parent was never enqueued`() {
        val parent = lockedJob()
        val child = freshJob()
        parent.addChild(child)
        assertEquals(null, child.parentQueue)
    }

    @Test
    fun `queueName and parentQueue survive a serialize-deserialize round trip`() {
        val parent = lockedJob().apply { queueName = "pipelines" }
        val child = freshJob()
        parent.addChild(child)
        val restored = parent.serialize().deserialize()
        assertEquals("pipelines", restored.queueName)
        assertEquals("pipelines", restored.getChildren().single().parentQueue)
    }

    @Test
    fun `deserializing a state without queue fields defaults them to null`() {
        // States persisted before the fields existed must keep deserializing (same-queue fallback).
        val legacy = """{"parentId":null,"id":"00000000-0000-0000-0000-000000000001","type":"bosca.sharedqueue.jobs.Job","status":"PENDING",
            "failures":0,"maxFailures":10,"created":"2026-01-01T00:00:00Z","modified":"2026-01-01T00:00:00Z",
            "definition":{},"executor":"bosca.sharedqueue.jobs.InternalsExecutor","executorName":null,"children":[],"callbacks":[],"context":null}"""
        val restored = Json.decodeFromString(SerializedJob.serializer(), legacy).deserialize()
        assertEquals(null, restored.queueName)
        assertEquals(null, restored.parentQueue)
    }

    // ----- isLocked -----

    @Test
    fun `isLocked is true while id is NIL`() {
        assertTrue(freshJob().isLocked)
    }

    @Test
    fun `isLocked is false once id is set and no lock is held`() {
        assertFalse(unlockedJob().isLocked)
    }

    @Test
    fun `isLocked is true when a held lock is present`() {
        assertTrue(lockedJob().isLocked)
    }

    @Test
    fun `isLocked is false when the lock is present but not held`() {
        val job = freshJob().apply {
            lock = mockk<DistributedLock> { every { isHeld } returns false }
            setId(UUID.random())
        }
        assertFalse(job.isLocked)
    }

    // ----- setId -----

    @Test
    fun `setId is a no-op when the id is unchanged`() {
        val job = unlockedJob()
        val id = job.getId()
        // Same id → early return before the lock check, so this must not throw despite being unlocked.
        job.setId(id)
        assertEquals(id, job.getId())
    }

    @Test
    fun `setId throws when the job is not locked`() {
        assertFailsWith<IllegalStateException> { unlockedJob().setId(UUID.random()) }
    }

    @Test
    fun `setId propagates the new id to children as their parent`() {
        val parent = freshJob()
        val child = freshJob()
        parent.addChild(child)
        val newId = UUID.random()
        parent.setId(newId)
        assertEquals(newId, child.getParentId())
    }

    // ----- addCallback -----

    @Test
    fun `addCallback throws when unlocked`() {
        assertFailsWith<IllegalStateException> {
            unlockedJob().addCallback(JobCallback(listener = InternalsListener::class))
        }
    }

    @Test
    fun `addCallback succeeds while locked`() {
        val job = lockedJob()
        job.addCallback(JobCallback(listener = InternalsListener::class))
        assertEquals(1, job.callbacks.size)
    }

    // ----- setParent -----

    @Test
    fun `setParent throws when unlocked`() {
        assertFailsWith<IllegalStateException> { unlockedJob().setParent(UUID.random()) }
    }

    @Test
    fun `setParent is a no-op when the parent is unchanged`() {
        val job = freshJob()
        val parentId = UUID.random()
        job.setParent(parentId)
        val callbacksAfterFirst = job.callbacks.size
        // Second call with the same parent returns early — no duplicate NotifyParentListener.
        job.setParent(parentId)
        assertEquals(callbacksAfterFirst, job.callbacks.size)
    }

    @Test
    fun `setParent registers a NotifyParentListener`() {
        val job = freshJob()
        job.setParent(UUID.random())
        assertTrue(job.callbacks.any { it.listener == NotifyParentListener::class })
    }

    // ----- addChild -----

    @Test
    fun `addChild throws when unlocked`() {
        assertFailsWith<IllegalStateException> { unlockedJob().addChild(freshJob()) }
    }

    @Test
    fun `addChild on a NIL-id parent does not set the child parent yet`() {
        val parent = freshJob()
        val child = freshJob()
        parent.addChild(child)
        // Parent id is still NIL, so the child's parent is not set until the parent gets an id.
        assertEquals(null, child.getParentId())
        assertEquals(1, parent.getChildren().size)
    }

    @Test
    fun `addChild on an id-bearing parent immediately sets the child parent`() {
        val parent = lockedJob()
        val child = freshJob()
        parent.addChild(child)
        assertEquals(parent.getId(), child.getParentId())
    }

    @Test
    fun `addChild with runOnParentComplete adds the RunChildOnCompleteListener exactly once`() {
        val parent = freshJob()
        parent.addChild(freshJob())
        parent.addChild(freshJob())
        assertEquals(1, parent.callbacks.count { it.listener == RunChildOnCompleteListener::class })
    }

    @Test
    fun `addChild without runOnParentComplete does not add the listener`() {
        val parent = freshJob()
        parent.addChild(freshJob(), runOnParentComplete = false)
        assertFalse(parent.callbacks.any { it.listener == RunChildOnCompleteListener::class })
    }

    // ----- setStatus -----

    @Test
    fun `setStatus is a no-op when unchanged`() {
        val job = unlockedJob()
        // Status starts UNINITIALIZED; setting it to the same value returns before the lock check.
        job.setStatus(JobStatus.UNINITIALIZED)
        assertEquals(JobStatus.UNINITIALIZED, statusOf(job))
    }

    @Test
    fun `setStatus throws when unlocked`() {
        assertFailsWith<IllegalStateException> { unlockedJob().setStatus(JobStatus.RUNNING) }
    }

    // ----- setRunOnFailure -----

    @Test
    fun `setRunOnFailure throws when unlocked`() {
        assertFailsWith<IllegalStateException> { unlockedJob().setRunOnFailure(true) }
    }

    // ----- setChildStatus -----

    @Test
    fun `setChildStatus throws when unlocked`() {
        assertFailsWith<IllegalStateException> {
            unlockedJob().setChildStatus(UUID.random(), JobStatus.COMPLETE)
        }
    }

    @Test
    fun `setChildStatus errors when the child is unknown`() {
        val parent = lockedJob()
        assertFailsWith<IllegalStateException> {
            parent.setChildStatus(UUID.random(), JobStatus.COMPLETE)
        }
    }

    @Test
    fun `setChildStatus updates the matching child status and context`() {
        val parent = lockedJob()
        val child = freshJob().apply {
            lock = mockk<DistributedLock> { every { isHeld } returns true }
        }
        parent.addChild(child)
        parent.setContext(JsonPrimitive("ctx"))

        parent.setChildStatus(child.getId(), JobStatus.COMPLETE)

        assertEquals(JobStatus.COMPLETE, statusOf(child))
        assertEquals(JsonPrimitive("ctx"), child.getContext())
    }

    // ----- setContext -----

    @Test
    fun `setContext throws when unlocked`() {
        assertFailsWith<IllegalStateException> { unlockedJob().setContext(JsonPrimitive("x")) }
    }

    @Test
    fun `setContext succeeds while locked`() {
        val job = lockedJob()
        job.setContext(JsonPrimitive("value"))
        assertEquals(JsonPrimitive("value"), job.getContext())
    }

    @Test
    fun `setContext with a Json encoder serializes and stores`() {
        val job = lockedJob()
        job.setContext(Json, 42)
        assertEquals(JsonPrimitive(42), job.getContext())
    }

    private fun statusOf(job: Job): JobStatus = job.status
}

private class InternalsExecutor : JobExecutor {
    override suspend fun execute() {}
}

private class InternalsListener : JobListener
