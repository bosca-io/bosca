package bosca.content.collection.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionProcessContentJobTest {

    @Test
    fun `field preservation`() {
        val id = UUID.random()
        val job = CollectionProcessContentJob(id = id)
        assertEquals(id, job.id)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = CollectionProcessContentJob(id = id)
        val b = CollectionProcessContentJob(id = id)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy preserves id`() {
        val id = UUID.random()
        val job = CollectionProcessContentJob(id = id)
        val copied = job.copy()
        assertEquals(id, copied.id)
    }

    @Test
    fun `copy changes id`() {
        val originalId = UUID.random()
        val newId = UUID.random()
        val job = CollectionProcessContentJob(id = originalId)
        val modified = job.copy(id = newId)
        assertEquals(newId, modified.id)
    }
}
