package bosca.scheduler.model

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ScheduledJobTest {

    private val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val createdBy = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")
    private val now = java.time.OffsetDateTime.now()

    @Test
    fun fieldsArePreserved() {
        val job = ScheduledJob(
            id = id,
            name = "Daily Backup",
            jobName = "backup-job",
            cronExpression = "0 0 * * *",
            createdAt = now,
            updatedAt = now,
            createdBy = createdBy
        )
        assertEquals(id, job.id)
        assertEquals("Daily Backup", job.name)
        assertEquals("backup-job", job.jobName)
        assertEquals("0 0 * * *", job.cronExpression)
    }

    @Test
    fun defaultValues() {
        val job = ScheduledJob(
            id = id,
            name = "Job",
            jobName = "j",
            cronExpression = "* * * * *",
            createdAt = now,
            updatedAt = now,
            createdBy = createdBy
        )
        assertNull(job.description)
        assertEquals(JsonObject(emptyMap()), job.jobParameters)
        assertTrue(job.enabled)
        assertFalse(job.allowConcurrent)
        assertFalse(job.catchUp)
        assertEquals(1, job.maxCatchUp)
        assertNull(job.lastRunAt)
        assertNull(job.nextRunAt)
        assertNull(job.executionPrincipalId)
        assertEquals(ScheduledJobPrincipalState.NOT_REQUIRED, job.principalState)
        assertNull(job.principalAssignedBy)
        assertNull(job.principalConfirmedBy)
    }

    @Test
    fun customJobParameters() {
        val params = JsonObject(mapOf("table" to JsonPrimitive("users")))
        val job = ScheduledJob(
            id = id,
            name = "Job",
            jobName = "j",
            cronExpression = "0 0 * * *",
            jobParameters = params,
            createdAt = now,
            updatedAt = now,
            createdBy = createdBy
        )
        assertEquals(params, job.jobParameters)
    }

    @Test
    fun dataClassEquality() {
        val a = ScheduledJob(id = id, name = "J", jobName = "j", cronExpression = "c", createdAt = now, updatedAt = now, createdBy = createdBy)
        val b = ScheduledJob(id = id, name = "J", jobName = "j", cronExpression = "c", createdAt = now, updatedAt = now, createdBy = createdBy)
        assertEquals(a, b)
    }

    @Test
    fun `principal state mapper follows lowercase postgres enum convention`() {
        val resultSet = mockk<ResultSet>()
        every { resultSet.getString(1) } returnsMany listOf(
            "not_required",
            "needs_principal",
            "pending_confirmation",
            "active",
        )
        val mapped = List(4) {
            ScheduledJobPrincipalStateMapper.map(ScheduledJobPrincipalState::class, emptyList(), resultSet, 1)
        }
        assertEquals(ScheduledJobPrincipalState.entries, mapped)

        val statement = mockk<PreparedStatement>(relaxed = true)
        ScheduledJobPrincipalStateMapper.bind(
            ScheduledJobPrincipalState::class,
            emptyList(),
            statement,
            2,
            ScheduledJobPrincipalState.PENDING_CONFIRMATION,
        )
        verify { statement.setString(2, "pending_confirmation") }
    }
}
