package bosca.scheduler.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobHistoryTest {

    @Test
    fun `JobHistory defaults scheduledJobId to null`() {
        val now = OffsetDateTime.now()
        val history = JobHistory(
            id = UUID.random(),
            jobId = UUID.random(),
            scheduledFor = now,
            triggeredAt = now
        )
        assertNull(history.scheduledJobId)
    }

    @Test
    fun `JobHistory defaults source to EVENT`() {
        val now = OffsetDateTime.now()
        val history = JobHistory(
            id = UUID.random(),
            jobId = UUID.random(),
            scheduledFor = now,
            triggeredAt = now
        )
        assertEquals(JobHistorySource.EVENT, history.source)
    }

    @Test
    fun `JobHistory defaults status to PENDING`() {
        val now = OffsetDateTime.now()
        val history = JobHistory(
            id = UUID.random(),
            jobId = UUID.random(),
            scheduledFor = now,
            triggeredAt = now
        )
        assertEquals(ScheduleExecutionStatus.PENDING, history.status)
    }

    @Test
    fun `JobHistory defaults wasCatchUp to false`() {
        val now = OffsetDateTime.now()
        val history = JobHistory(
            id = UUID.random(),
            jobId = UUID.random(),
            scheduledFor = now,
            triggeredAt = now
        )
        assertEquals(false, history.wasCatchUp)
    }
}
