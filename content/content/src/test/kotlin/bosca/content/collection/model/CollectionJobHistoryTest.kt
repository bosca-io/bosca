package bosca.content.collection.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionJobHistoryTest {

    @Test
    fun `CollectionJobHistory without languageTag defaults to null`() {
        val history = CollectionJobHistory(
            id = UUID.random(),
            jobName = "test-job",
            jobId = UUID.random(),
            status = "initial queue",
            principal = null,
        )
        assertNull(history.languageTag)
    }

    @Test
    fun `CollectionJobHistory carries languageTag`() {
        val id = UUID.random()
        val jobId = UUID.random()
        val history = CollectionJobHistory(
            id = id,
            jobName = "transition-collection",
            jobId = jobId,
            status = "initial queue",
            principal = null,
            languageTag = "es",
        )
        assertEquals(id, history.id)
        assertEquals(jobId, history.jobId)
        assertEquals("transition-collection", history.jobName)
        assertEquals("es", history.languageTag)
    }

    @Test
    fun `CollectionJobHistory with null languageTag for collection transitions`() {
        val history = CollectionJobHistory(
            id = UUID.random(),
            jobName = "transition-collection",
            jobId = UUID.random(),
            status = "initial queue",
            principal = UUID.random(),
        )
        assertNull(history.languageTag)
    }

    @Test
    fun `CollectionJobHistory preserves all fields with languageTag`() {
        val id = UUID.random()
        val jobId = UUID.random()
        val principalId = UUID.random()
        val history = CollectionJobHistory(
            id = id,
            jobName = "my-job",
            jobId = jobId,
            status = "running",
            principal = principalId,
            languageTag = "fr",
            success = true,
        )
        assertEquals(id, history.id)
        assertEquals("my-job", history.jobName)
        assertEquals(jobId, history.jobId)
        assertEquals("running", history.status)
        assertEquals(principalId, history.principal)
        assertEquals("fr", history.languageTag)
        assertEquals(true, history.success)
    }
}
