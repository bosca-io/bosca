package bosca.content.collection.jobs

import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CollectionIndexJobTest {

    @Test
    fun `all defaults are null or false`() {
        val job = CollectionIndexJob()
        assertNull(job.id)
        assertNull(job.storage)
        assertFalse(job.deleteFirst)
        assertFalse(job.deleteOnly)
        assertNull(job.batchSize)
    }

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val storage = IndexStorageSystem(id = UUID.random(), name = "meilisearch")
        val job = CollectionIndexJob(
            id = id,
            storage = storage,
            deleteFirst = true,
            deleteOnly = true,
            batchSize = 500
        )
        assertEquals(id, job.id)
        assertEquals(storage, job.storage)
        assertEquals(true, job.deleteFirst)
        assertEquals(true, job.deleteOnly)
        assertEquals(500, job.batchSize)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = CollectionIndexJob(id = id, deleteFirst = true)
        val b = CollectionIndexJob(id = id, deleteFirst = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes single field`() {
        val job = CollectionIndexJob(deleteFirst = false)
        val modified = job.copy(deleteFirst = true)
        assertEquals(true, modified.deleteFirst)
        assertNull(modified.id)
    }
}
