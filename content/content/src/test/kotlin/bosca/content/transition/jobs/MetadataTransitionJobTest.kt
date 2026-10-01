package bosca.content.transition.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataTransitionJobTest {

    @Test
    fun `field preservation`() {
        val id = UUID.random()
        val job = MetadataTransitionJob(id = id, version = 3)
        assertEquals(id, job.id)
        assertEquals(3, job.version)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = MetadataTransitionJob(id = id, version = 1)
        val b = MetadataTransitionJob(id = id, version = 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes version`() {
        val id = UUID.random()
        val job = MetadataTransitionJob(id = id, version = 1)
        val modified = job.copy(version = 99)
        assertEquals(99, modified.version)
        assertEquals(id, modified.id)
    }
}
