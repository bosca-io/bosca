package bosca.content.transition.jobs

import bosca.queue.annotations.IMetadataJobDefinition
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MetadataJobTest {

    @Test
    fun `preserves id and version fields`() {
        val id = UUID.random()
        val job = MetadataJob(id = id, version = 5)
        assertEquals(id, job.id)
        assertEquals(5, job.version)
    }

    @Test
    fun `version can be null`() {
        val id = UUID.random()
        val job = MetadataJob(id = id, version = null)
        assertEquals(id, job.id)
        assertNull(job.version)
    }

    @Test
    fun `implements IMetadataJobDefinition`() {
        val id = UUID.random()
        val job: IMetadataJobDefinition = MetadataJob(id = id, version = 1)
        assertEquals(id, job.id)
        assertEquals(1, job.version)
    }

    @Test
    fun `implements IJobDefinition`() {
        val id = UUID.random()
        val job = MetadataJob(id = id, version = 2)
        assertIs<IJobDefinition>(job)
    }

    @Test
    fun `two instances with same fields preserve values independently`() {
        val id = UUID.random()
        val a = MetadataJob(id = id, version = 3)
        val b = MetadataJob(id = id, version = 3)
        // MetadataJob is a regular class, not a data class, so check field equality
        assertEquals(a.id, b.id)
        assertEquals(a.version, b.version)
    }
}
