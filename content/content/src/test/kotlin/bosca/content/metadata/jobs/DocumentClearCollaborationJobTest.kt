package bosca.content.metadata.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentClearCollaborationJobTest {

    @Test
    fun `defaults are null`() {
        val job = DocumentClearCollaborationJob()
        assertNull(job.id)
        assertNull(job.version)
    }

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val job = DocumentClearCollaborationJob(id = id, version = 5)
        assertEquals(id, job.id)
        assertEquals(5, job.version)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = DocumentClearCollaborationJob(id = id, version = 2)
        val b = DocumentClearCollaborationJob(id = id, version = 2)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes version`() {
        val id = UUID.random()
        val job = DocumentClearCollaborationJob(id = id, version = 1)
        val modified = job.copy(version = 10)
        assertEquals(10, modified.version)
        assertEquals(id, modified.id)
    }
}
