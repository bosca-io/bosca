package bosca.content.collection.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionParentItemCacheInvalidationJobTest {

    @Test
    fun `default id is null`() {
        val job = CollectionParentItemCacheInvalidationJob()
        assertNull(job.id)
    }

    @Test
    fun `field preservation with id`() {
        val id = UUID.random()
        val job = CollectionParentItemCacheInvalidationJob(id = id)
        assertEquals(id, job.id)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = CollectionParentItemCacheInvalidationJob(id = id)
        val b = CollectionParentItemCacheInvalidationJob(id = id)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes id`() {
        val job = CollectionParentItemCacheInvalidationJob(id = UUID.random())
        val newId = UUID.random()
        val modified = job.copy(id = newId)
        assertEquals(newId, modified.id)
    }
}
