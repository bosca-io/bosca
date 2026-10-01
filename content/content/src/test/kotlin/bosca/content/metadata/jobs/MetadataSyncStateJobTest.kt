package bosca.content.metadata.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataSyncStateJobTest {

    @Test
    fun `field preservation`() {
        val id = UUID.random()
        val job = MetadataSyncStateJob(id = id, version = 3)
        assertEquals(id, job.id)
        assertEquals(3, job.version)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = MetadataSyncStateJob(id = id, version = 5)
        val b = MetadataSyncStateJob(id = id, version = 5)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes version`() {
        val id = UUID.random()
        val job = MetadataSyncStateJob(id = id, version = 1)
        val modified = job.copy(version = 99)
        assertEquals(99, modified.version)
        assertEquals(id, modified.id)
    }
}
