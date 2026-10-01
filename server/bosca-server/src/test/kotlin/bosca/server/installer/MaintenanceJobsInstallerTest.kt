package bosca.server.installer

import bosca.scheduler.jobs.PurgeJobHistoryJob
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class MaintenanceJobsInstallerTest {

    private val schedulerService = mockk<SchedulerService>()
    private val json = Json { }
    private val installer = MaintenanceJobsInstaller(schedulerService, json)

    private fun scheduledJob(jobName: String): ScheduledJob = mockk {
        every { this@mockk.jobName } returns jobName
    }

    @Test
    fun `version is 1_3_0`() {
        assertEquals("1.3.0", installer.version)
    }

    @Test
    fun `install creates all jobs when none exist`() = runTest {
        coEvery { schedulerService.getJobs(any(), any(), any()) } returns emptyList()
        coEvery { schedulerService.createJob(any(), any()) } returns mockk()

        installer.install(mockk(), mockk())

        coVerify(exactly = 6) { schedulerService.createJob(any(), eq(UUID.NIL)) }
    }

    @Test
    fun `install skips existing finalize-deletion job`() = runTest {
        coEvery { schedulerService.getJobs(any(), any(), any()) } returns listOf(
            scheduledJob("finalize-deletion")
        )
        coEvery { schedulerService.createJob(any(), any()) } returns mockk()

        installer.install(mockk(), mockk())

        val captured = mutableListOf<ScheduledJobInput>()
        coVerify(exactly = 5) { schedulerService.createJob(capture(captured), any()) }
        assertEquals(
            setOf(
                "purge-ephemeral-scripts",
                "purge-job-history",
                "delete-expired-security-tokens",
                "message-outbox-maintenance",
                "notification-maintenance",
            ),
            captured.map { it.jobName }.toSet(),
        )
    }

    @Test
    fun `install skips existing purge-ephemeral-scripts job`() = runTest {
        coEvery { schedulerService.getJobs(any(), any(), any()) } returns listOf(
            scheduledJob("purge-ephemeral-scripts")
        )
        coEvery { schedulerService.createJob(any(), any()) } returns mockk()

        installer.install(mockk(), mockk())

        val captured = mutableListOf<ScheduledJobInput>()
        coVerify(exactly = 5) { schedulerService.createJob(capture(captured), any()) }
        assertEquals(
            setOf(
                "finalize-deletion",
                "purge-job-history",
                "delete-expired-security-tokens",
                "message-outbox-maintenance",
                "notification-maintenance",
            ),
            captured.map { it.jobName }.toSet(),
        )
    }

    @Test
    fun `install skips all when every job exists`() = runTest {
        coEvery { schedulerService.getJobs(any(), any(), any()) } returns listOf(
            scheduledJob("finalize-deletion"),
            scheduledJob("purge-ephemeral-scripts"),
            scheduledJob("purge-job-history"),
            scheduledJob("delete-expired-security-tokens"),
            scheduledJob("message-outbox-maintenance"),
            scheduledJob("notification-maintenance"),
        )

        installer.install(mockk(), mockk())

        coVerify(exactly = 0) { schedulerService.createJob(any(), any()) }
    }

    @Test
    fun `install passes correct cron expressions`() = runTest {
        coEvery { schedulerService.getJobs(any(), any(), any()) } returns emptyList()
        coEvery { schedulerService.createJob(any(), any()) } returns mockk()

        val captured = mutableListOf<ScheduledJobInput>()

        installer.install(mockk(), mockk())

        coVerify { schedulerService.createJob(capture(captured), any()) }

        val finalize = captured.find { it.jobName == "finalize-deletion" }
        val purge = captured.find { it.jobName == "purge-ephemeral-scripts" }
        val purgeHistory = captured.find { it.jobName == "purge-job-history" }
        val security = captured.find { it.jobName == "delete-expired-security-tokens" }
        val messageOutbox = captured.find { it.jobName == "message-outbox-maintenance" }
        val notifications = captured.find { it.jobName == "notification-maintenance" }

        assertEquals("0 * * * *", finalize?.cronExpression)
        assertEquals("0 3 * * *", purge?.cronExpression)
        assertEquals("0 0 * * *", purgeHistory?.cronExpression)
        assertEquals(false, purgeHistory?.allowConcurrent)
        assertEquals(
            PurgeJobHistoryJob(retentionDays = 30),
            json.decodeFromJsonElement(purgeHistory!!.jobParameters),
        )
        assertEquals("*/30 * * * *", security?.cronExpression)
        assertEquals("* * * * *", messageOutbox?.cronExpression)
        assertEquals(false, messageOutbox?.allowConcurrent)
        assertEquals("* * * * *", notifications?.cronExpression)
        assertEquals(false, notifications?.allowConcurrent)
    }
}
