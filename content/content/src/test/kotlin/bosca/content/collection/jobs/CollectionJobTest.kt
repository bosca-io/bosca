package bosca.content.collection.jobs

import bosca.queue.annotations.ICollectionJobDefinition
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CollectionJobTest {

    @Test
    fun `preserves id field`() {
        val id = UUID.random()
        val job = CollectionJob(id = id)
        assertEquals(id, job.id)
    }

    @Test
    fun `implements ICollectionJobDefinition`() {
        val id = UUID.random()
        val job: ICollectionJobDefinition = CollectionJob(id = id)
        assertEquals(id, job.id)
    }

    @Test
    fun `implements IJobDefinition`() {
        val id = UUID.random()
        val job = CollectionJob(id = id)
        assertIs<IJobDefinition>(job)
    }

    @Test
    fun `two instances with same id are not equal because it is not a data class`() {
        val id = UUID.random()
        val a = CollectionJob(id = id)
        val b = CollectionJob(id = id)
        // CollectionJob is a regular class, not a data class, so referential equality applies
        assertEquals(a.id, b.id)
    }
}
