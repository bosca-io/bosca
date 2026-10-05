package bosca.content.collection.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataParentItemCacheInvalidationJobTest {

    @Test
    fun `defaults are null`() {
        val job = MetadataParentItemCacheInvalidationJob()
        assertNull(job.id)
        assertNull(job.version)
    }

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val job = MetadataParentItemCacheInvalidationJob(id = id, version = 7)
        assertEquals(id, job.id)
        assertEquals(7, job.version)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = MetadataParentItemCacheInvalidationJob(id = id, version = 2)
        val b = MetadataParentItemCacheInvalidationJob(id = id, version = 2)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes version`() {
        val id = UUID.random()
        val job = MetadataParentItemCacheInvalidationJob(id = id, version = 1)
        val modified = job.copy(version = 5)
        assertEquals(id, modified.id)
        assertEquals(5, modified.version)
    }
}
