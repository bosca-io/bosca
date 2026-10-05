package bosca.content.metadata.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class GuidePublishStepsJobTest {

    @Test
    fun `field preservation`() {
        val id = UUID.random()
        val job = GuidePublishStepsJob(id = id, version = 3)
        assertEquals(id, job.id)
        assertEquals(3, job.version)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = GuidePublishStepsJob(id = id, version = 1)
        val b = GuidePublishStepsJob(id = id, version = 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes version`() {
        val id = UUID.random()
        val job = GuidePublishStepsJob(id = id, version = 1)
        val modified = job.copy(version = 5)
        assertEquals(5, modified.version)
        assertEquals(id, modified.id)
    }
}
