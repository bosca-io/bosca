package bosca.scheduler.graphql

import bosca.scheduler.model.JobHistory
import bosca.scheduler.model.JobHistorySource
import bosca.scheduler.model.ScheduleExecutionStatus
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

class JobHistoryControllerTest {

    private val controller = JobHistoryController()

    @Test
    fun `fields are resolved correctly`() {
        val id = UUID.random()
        val scheduledJobId = UUID.random()
        val jobId = UUID.random()
        val now = OffsetDateTime.now()
        val context: JsonElement = Json.parseToJsonElement("{\"key\": \"value\"}")
        val entry = JobHistory(
            id = id,
            scheduledJobId = scheduledJobId,
            jobId = jobId,
            name = "test-job",
            scheduledFor = now,
            triggeredAt = now,
            source = JobHistorySource.SCHEDULER,
            status = ScheduleExecutionStatus.RUNNING,
            completedAt = now,
            errorMessage = "error",
            wasCatchUp = true,
            context = context
        )

        assertEquals(id, controller.id(entry))
        assertEquals(scheduledJobId, controller.scheduledJobId(entry))
        assertEquals(jobId, controller.jobId(entry))
        assertEquals("test-job", controller.name(entry))
        assertEquals(now, controller.scheduledFor(entry))
        assertEquals(now, controller.triggeredAt(entry))
        assertEquals(JobHistorySource.SCHEDULER, controller.source(entry))
        assertEquals(ScheduleExecutionStatus.RUNNING, controller.status(entry))
        assertEquals(now, controller.completedAt(entry))
        assertEquals("error", controller.errorMessage(entry))
        assertEquals(true, controller.wasCatchUp(entry))
        assertEquals(context, controller.context(entry))
    }

    @Test
    fun `nullable scheduledJobId is resolved correctly`() {
        val id = UUID.random()
        val jobId = UUID.random()
        val now = OffsetDateTime.now()
        val entry = JobHistory(
            id = id,
            scheduledJobId = null,
            jobId = jobId,
            scheduledFor = now,
            triggeredAt = now,
            source = JobHistorySource.EVENT,
            status = ScheduleExecutionStatus.PENDING,
        )

        assertEquals(null, controller.scheduledJobId(entry))
        assertEquals(null, controller.name(entry))
        assertEquals(JobHistorySource.EVENT, controller.source(entry))
        assertEquals(null, controller.context(entry))
        assertEquals(null, controller.parentJobId(entry))
    }

    @Test
    fun `parentJobId is resolved correctly for child rows`() {
        val parentJobId = UUID.random()
        val now = OffsetDateTime.now()
        val entry = JobHistory(
            id = UUID.random(),
            jobId = UUID.random(),
            scheduledFor = now,
            triggeredAt = now,
            parentJobId = parentJobId,
        )

        assertEquals(parentJobId, controller.parentJobId(entry))
    }
}
