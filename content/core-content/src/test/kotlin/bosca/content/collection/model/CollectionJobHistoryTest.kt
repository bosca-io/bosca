package bosca.content.collection.model

import bosca.content.transition.model.JobHistory
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CollectionJobHistoryTest {

    private val now = OffsetDateTime.now()

    @Test
    fun `stores all properties`() {
        val id = Uuid.random()
        val jobId = Uuid.random()
        val principal = Uuid.random()
        val complete = OffsetDateTime.now().plusHours(1)
        val delayed = OffsetDateTime.now().plusMinutes(30)

        val history = CollectionJobHistory(
            id = id,
            jobName = "index-job",
            jobId = jobId,
            status = "completed",
            principal = principal,
            languageTag = "fr-FR",
            created = now,
            complete = complete,
            success = true,
            delayedUntil = delayed
        )

        assertEquals(id, history.id)
        assertEquals("index-job", history.jobName)
        assertEquals(jobId, history.jobId)
        assertEquals("completed", history.status)
        assertEquals(principal, history.principal)
        assertEquals("fr-FR", history.languageTag)
        assertEquals(now, history.created)
        assertEquals(complete, history.complete)
        assertTrue(history.success)
        assertEquals(delayed, history.delayedUntil)
    }

    @Test
    fun `default values`() {
        val id = Uuid.random()
        val jobId = Uuid.random()

        val history = CollectionJobHistory(
            id = id,
            jobName = "job",
            jobId = jobId,
            status = "pending",
            principal = null
        )

        assertNull(history.languageTag)
        assertNull(history.complete)
        assertFalse(history.success)
        assertNull(history.delayedUntil)
        assertNull(history.principal)
    }

    @Test
    fun `implements JobHistory interface`() {
        val history = CollectionJobHistory(
            id = Uuid.random(),
            jobName = "job",
            jobId = Uuid.random(),
            status = "running",
            principal = null
        )
        assertTrue(history is JobHistory)
    }

    @Test
    fun `CollectionJobHistory is not a data class so uses reference equality`() {
        val id = Uuid.random()
        val jobId = Uuid.random()
        val a = CollectionJobHistory(id = id, jobName = "j", jobId = jobId, status = "s", principal = null, created = now)
        val b = CollectionJobHistory(id = id, jobName = "j", jobId = jobId, status = "s", principal = null, created = now)
        assertTrue(a !== b)
    }
}
