@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.sharedqueue.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the [Job] ⇄ [SerializedJob] conversion extensions in `SerializedJob.kt`
 * and [JobCallback]'s DI resolution. These are what the queues use to persist and
 * rehydrate a job across a dequeue, so the id-minting, child recursion, and
 * callback (named/unnamed) round-trips must all hold.
 */
class SerializedJobExtTest {

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun job(): Job = InternalJobConstructor(Json.parseToJsonElement("{}"), SerExecutor::class)

    @Test
    fun `serialize mints an id for a NIL-id job`() {
        val job = job()
        assertEquals(UUID.NIL, job.getId())
        val serialized = job.serialize()
        // serialize() assigns a fresh id the first time it runs on an unsaved job.
        assertTrue(serialized.id != UUID.NIL)
        assertEquals(SerExecutor::class.qualifiedName, serialized.executor)
    }

    @Test
    fun `serialize keeps an already-assigned id`() {
        val job = job().apply { setId(UUID.random()) }
        val id = job.getId()
        assertEquals(id, job.serialize().id)
    }

    @Test
    fun `job round-trips through serialize and deserialize with children and callbacks`() {
        val parent = job()
        parent.setContext(JsonPrimitive("ctx"))
        val child = job()
        parent.addChild(child) // attaches a RunChildOnCompleteListener callback too
        parent.setId(UUID.random())

        val serialized = parent.serialize()
        assertEquals(1, serialized.children.size)
        assertTrue(serialized.callbacks.any { it.listener == bosca.sharedqueue.jobs.listeners.RunChildOnCompleteListener::class.qualifiedName })

        val restored = serialized.deserialize()
        assertEquals(parent.getId(), restored.getId())
        assertEquals(SerExecutor::class, restored.executor)
        assertEquals(1, restored.getChildren().size)
        assertEquals(JsonPrimitive("ctx"), restored.getContext())
        assertEquals(parent.getId(), restored.getChildren().single().getParentId())
    }

    @Test
    fun `newListener resolves an unnamed listener`() = runTest {
        provides<SerListener> { SerListener() }
        val callback = JobCallback(listener = SerListener::class)
        assertNotNull(callback.newListener())
    }

    @Test
    fun `newListener resolves a named listener`() = runTest {
        provides<SerListener>(name = "primary") { SerListener() }
        val callback = JobCallback(listener = SerListener::class, listenerName = "primary")
        assertNotNull(callback.newListener())
    }

    @Test
    fun `serialize fails when the executor class has no qualified name`() {
        // A local class has a null qualifiedName, hitting the `?: error` guard.
        class LocalExecutor : JobExecutor {
            override suspend fun execute() {}
        }
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), LocalExecutor::class).apply { setId(UUID.random()) }
        assertFailsWith<IllegalStateException> { job.serialize() }
    }

    @Test
    fun `callback serialize fails when the listener class has no qualified name`() {
        class LocalListener : JobListener
        assertFailsWith<IllegalStateException> { JobCallback(listener = LocalListener::class).serialize() }
    }

    @Test
    fun `callback round-trips through serialize and deserialize preserving the name`() {
        val callback = JobCallback(listener = SerListener::class, listenerName = "named")
        val serialized = callback.serialize()
        assertEquals(SerListener::class.qualifiedName, serialized.listener)
        val restored = serialized.deserialize()
        assertEquals(SerListener::class, restored.listener)
        assertEquals("named", restored.listenerName)
    }
}

private class SerExecutor : JobExecutor {
    override suspend fun execute() {}
}

private class SerListener : JobListener
