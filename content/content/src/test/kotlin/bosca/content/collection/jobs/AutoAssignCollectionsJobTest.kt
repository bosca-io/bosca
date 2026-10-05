package bosca.content.collection.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoAssignCollectionsJobTest {

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val job = AutoAssignCollectionsJob(id = id, version = 3)
        assertEquals(id, job.id)
        assertEquals(3, job.version)
    }

    @Test
    fun `version defaults to null`() {
        val id = UUID.random()
        val job = AutoAssignCollectionsJob(id = id)
        assertEquals(id, job.id)
        assertNull(job.version)
    }

    @Test
    fun `data class equality for identical values`() {
        val id = UUID.random()
        val a = AutoAssignCollectionsJob(id = id, version = 1)
        val b = AutoAssignCollectionsJob(id = id, version = 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy preserves fields`() {
        val id = UUID.random()
        val original = AutoAssignCollectionsJob(id = id, version = 5)
        val copied = original.copy()
        assertEquals(original, copied)
    }

    @Test
    fun `copy can change version`() {
        val id = UUID.random()
        val original = AutoAssignCollectionsJob(id = id, version = 1)
        val modified = original.copy(version = 10)
        assertEquals(id, modified.id)
        assertEquals(10, modified.version)
    }
}
