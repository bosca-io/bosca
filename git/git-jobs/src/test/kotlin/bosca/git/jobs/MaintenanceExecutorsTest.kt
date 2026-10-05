@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.git.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.repository.GitRepositoryRepository
import bosca.git.service.RepositoryLifecycleService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Coverage for the delegation executors that back the scheduled git maintenance
 * jobs (GC, purge, weekly maintenance fan-out, backup) plus their serializable
 * job payloads. Each executor is driven through its real `execute()` inside a
 * job-queue coroutine context, matching how the runner invokes it.
 */
class MaintenanceExecutorsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val lifecycleService = mockk<RepositoryLifecycleService>(relaxed = true)
    private val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<RepositoryLifecycleService>(singleton = true) { lifecycleService }
        provides<GitRepositoryRepository>(singleton = true) { repoRepository }
        // The maintenance fan-out enqueues RepositoryGcJobs via the generated
        // enqueue() extension, which resolves the "git" queue by name.
        provides<JobQueue>(name = "git", singleton = true) { jobQueue }
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private suspend fun <J> run(executor: JobExecutor, serializer: KSerializer<J>, job: J) {
        val jobObj: Job = InternalJobConstructor(
            definition = json.encodeToJsonElement(serializer, job),
            executor = executor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObj)) { executor.execute() }
    }

    // ── GC ──────────────────────────────────────────────────────────────

    @Test
    fun `gc executor runs GC for the job repository`() = runTest {
        val repoId = UUID.random()
        coEvery { lifecycleService.runGc(repoId) } returns true
        run(RepositoryGcExecutor(), RepositoryGcJob.serializer(), RepositoryGcJob(repoId))
        coVerify { lifecycleService.runGc(repoId) }
    }

    @Test
    fun `gc companion delegates to the lifecycle service`() = runTest {
        val repoId = UUID.random()
        coEvery { lifecycleService.runGc(repoId) } returns true
        RepositoryGcExecutor.runGc(RepositoryGcJob(repoId), lifecycleService)
        coVerify { lifecycleService.runGc(repoId) }
    }

    @Test
    fun `gc executor delays for retry when the write lock is busy`() = runTest {
        val repoId = UUID.random()
        coEvery { lifecycleService.runGc(repoId) } returns false

        val delay = assertFailsWith<DelayException> {
            run(RepositoryGcExecutor(), RepositoryGcJob.serializer(), RepositoryGcJob(repoId))
        }

        // A skipped cycle must requeue instead of silently waiting for the next
        // weekly sweep — otherwise a busy repository never gets compacted.
        assertEquals(RepositoryGcExecutor.LOCK_BUSY_RETRY_DELAY, delay.time)
    }

    // ── Purge ───────────────────────────────────────────────────────────

    @Test
    fun `purge executor purges expired repositories`() = runTest {
        run(RepositoryPurgeExecutor(), RepositoryPurgeJob.serializer(), RepositoryPurgeJob())
        coVerify { lifecycleService.purgeExpiredRepositories() }
    }

    @Test
    fun `purge companion delegates to the lifecycle service`() = runTest {
        RepositoryPurgeExecutor.runPurge(RepositoryPurgeJob(), lifecycleService)
        coVerify { lifecycleService.purgeExpiredRepositories() }
    }

    // ── Maintenance fan-out ─────────────────────────────────────────────

    @Test
    fun `maintenance executor enqueues a GC job per active repository`() = runTest {
        val ids = listOf(UUID.random(), UUID.random(), UUID.random())
        coEvery { repoRepository.findActiveIds() } returns ids

        run(RepositoryMaintenanceExecutor(), RepositoryMaintenanceJob.serializer(), RepositoryMaintenanceJob())

        coVerify(exactly = ids.size) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `maintenance executor does nothing when there are no active repositories`() = runTest {
        coEvery { repoRepository.findActiveIds() } returns emptyList()

        run(RepositoryMaintenanceExecutor(), RepositoryMaintenanceJob.serializer(), RepositoryMaintenanceJob())

        coVerify(exactly = 0) { jobQueue.enqueue(any()) }
    }

    // ── Backup ──────────────────────────────────────────────────────────

    @Test
    fun `backup executor backs up a single repository when an id is given`() = runTest {
        val repoId = UUID.random()
        coEvery { lifecycleService.backup(repoId) } returns "git-backups/$repoId/backup.bundle"

        run(RepositoryBackupExecutor(), RepositoryBackupJob.serializer(), RepositoryBackupJob(repoId))

        coVerify(exactly = 1) { lifecycleService.backup(repoId) }
        coVerify(exactly = 0) { repoRepository.findActiveIds() }
    }

    @Test
    fun `backup executor backs up all active repositories when no id is given`() = runTest {
        val ids = listOf(UUID.random(), UUID.random())
        coEvery { repoRepository.findActiveIds() } returns ids
        coEvery { lifecycleService.backup(any()) } returns "path"

        run(RepositoryBackupExecutor(), RepositoryBackupJob.serializer(), RepositoryBackupJob())

        coVerify(exactly = 1) { lifecycleService.backup(ids[0]) }
        coVerify(exactly = 1) { lifecycleService.backup(ids[1]) }
    }

    @Test
    fun `backup executor continues when one repository backup fails`() = runTest {
        val ok = UUID.random()
        val bad = UUID.random()
        coEvery { repoRepository.findActiveIds() } returns listOf(bad, ok)
        coEvery { lifecycleService.backup(bad) } throws RuntimeException("backup boom")
        coEvery { lifecycleService.backup(ok) } returns "path"

        run(RepositoryBackupExecutor(), RepositoryBackupJob.serializer(), RepositoryBackupJob())

        // The failure is swallowed and the next repository is still backed up.
        coVerify { lifecycleService.backup(ok) }
    }

    // ── Job payload serialization / value semantics ─────────────────────

    @Test
    fun `job payloads round-trip and preserve identity`() {
        val repoId = UUID.random()
        val gc = RepositoryGcJob(repoId)
        assertEquals(gc, json.decodeFromString(RepositoryGcJob.serializer(), json.encodeToString(RepositoryGcJob.serializer(), gc)))

        val purge = RepositoryPurgeJob(repoId)
        assertEquals(purge, json.decodeFromString(RepositoryPurgeJob.serializer(), json.encodeToString(RepositoryPurgeJob.serializer(), purge)))
        assertEquals(RepositoryPurgeJob(), json.decodeFromString(RepositoryPurgeJob.serializer(), json.encodeToString(RepositoryPurgeJob.serializer(), RepositoryPurgeJob())))

        val backup = RepositoryBackupJob(repoId)
        assertEquals(backup, json.decodeFromString(RepositoryBackupJob.serializer(), json.encodeToString(RepositoryBackupJob.serializer(), backup)))
        assertEquals(RepositoryBackupJob(), json.decodeFromString(RepositoryBackupJob.serializer(), json.encodeToString(RepositoryBackupJob.serializer(), RepositoryBackupJob())))
    }
}
