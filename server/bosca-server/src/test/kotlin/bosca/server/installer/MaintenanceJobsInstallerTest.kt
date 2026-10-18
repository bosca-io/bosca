package bosca.server.installer

import bosca.backup.ObjectBackupDispatchRecoveryJob
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
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class MaintenanceJobsInstallerTest {

    private val schedulerService = mockk<SchedulerService>()
    private val json = Json
    private val installer = MaintenanceJobsInstaller(schedulerService, json)

    private fun scheduledJob(jobName: String): ScheduledJob = mockk {
        every { this@mockk.jobName } returns jobName
    }

    private fun existing(vararg names: String) {
        coEvery { schedulerService.getJobsByName(any(), any(), any()) } answers {
            val name = firstArg<String>()
            if (name in names) listOf(scheduledJob(name)) else emptyList()
        }
    }

    @Test
    fun `version is 1_5_0`() {
        assertEquals("1.5.0", installer.version)
    }

    @Test
    fun `install creates all jobs when none exist`() = runTest {
        existing()
        coEvery { schedulerService.createJob(any(), any()) } returns mockk()

        installer.install(mockk(), mockk())

        coVerify(exactly = 8) { schedulerService.createJob(any(), eq(UUID.NIL)) }
    }

    @Test
    fun `install skips existing finalize-deletion job`() = runTest {
        existing("finalize-deletion")
        coEvery { schedulerService.createJob(any(), any()) } returns mockk()

        installer.install(mockk(), mockk())

        val captured = mutableListOf<ScheduledJobInput>()
        coVerify(exactly = 7) { schedulerService.createJob(capture(captured), any()) }
        assertEquals(
            setOf(
                "purge-ephemeral-scripts",
                "purge-job-history",
                "delete-expired-security-tokens",
                "delete-expired-bible-chapters",
                "message-outbox-maintenance",
                "notification-maintenance",
                ObjectBackupDispatchRecoveryJob.NAME,
            ),
            captured.map { it.jobName }.toSet(),
        )
    }

    @Test
    fun `install skips existing purge-ephemeral-scripts job`() = runTest {
        existing("purge-ephemeral-scripts")
        coEvery { schedulerService.createJob(any(), any()) } returns mockk()

        installer.install(mockk(), mockk())

        val captured = mutableListOf<ScheduledJobInput>()
        coVerify(exactly = 7) { schedulerService.createJob(capture(captured), any()) }
        assertEquals(
            setOf(
                "finalize-deletion",
                "purge-job-history",
                "delete-expired-security-tokens",
                "delete-expired-bible-chapters",
                "message-outbox-maintenance",
                "notification-maintenance",
                ObjectBackupDispatchRecoveryJob.NAME,
            ),
            captured.map { it.jobName }.toSet(),
        )
    }

    @Test
    fun `install skips all when every job exists`() = runTest {
        existing(
            "finalize-deletion", "purge-ephemeral-scripts", "purge-job-history",
            "delete-expired-security-tokens", "delete-expired-bible-chapters",
            "message-outbox-maintenance", "notification-maintenance", ObjectBackupDispatchRecoveryJob.NAME,
        )

        installer.install(mockk(), mockk())

        coVerify(exactly = 0) { schedulerService.createJob(any(), any()) }
    }

    @Test
    fun `install passes correct cron expressions`() = runTest {
        existing()
        coEvery { schedulerService.createJob(any(), any()) } returns mockk()

        val captured = mutableListOf<ScheduledJobInput>()

        installer.install(mockk(), mockk())

        coVerify { schedulerService.createJob(capture(captured), any()) }

        val finalize = captured.find { it.jobName == "finalize-deletion" }
        val purge = captured.find { it.jobName == "purge-ephemeral-scripts" }
        val purgeHistory = captured.find { it.jobName == "purge-job-history" }
        val security = captured.find { it.jobName == "delete-expired-security-tokens" }
        val chapters = captured.find { it.jobName == "delete-expired-bible-chapters" }
        val messageOutbox = captured.find { it.jobName == "message-outbox-maintenance" }
        val notifications = captured.find { it.jobName == "notification-maintenance" }

        assertEquals("0 * * * *", finalize?.cronExpression)
        assertEquals("0 3 * * *", purge?.cronExpression)
        assertEquals("0 0 * * *", purgeHistory?.cronExpression)
        assertEquals(false, purgeHistory?.allowConcurrent)
        assertEquals(
            PurgeJobHistoryJob(retentionDays = 30),
            json.decodeFromJsonElement(assertNotNull(purgeHistory).jobParameters),
        )
        assertEquals("*/30 * * * *", security?.cronExpression)
        assertEquals("0 0 * * *", chapters?.cronExpression)
        assertEquals(false, chapters?.allowConcurrent)
        assertEquals(false, chapters?.catchUp)
        assertEquals(JsonObject(emptyMap()), chapters?.jobParameters)
        assertEquals("0 * * * *", messageOutbox?.cronExpression)
        assertEquals(false, messageOutbox?.allowConcurrent)
        assertEquals("0 * * * *", notifications?.cronExpression)
        assertEquals(false, notifications?.allowConcurrent)
        val recovery = assertNotNull(captured.find { it.jobName == ObjectBackupDispatchRecoveryJob.NAME })
        assertEquals("0 0 * * *", recovery.cronExpression)
        assertEquals(true, recovery.enabled)
        assertEquals(false, recovery.allowConcurrent)
        assertEquals(false, recovery.catchUp)
        assertEquals(1, recovery.maxCatchUp)
        assertEquals(
            ObjectBackupDispatchRecoveryJob(),
            json.decodeFromJsonElement(ObjectBackupDispatchRecoveryJob.serializer(), recovery.jobParameters),
        )
    }

    @Test
    fun `installer finds existing maintenance definitions beyond the first catalog page`() = runTest {
        coEvery { schedulerService.getJobs(any(), any(), any()) } returns
            (1..100).map { scheduledJob("unrelated-$it") }
        coEvery { schedulerService.getJobsByName(any(), any(), any()) } answers {
            listOf(scheduledJob(firstArg()))
        }
        coEvery { schedulerService.createJob(any(), any()) } returns mockk()
        installer.install(mockk(), mockk())
        coVerify(exactly = 0) { schedulerService.createJob(any(), any()) }
        coVerify { schedulerService.getJobsByName(ObjectBackupDispatchRecoveryJob.NAME, 1, 0) }
        coVerify(exactly = 0) { schedulerService.getJobs(any(), any(), any()) }
    }

}
