package bosca.scheduler.graphql

import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.scheduler.service.SchedulerService
import bosca.serialization.OffsetDateTime
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ScheduledJobControllerTest {

    private val schedulerService = mockk<SchedulerService>()
    private val controller = ScheduledJobController(schedulerService)

    private val now = OffsetDateTime.now()
    private val jobId = Uuid.random()
    private val createdBy = Uuid.random()
    private val executionPrincipalId = Uuid.random()

    private val job = ScheduledJob(
        id = jobId,
        name = "Test Job",
        description = "A test scheduled job",
        jobName = "test-runner",
        jobParameters = JsonObject(mapOf("key" to JsonPrimitive("value"))),
        cronExpression = "0 * * * *",
        enabled = true,
        allowConcurrent = false,
        catchUp = true,
        maxCatchUp = 5,
        createdAt = now,
        updatedAt = now,
        createdBy = createdBy,
        executionPrincipalId = executionPrincipalId,
        principalState = ScheduledJobPrincipalState.ACTIVE,
        principalAssignedBy = createdBy,
        principalConfirmedBy = executionPrincipalId,
        lastRunAt = now,
        nextRunAt = now
    )

    @Test
    fun `id returns job id`() {
        assertEquals(jobId, controller.id(job))
    }

    @Test
    fun `name returns job name`() {
        assertEquals("Test Job", controller.name(job))
    }

    @Test
    fun `description returns job description`() {
        assertEquals("A test scheduled job", controller.description(job))
    }

    @Test
    fun `jobName returns the job executor name`() {
        assertEquals("test-runner", controller.jobName(job))
    }

    @Test
    fun `jobParameters returns the parameters`() {
        val params = controller.jobParameters(job)
        assertEquals(JsonObject(mapOf("key" to JsonPrimitive("value"))), params)
    }

    @Test
    fun `cronExpression returns the cron string`() {
        assertEquals("0 * * * *", controller.cronExpression(job))
    }

    @Test
    fun `enabled returns true when job is enabled`() {
        assertTrue(controller.enabled(job))
    }

    @Test
    fun `allowConcurrent returns false`() {
        assertFalse(controller.allowConcurrent(job))
    }

    @Test
    fun `catchUp returns true`() {
        assertTrue(controller.catchUp(job))
    }

    @Test
    fun `maxCatchUp returns configured value`() {
        assertEquals(5, controller.maxCatchUp(job))
    }

    @Test
    fun `createdBy returns the creator UUID`() {
        assertEquals(createdBy, controller.createdBy(job))
    }

    @Test
    fun `principal fields expose durable scheduler assignment state`() {
        assertEquals(executionPrincipalId, controller.executionPrincipalId(job))
        assertEquals(ScheduledJobPrincipalState.ACTIVE, controller.principalState(job))
        assertEquals(createdBy, controller.principalAssignedBy(job))
        assertEquals(executionPrincipalId, controller.principalConfirmedBy(job))
    }

    @Test
    fun `lastRunAt and nextRunAt return timestamps`() {
        assertEquals(now, controller.lastRunAt(job))
        assertEquals(now, controller.nextRunAt(job))
    }

    @Test
    fun `nullable fields return null when not set`() {
        val minimalJob = ScheduledJob(
            id = Uuid.random(),
            name = "Minimal",
            jobName = "runner",
            cronExpression = "* * * * *",
            createdAt = now,
            updatedAt = now,
            createdBy = Uuid.random()
        )
        assertNull(controller.description(minimalJob))
        assertNull(controller.lastRunAt(minimalJob))
        assertNull(controller.nextRunAt(minimalJob))
        assertNull(controller.executionPrincipalId(minimalJob))
        assertEquals(ScheduledJobPrincipalState.NOT_REQUIRED, controller.principalState(minimalJob))
        assertNull(controller.principalAssignedBy(minimalJob))
        assertNull(controller.principalConfirmedBy(minimalJob))
    }
}
