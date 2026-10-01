package bosca.profile.profile.jobs

import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProfileIndexJobTest {

    @Test
    fun `ProfileIndexJob default values`() {
        val job = ProfileIndexJob()
        assertFalse(job.deleteFirst)
        assertFalse(job.deleteOnly)
        assertNull(job.storage)
        assertNull(job.id)
        assertNull(job.batchSize)
    }

    @Test
    fun `ProfileIndexJob creation with all fields`() {
        val id = UUID.random()
        val storage = IndexStorageSystem(name = "meilisearch")
        val job = ProfileIndexJob(
            deleteFirst = true,
            deleteOnly = false,
            storage = storage,
            id = id,
            batchSize = 100
        )
        assertEquals(true, job.deleteFirst)
        assertEquals(false, job.deleteOnly)
        assertEquals(storage, job.storage)
        assertEquals(id, job.id)
        assertEquals(100, job.batchSize)
    }

    @Test
    fun `ProfileIndexJob with deleteFirst true`() {
        val job = ProfileIndexJob(deleteFirst = true)
        assertEquals(true, job.deleteFirst)
        assertFalse(job.deleteOnly)
    }

    @Test
    fun `ProfileIndexJob with deleteOnly true`() {
        val job = ProfileIndexJob(deleteOnly = true)
        assertFalse(job.deleteFirst)
        assertEquals(true, job.deleteOnly)
    }

    @Test
    fun `ProfileIndexJob with batchSize`() {
        val job = ProfileIndexJob(batchSize = 50)
        assertEquals(50, job.batchSize)
    }

    @Test
    fun `ProfileIndexJob data class equality`() {
        val id = UUID.random()
        val a = ProfileIndexJob(deleteFirst = true, id = id, batchSize = 200)
        val b = ProfileIndexJob(deleteFirst = true, id = id, batchSize = 200)
        assertEquals(a, b)
    }

    @Test
    fun `ProfileIndexJob data class equality with defaults`() {
        val a = ProfileIndexJob()
        val b = ProfileIndexJob()
        assertEquals(a, b)
    }

    @Test
    fun `ProfileIndexJob copy changes specific fields`() {
        val original = ProfileIndexJob(deleteFirst = false, batchSize = 10)
        val copy = original.copy(deleteFirst = true, batchSize = 500)
        assertEquals(true, copy.deleteFirst)
        assertEquals(500, copy.batchSize)
        assertEquals(false, copy.deleteOnly)
    }

    @Test
    fun `ProfileIndexJob hashCode is consistent for equal instances`() {
        val a = ProfileIndexJob(deleteFirst = true, deleteOnly = true, batchSize = 25)
        val b = ProfileIndexJob(deleteFirst = true, deleteOnly = true, batchSize = 25)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ProfileIndexJob with storage system`() {
        val storage = IndexStorageSystem(id = UUID.random(), name = "test-storage")
        val job = ProfileIndexJob(storage = storage)
        assertEquals(storage, job.storage)
    }
}
