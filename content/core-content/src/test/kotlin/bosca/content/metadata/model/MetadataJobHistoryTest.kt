package bosca.content.metadata.model

import bosca.content.transition.model.JobHistory
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MetadataJobHistoryTest {

    private val now = OffsetDateTime.now()

    @Test
    fun `stores all properties`() {
        val id = Uuid.random()
        val jobId = Uuid.random()
        val principal = Uuid.random()
        val complete = OffsetDateTime.now().plusHours(1)
        val delayed = OffsetDateTime.now().plusMinutes(30)

        val history = MetadataJobHistory(
            id = id,
            version = 3,
            jobName = "process-job",
            jobId = jobId,
            status = "completed",
            principal = principal,
            created = now,
            complete = complete,
            success = true,
            delayedUntil = delayed
        )

        assertEquals(id, history.id)
        assertEquals(3, history.version)
        assertEquals("process-job", history.jobName)
        assertEquals(jobId, history.jobId)
        assertEquals("completed", history.status)
        assertEquals(principal, history.principal)
        assertEquals(now, history.created)
        assertEquals(complete, history.complete)
        assertTrue(history.success)
        assertEquals(delayed, history.delayedUntil)
    }

    @Test
    fun `default values`() {
        val id = Uuid.random()
        val jobId = Uuid.random()

        val history = MetadataJobHistory(
            id = id,
            version = 1,
            jobName = "job",
            jobId = jobId,
            status = "pending",
            principal = null
        )

        assertNull(history.complete)
        assertFalse(history.success)
        assertNull(history.delayedUntil)
        assertNull(history.principal)
    }

    @Test
    fun `implements JobHistory interface`() {
        val history = MetadataJobHistory(
            id = Uuid.random(),
            version = 1,
            jobName = "job",
            jobId = Uuid.random(),
            status = "running",
            principal = null
        )
        assertTrue(history is JobHistory)
    }

    @Test
    fun `is not a data class so uses reference equality`() {
        val id = Uuid.random()
        val jobId = Uuid.random()
        val a = MetadataJobHistory(id = id, version = 1, jobName = "j", jobId = jobId, status = "s", principal = null, created = now)
        val b = MetadataJobHistory(id = id, version = 1, jobName = "j", jobId = jobId, status = "s", principal = null, created = now)
        assertTrue(a !== b)
    }
}
