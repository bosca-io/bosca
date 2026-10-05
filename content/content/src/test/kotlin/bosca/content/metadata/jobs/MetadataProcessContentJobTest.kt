package bosca.content.metadata.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataProcessContentJobTest {

    @Test
    fun `field preservation`() {
        val id = UUID.random()
        val job = MetadataProcessContentJob(id = id, version = 4)
        assertEquals(id, job.id)
        assertEquals(4, job.version)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = MetadataProcessContentJob(id = id, version = 1)
        val b = MetadataProcessContentJob(id = id, version = 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes version`() {
        val id = UUID.random()
        val job = MetadataProcessContentJob(id = id, version = 1)
        val modified = job.copy(version = 10)
        assertEquals(10, modified.version)
        assertEquals(id, modified.id)
    }
}
