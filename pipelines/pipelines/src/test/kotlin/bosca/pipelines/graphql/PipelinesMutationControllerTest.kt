@file:OptIn(Internal::class, ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.core.annotations.Internal
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.git.PipelineBackfillEntry
import bosca.pipelines.git.PipelineGitSyncService
import bosca.pipelines.git.PipelineRepoValidationError
import bosca.pipelines.git.PipelineSyncResult
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineNamedShape
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.model.PipelineSecret
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.ShapeField
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.ExecutionResult
import bosca.pipelines.service.ExecutionState
import bosca.pipelines.service.PipelineExecutor
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.PipelineService
import bosca.pipelines.service.PipelineShapeService
import bosca.pipelines.trigger.PipelineScheduledRunExecutor
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Branch coverage for [PipelinesMutationController] — the authoring / Git-sync / run-control
 * mutations behind `Mutation.pipelines`. Every guard (admin gate, public-vs-grant execute
 * authorization), every best-effort try/catch (schedule sync, Git push), and every model-mapping arm
 * is exercised with both arms taken.
 *
 * Service calls are constructor-injected mockk; the two `provide<T>()` consumers (`provide<Json>()`
 * in dryRun/run/syncSchedule) are satisfied by registering a Json provider in [setUp].
 */
class PipelinesMutationControllerTest {

    private val json = Json

    private val service = mockk<PipelineService>(relaxed = true)
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val permissionEvaluator = mockk<PipelinePermissionEvaluator>(relaxed = true)
    private val executor = mockk<PipelineExecutor>(relaxed = true)
    private val gitSync = mockk<PipelineGitSyncService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val secretService = mockk<PipelineSecretService>(relaxed = true)
    private val shapeService = mockk<PipelineShapeService>(relaxed = true)
    private val scheduler = mockk<SchedulerService>(relaxed = true)

    /** Backing flags so an [ObjectProvider] can report `exists` independently of `get()`. */
    private var gitExists = true
    private var schedulerExists = true

    private val gitProvider = object : ObjectProvider<PipelineGitSyncService> {
        override val type = PipelineGitSyncService::class
        override val exists get() = gitExists
        override suspend fun get() = gitSync
    }
    private val schedulerProvider = object : ObjectProvider<SchedulerService> {
        override val type = SchedulerService::class
        override val exists get() = schedulerExists
        override suspend fun get() = scheduler
    }

    private val controller = PipelinesMutationController(
        service = service,
        groups = groups,
        permissionEvaluator = permissionEvaluator,
        executor = executor,
        gitSyncService = gitProvider,
        runService = runService,
        secretService = secretService,
        schedulerService = schedulerProvider,
        shapeService = shapeService,
    )

    private val auth = AuthenticationContext(null, null)

    @BeforeTest
    fun setUp() {
        provides<Json> { json }
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun pipeline(
        id: UUID = UUID.random(),
        name: String = "P",
        public: Boolean = false,
        schedule: String? = null,
        gitRepositoryId: UUID? = null,
        acceptedInputType: String = "JSON",
    ) = Pipeline(
        id = id,
        name = name,
        acceptedInputType = acceptedInputType,
        public = public,
        schedule = schedule,
        gitRepositoryId = gitRepositoryId,
    )

    private fun input(
        id: UUID? = null,
        name: String = "P",
        description: String? = null,
        tags: List<String>? = null,
        triggered: Boolean? = null,
        key: String? = null,
        api: Boolean? = null,
        public: Boolean? = null,
        schedule: String? = null,
        maxConcurrentRuns: Int? = null,
        maxRunsPerMinute: Int? = null,
        version: Int? = null,
    ) = PipelineInput(
        id = id,
        name = name,
        description = description,
        acceptedInputType = "JSON",
        tags = tags,
        triggered = triggered,
        key = key,
        api = api,
        public = public,
        schedule = schedule,
        maxConcurrentRuns = maxConcurrentRuns,
        maxRunsPerMinute = maxRunsPerMinute,
        version = version,
        graph = JsonObject(emptyMap()),
    )

    private fun rejectAdmin() {
        every { groups.verifyHasAdminGroup(any()) } throws IllegalStateException("not an admin")
    }

    // ---------------------------------------------------------------------------------------------
    // save
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `save rejects a non-admin and never touches the service`() = runTest {
        rejectAdmin()
        assertFailsWith<IllegalStateException> { controller.save(auth, input()) }
        coVerify(exactly = 0) { service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `save coalesces all nullable input fields to their defaults`() = runTest {
        schedulerExists = false
        val saved = pipeline(gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved

        val result = controller.save(auth, input(id = null, description = null, triggered = null, key = null, api = null, public = null, version = null))

        assertSame(saved, result)
        coVerify(exactly = 1) {
            service.save(
                id = UUID.NIL,
                name = "P",
                description = "",
                acceptedInputType = "JSON",
                triggered = false,
                version = 0L,
                graph = any(),
                key = "",
                api = false,
                public = false,
                schedule = null,
                maxConcurrentRuns = null,
                maxRunsPerMinute = null,
            )
        }
    }

    @Test
    fun `save passes through all present input fields`() = runTest {
        schedulerExists = false
        val id = UUID.random()
        val saved = pipeline(id = id, gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved

        controller.save(
            auth,
            input(
                id = id,
                description = "desc",
                tags = listOf("release"),
                triggered = true,
                key = "k",
                api = true,
                public = true,
                maxConcurrentRuns = 3,
                maxRunsPerMinute = 10,
                version = 7,
            ),
        )

        coVerify(exactly = 1) {
            service.save(
                id = id,
                name = "P",
                description = "desc",
                acceptedInputType = "JSON",
                triggered = true,
                version = 7L,
                graph = any(),
                tags = listOf("release"),
                key = "k",
                api = true,
                public = true,
                schedule = null,
                maxConcurrentRuns = 3,
                maxRunsPerMinute = 10,
            )
        }
    }

    @Test
    fun `save with a git-linked pipeline and author pushes to git`() = runTest {
        schedulerExists = false
        gitExists = true
        val saved = pipeline(gitRepositoryId = UUID.random())
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        coEvery { gitSync.pushToGit(any(), any(), any()) } returns PipelineSyncResult.Ok("sha")

        controller.save(auth, input(), authorName = "Ada", authorEmail = "ada@x.io")

        coVerify(exactly = 1) { gitSync.pushToGit(saved.id, "Ada", "ada@x.io") }
    }

    @Test
    fun `save with no git repository id never pushes`() = runTest {
        schedulerExists = false
        val saved = pipeline(gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved

        controller.save(auth, input(), authorName = "Ada", authorEmail = "ada@x.io")

        coVerify(exactly = 0) { gitSync.pushToGit(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------------------
    // pushAfterSave (via save)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `pushAfterSave is skipped when git sync is unavailable`() = runTest {
        schedulerExists = false
        gitExists = false
        val saved = pipeline(gitRepositoryId = UUID.random())
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved

        controller.save(auth, input(), authorName = "Ada", authorEmail = "ada@x.io")
        coVerify(exactly = 0) { gitSync.pushToGit(any(), any(), any()) }
    }

    @Test
    fun `pushAfterSave is skipped when author name is null`() = runTest {
        schedulerExists = false
        gitExists = true
        val saved = pipeline(gitRepositoryId = UUID.random())
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved

        controller.save(auth, input(), authorName = null, authorEmail = "ada@x.io")
        coVerify(exactly = 0) { gitSync.pushToGit(any(), any(), any()) }
    }

    @Test
    fun `pushAfterSave is skipped when author email is null`() = runTest {
        schedulerExists = false
        gitExists = true
        val saved = pipeline(gitRepositoryId = UUID.random())
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved

        controller.save(auth, input(), authorName = "Ada", authorEmail = null)
        coVerify(exactly = 0) { gitSync.pushToGit(any(), any(), any()) }
    }

    @Test
    fun `pushAfterSave logs a non-Ok sync result but the save still succeeds`() = runTest {
        schedulerExists = false
        gitExists = true
        val saved = pipeline(gitRepositoryId = UUID.random())
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        coEvery { gitSync.pushToGit(any(), any(), any()) } returns PipelineSyncResult.Failure("nope")

        val result = controller.save(auth, input(), authorName = "Ada", authorEmail = "ada@x.io")
        assertSame(saved, result)
        coVerify(exactly = 1) { gitSync.pushToGit(saved.id, "Ada", "ada@x.io") }
    }

    @Test
    fun `pushAfterSave swallows a push exception but the save still succeeds`() = runTest {
        schedulerExists = false
        gitExists = true
        val saved = pipeline(gitRepositoryId = UUID.random())
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        coEvery { gitSync.pushToGit(any(), any(), any()) } throws RuntimeException("io")

        val result = controller.save(auth, input(), authorName = "Ada", authorEmail = "ada@x.io")
        assertSame(saved, result)
    }

    @Test
    fun `pushAfterSave rethrows cancellation`() = runTest {
        schedulerExists = false
        gitExists = true
        val saved = pipeline(gitRepositoryId = UUID.random())
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        coEvery { gitSync.pushToGit(any(), any(), any()) } throws CancellationException("cancel")

        assertFailsWith<CancellationException> {
            controller.save(auth, input(), authorName = "Ada", authorEmail = "ada@x.io")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // syncSchedule (via save / delete)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `syncSchedule is a no-op when the scheduler is unavailable`() = runTest {
        schedulerExists = false
        val saved = pipeline(schedule = "0 0 * * *", gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved

        controller.save(auth, input(schedule = "0 0 * * *"))
        coVerify(exactly = 0) { scheduler.getJobs(any(), any(), any()) }
    }

    @Test
    fun `syncSchedule creates a new job when none exists and a cron is set`() = runTest {
        schedulerExists = true
        val saved = pipeline(name = "Nightly", schedule = "0 0 * * *", gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        coEvery { scheduler.getJobs(any(), any(), any()) } returns emptyList()

        controller.save(auth, input(name = "Nightly", schedule = "0 0 * * *"))

        val captured = slot<ScheduledJobInput>()
        coVerify(exactly = 1) { scheduler.createJob(capture(captured), UUID.NIL) }
        assertEquals(PipelineScheduledRunExecutor.NAME, captured.captured.jobName)
        assertEquals("0 0 * * *", captured.captured.cronExpression)
        assertEquals("Pipeline schedule: Nightly", captured.captured.name)
        coVerify(exactly = 0) { scheduler.updateJob(any(), any()) }
    }

    @Test
    fun `syncSchedule updates the existing job when one already matches the pipeline`() = runTest {
        schedulerExists = true
        val pipelineId = UUID.random()
        val saved = pipeline(id = pipelineId, schedule = "5 4 * * *", gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        val jobId = UUID.random()
        coEvery { scheduler.getJobs(any(), any(), any()) } returns listOf(existingScheduledJob(jobId, pipelineId))

        controller.save(auth, input(schedule = "5 4 * * *"))

        coVerify(exactly = 1) { scheduler.updateJob(jobId, any()) }
        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
    }

    @Test
    fun `syncSchedule deletes the existing job when the cron is cleared`() = runTest {
        schedulerExists = true
        val pipelineId = UUID.random()
        val saved = pipeline(id = pipelineId, schedule = null, gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        val jobId = UUID.random()
        coEvery { scheduler.getJobs(any(), any(), any()) } returns listOf(existingScheduledJob(jobId, pipelineId))

        controller.save(auth, input(schedule = null))

        coVerify(exactly = 1) { scheduler.deleteJob(jobId) }
        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
        coVerify(exactly = 0) { scheduler.updateJob(any(), any()) }
    }

    @Test
    fun `syncSchedule with a blank cron and no existing job deletes nothing`() = runTest {
        schedulerExists = true
        val saved = pipeline(schedule = "   ", gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        coEvery { scheduler.getJobs(any(), any(), any()) } returns emptyList()

        controller.save(auth, input(schedule = "   "))

        coVerify(exactly = 0) { scheduler.deleteJob(any()) }
        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
        coVerify(exactly = 0) { scheduler.updateJob(any(), any()) }
    }

    @Test
    fun `syncSchedule ignores a scheduled job whose parameters do not decode`() = runTest {
        schedulerExists = true
        val pipelineId = UUID.random()
        val saved = pipeline(id = pipelineId, schedule = "0 0 * * *", gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        // A matching-name job whose parameters are garbage: runCatching decode -> null != pipelineId, no match.
        val garbage = existingScheduledJob(UUID.random(), pipelineId).copy(jobParameters = buildJsonObject { put("nope", "x") })
        coEvery { scheduler.getJobs(any(), any(), any()) } returns listOf(garbage)

        controller.save(auth, input(schedule = "0 0 * * *"))

        // No match found -> create a fresh job rather than update the un-decodable one.
        coVerify(exactly = 1) { scheduler.createJob(any(), UUID.NIL) }
        coVerify(exactly = 0) { scheduler.updateJob(any(), any()) }
    }

    @Test
    fun `syncSchedule ignores a job with a different job name`() = runTest {
        schedulerExists = true
        val pipelineId = UUID.random()
        val saved = pipeline(id = pipelineId, schedule = "0 0 * * *", gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        val otherJob = existingScheduledJob(UUID.random(), pipelineId).copy(jobName = "some-other-job")
        coEvery { scheduler.getJobs(any(), any(), any()) } returns listOf(otherJob)

        controller.save(auth, input(schedule = "0 0 * * *"))

        coVerify(exactly = 1) { scheduler.createJob(any(), UUID.NIL) }
        coVerify(exactly = 0) { scheduler.updateJob(any(), any()) }
    }

    @Test
    fun `syncSchedule swallows a scheduler exception so the save still succeeds`() = runTest {
        schedulerExists = true
        val saved = pipeline(schedule = "0 0 * * *", gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        coEvery { scheduler.getJobs(any(), any(), any()) } throws RuntimeException("scheduler down")

        val result = controller.save(auth, input(schedule = "0 0 * * *"))
        assertSame(saved, result)
    }

    @Test
    fun `syncSchedule rethrows cancellation`() = runTest {
        schedulerExists = true
        val saved = pipeline(schedule = "0 0 * * *", gitRepositoryId = null)
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
        coEvery { scheduler.getJobs(any(), any(), any()) } throws CancellationException("cancel")

        assertFailsWith<CancellationException> { controller.save(auth, input(schedule = "0 0 * * *")) }
    }

    // ---------------------------------------------------------------------------------------------
    // backfill
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `backfill rejects a non-admin`() = runTest {
        rejectAdmin()
        assertFailsWith<IllegalStateException> {
            controller.backfill(auth, UUID.random(), emptyList(), "Ada", "ada@x.io")
        }
    }

    @Test
    fun `backfill returns a not-available result when git sync is unavailable`() = runTest {
        gitExists = false
        val result = controller.backfill(auth, UUID.random(), emptyList(), "Ada", "ada@x.io")
        assertFalse(result.ok)
        assertEquals("Git sync is not available on this server", result.errorMessage)
        coVerify(exactly = 0) { gitSync.backfill(any(), any(), any(), any()) }
    }

    @Test
    fun `backfill maps entries and returns the ok sync result`() = runTest {
        gitExists = true
        val repoId = UUID.random()
        val entryId = UUID.random()
        coEvery { gitSync.backfill(any(), any(), any(), any()) } returns PipelineSyncResult.Ok("abc123")

        val result = controller.backfill(
            auth,
            repoId,
            listOf(PipelineBackfillEntryInput(entryId, "pipelines/x.yaml")),
            "Ada",
            "ada@x.io",
        )

        assertTrue(result.ok)
        assertEquals("abc123", result.commitSha)
        val captured = slot<List<PipelineBackfillEntry>>()
        coVerify(exactly = 1) { gitSync.backfill(repoId, capture(captured), "Ada", "ada@x.io") }
        assertEquals(entryId, captured.captured.single().pipelineId)
        assertEquals("pipelines/x.yaml", captured.captured.single().gitPath)
    }

    @Test
    fun `backfill maps a validation-failed sync result`() = runTest {
        gitExists = true
        coEvery { gitSync.backfill(any(), any(), any(), any()) } returns
            PipelineSyncResult.ValidationFailed(listOf(PipelineRepoValidationError("p", "bad")))

        val result = controller.backfill(auth, UUID.random(), emptyList(), "Ada", "ada@x.io")
        assertFalse(result.ok)
        assertEquals(listOf(PipelineRepoValidationError("p", "bad")), result.validationErrors)
    }

    @Test
    fun `backfill maps a failure sync result`() = runTest {
        gitExists = true
        coEvery { gitSync.backfill(any(), any(), any(), any()) } returns PipelineSyncResult.Failure("boom")

        val result = controller.backfill(auth, UUID.random(), emptyList(), "Ada", "ada@x.io")
        assertFalse(result.ok)
        assertEquals("boom", result.errorMessage)
    }

    // ---------------------------------------------------------------------------------------------
    // delete
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `delete rejects a non-admin`() = runTest {
        rejectAdmin()
        assertFailsWith<IllegalStateException> { controller.delete(auth, UUID.random()) }
        coVerify(exactly = 0) { service.delete(any()) }
    }

    @Test
    fun `delete removes the pipeline and tears down its schedule when it existed`() = runTest {
        schedulerExists = true
        val id = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, schedule = "0 0 * * *")
        coEvery { scheduler.getJobs(any(), any(), any()) } returns emptyList()

        assertTrue(controller.delete(auth, id))
        coVerify(exactly = 1) { service.delete(id) }
        // The teardown sync runs with a null schedule -> no create/update, just a getJobs scan.
        coVerify(exactly = 1) { scheduler.getJobs(any(), any(), any()) }
        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
    }

    @Test
    fun `delete on a missing pipeline does not sync a schedule`() = runTest {
        schedulerExists = true
        val id = UUID.random()
        coEvery { service.get(id) } returns null

        assertTrue(controller.delete(auth, id))
        coVerify(exactly = 1) { service.delete(id) }
        coVerify(exactly = 0) { scheduler.getJobs(any(), any(), any()) }
    }

    @Test
    fun `delete still removes a pipeline whose broken graph cannot be read`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } throws IllegalArgumentException("unknown node type")

        assertTrue(controller.delete(auth, id))

        coVerify(exactly = 1) { service.delete(id) }
        coVerify(exactly = 0) { scheduler.getJobs(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------------------
    // clone
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `clone rejects a non-admin`() = runTest {
        rejectAdmin()
        assertFailsWith<IllegalStateException> { controller.clone(auth, UUID.random()) }
    }

    @Test
    fun `clone fails when the source pipeline is missing`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } returns null
        val ex = assertFailsWith<IllegalStateException> { controller.clone(auth, id) }
        assertTrue(ex.message?.contains("Pipeline not found") == true)
    }

    @Test
    fun `clone copies the source graph under a new inactive pipeline`() = runTest {
        val id = UUID.random()
        val source = pipeline(id = id, name = "Orig").copy(description = "d", acceptedInputType = "JSON")
        coEvery { service.get(id) } returns source
        val graph = buildJsonObject { put("nodes", "x") }
        coEvery { service.graphAsJsonElement(source) } returns graph
        val cloned = pipeline(name = "Orig (copy)")
        coEvery {
            service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns cloned

        val result = controller.clone(auth, id)
        assertSame(cloned, result)
        coVerify(exactly = 1) {
            service.save(
                id = UUID.NIL,
                name = "Orig (copy)",
                description = "d",
                acceptedInputType = "JSON",
                triggered = false,
                version = 0,
                graph = graph,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // dryRun
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `dryRun fails when the pipeline is missing`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } returns null
        val ex = assertFailsWith<IllegalStateException> {
            controller.dryRun(auth, id, null, JsonObject(emptyMap()))
        }
        assertTrue(ex.message?.contains("Pipeline not found") == true)
    }

    @Test
    fun `dryRun on a public pipeline skips the permission check and records the trace`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, public = true)
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)

        val result = controller.dryRun(auth, id, null, JsonObject(emptyMap()))
        assertNull(result.error)
        assertTrue(result.outputs is JsonObject)
        coVerify(exactly = 0) { permissionEvaluator.verifyAllowed(any(), any(), any()) }
        coVerify(exactly = 1) { executor.execute(any(), any(), any(), any()) }
    }

    @Test
    fun `dryRun on a non-public pipeline enforces EXECUTE permission`() = runTest {
        val id = UUID.random()
        val pl = pipeline(id = id, public = false)
        coEvery { service.get(id) } returns pl
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(null)

        controller.dryRun(auth, id, null, JsonObject(emptyMap()))
        coVerify(exactly = 1) { permissionEvaluator.verifyAllowed(auth, pl, PermissionAction.EXECUTE) }
    }

    @Test
    fun `dryRun reports a thrown execution error in the result`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, public = true)
        coEvery { executor.execute(any(), any(), any(), any()) } throws RuntimeException("kaboom")

        val result = controller.dryRun(auth, id, null, JsonObject(emptyMap()))
        assertEquals("kaboom", result.error)
    }

    @Test
    fun `dryRun reports the toString when a thrown error has no message`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, public = true)
        coEvery { executor.execute(any(), any(), any(), any()) } throws RuntimeException()

        val result = controller.dryRun(auth, id, null, JsonObject(emptyMap()))
        assertTrue(result.error?.contains("RuntimeException") == true)
    }

    @Test
    fun `dryRun fails when execution suspends in an inline context`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, public = true)
        coEvery { executor.execute(any(), any(), any(), any()) } returns
            ExecutionResult.Suspended(ExecutionState(emptyMap(), setOf("n")), emptyList())

        val result = controller.dryRun(auth, id, null, JsonObject(emptyMap()))
        assertTrue(result.error?.contains("suspended") == true)
    }

    @Test
    fun `dryRun rethrows cancellation from the executor`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, public = true)
        coEvery { executor.execute(any(), any(), any(), any()) } throws CancellationException("cancel")

        assertFailsWith<CancellationException> { controller.dryRun(auth, id, null, JsonObject(emptyMap())) }
    }

    @Test
    fun `dryRun rejects a non-public pipeline the caller cannot execute`() = runTest {
        val id = UUID.random()
        val pl = pipeline(id = id, public = false)
        coEvery { service.get(id) } returns pl
        coEvery { permissionEvaluator.verifyAllowed(any(), any(), any()) } throws IllegalStateException("forbidden")

        assertFailsWith<IllegalStateException> { controller.dryRun(auth, id, null, JsonObject(emptyMap())) }
        coVerify(exactly = 0) { executor.execute(any(), any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------------------
    // run
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `run fails when the pipeline is missing`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } returns null
        val ex = assertFailsWith<IllegalStateException> { controller.run(auth, id, JsonObject(emptyMap())) }
        assertTrue(ex.message?.contains("Pipeline not found") == true)
    }

    @Test
    fun `run on a non-public pipeline enforces EXECUTE permission`() = runTest {
        val id = UUID.random()
        val pl = pipeline(id = id, public = false)
        coEvery { service.get(id) } returns pl
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns
            PipelineRun(id = UUID.random(), pipelineId = id, status = PipelineRunStatus.OK, graphSnapshot = JsonObject(emptyMap()))

        controller.run(auth, id, JsonObject(emptyMap()))
        coVerify(exactly = 1) { permissionEvaluator.verifyAllowed(auth, pl, PermissionAction.EXECUTE) }
    }

    @Test
    fun `run maps a successful on-demand outcome`() = runTest {
        val id = UUID.random()
        val runId = UUID.random()
        val out = buildJsonObject { put("done", true) }
        coEvery { service.get(id) } returns pipeline(id = id, public = true)
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns
            PipelineRun(id = runId, pipelineId = id, status = PipelineRunStatus.OK, graphSnapshot = JsonObject(emptyMap()), output = out, error = null)

        val result = controller.run(auth, id, JsonObject(emptyMap()))
        assertTrue(result.ok)
        assertEquals(runId, result.runId)
        assertEquals(PipelineRunStatus.OK, result.status)
        assertEquals(out, result.output)
        assertNull(result.error)
        coVerify(exactly = 1) { runService.start(any(), any(), "manual", any(), auth) }
    }

    @Test
    fun `run maps a failed on-demand outcome with ok false`() = runTest {
        val id = UUID.random()
        val runId = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, public = true)
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns
            PipelineRun(id = runId, pipelineId = id, status = PipelineRunStatus.FAILED, graphSnapshot = JsonObject(emptyMap()), output = null, error = "node blew up")

        val result = controller.run(auth, id, JsonObject(emptyMap()))
        assertFalse(result.ok)
        assertEquals(PipelineRunStatus.FAILED, result.status)
        assertEquals("node blew up", result.error)
    }

    @Test
    fun `run maps a cancelled on-demand outcome with ok false`() = runTest {
        val id = UUID.random()
        val runId = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, public = true)
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns
            PipelineRun(
                id = runId,
                pipelineId = id,
                status = PipelineRunStatus.CANCELLED,
                graphSnapshot = JsonObject(emptyMap()),
                error = "cancelled",
            )

        val result = controller.run(auth, id, JsonObject(emptyMap()))

        assertFalse(result.ok)
        assertEquals(PipelineRunStatus.CANCELLED, result.status)
    }

    @Test
    fun `run reports admission shedding without inventing a run id`() = runTest {
        val id = UUID.random()
        coEvery { service.get(id) } returns pipeline(id = id, name = "Capped", public = true)
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns null

        val result = controller.run(auth, id, JsonObject(emptyMap()))

        assertFalse(result.ok)
        assertEquals(UUID.NIL, result.runId)
        assertEquals(PipelineRunStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("Capped"))
    }

    @Test
    fun `run reports an input-resolution failure without recording a run`() = runTest {
        val id = UUID.random()
        // A JSON pipeline with a schema that the empty-object payload violates makes
        // resolvePipelineRunInput throw (check failed), exercising the catch arm.
        coEvery { service.get(id) } returns schemaPipeline(id)

        val result = controller.run(auth, id, JsonObject(emptyMap()))
        assertFalse(result.ok)
        assertEquals(UUID.NIL, result.runId)
        assertEquals(PipelineRunStatus.FAILED, result.status)
        assertNull(result.output)
        assertTrue(result.error?.contains("input schema") == true)
        coVerify(exactly = 0) { runService.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `run rejects a non-public pipeline the caller cannot execute`() = runTest {
        val id = UUID.random()
        val pl = pipeline(id = id, public = false)
        coEvery { service.get(id) } returns pl
        coEvery { permissionEvaluator.verifyAllowed(any(), any(), any()) } throws IllegalStateException("forbidden")

        assertFailsWith<IllegalStateException> { controller.run(auth, id, JsonObject(emptyMap())) }
        coVerify(exactly = 0) { runService.start(any(), any(), any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------------------
    // restartRun
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `restartRun rejects a non-admin`() = runTest {
        rejectAdmin()
        assertFailsWith<IllegalStateException> { controller.restartRun(auth, UUID.random()) }
        coVerify(exactly = 0) { runService.restart(any()) }
    }

    @Test
    fun `restartRun delegates and returns the new run`() = runTest {
        val runId = UUID.random()
        val restarted = PipelineRun(pipelineId = UUID.random(), graphSnapshot = JsonObject(emptyMap()))
        coEvery { runService.restart(runId) } returns restarted

        assertSame(restarted, controller.restartRun(auth, runId))
        coVerify(exactly = 1) { runService.restart(runId) }
    }

    @Test
    fun `restartRun returns null when the source run is gone`() = runTest {
        val runId = UUID.random()
        coEvery { runService.restart(runId) } returns null
        assertNull(controller.restartRun(auth, runId))
    }

    @Test
    fun `deleteRun delegates and returns the repository outcome`() = runTest {
        val runId = UUID.random()
        coEvery { runService.delete(runId) } returns true

        assertTrue(controller.deleteRun(auth, runId))
        coVerify(exactly = 1) { runService.delete(runId) }
    }

    // ---------------------------------------------------------------------------------------------
    // setSecret / deleteSecret
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `setSecret rejects a non-admin`() = runTest {
        rejectAdmin()
        assertFailsWith<IllegalStateException> { controller.setSecret(auth, "API_KEY", "v") }
        coVerify(exactly = 0) { secretService.setSecret(any(), any()) }
    }

    @Test
    fun `setSecret delegates and returns the metadata`() = runTest {
        val secret = PipelineSecret(name = "API_KEY")
        coEvery { secretService.setSecret("API_KEY", "v") } returns secret
        assertSame(secret, controller.setSecret(auth, "API_KEY", "v"))
        coVerify(exactly = 1) { secretService.setSecret("API_KEY", "v") }
    }

    @Test
    fun `deleteSecret rejects a non-admin`() = runTest {
        rejectAdmin()
        assertFailsWith<IllegalStateException> { controller.deleteSecret(auth, "API_KEY") }
        coVerify(exactly = 0) { secretService.deleteSecret(any()) }
    }

    @Test
    fun `deleteSecret delegates and returns true`() = runTest {
        assertTrue(controller.deleteSecret(auth, "API_KEY"))
        coVerify(exactly = 1) { secretService.deleteSecret("API_KEY") }
    }

    @Test
    fun `shape mutations decode fields and delegate`() = runTest {
        val fields = listOf(ShapeField("id", "String"), ShapeField("active", "Boolean"))
        val saved = PipelineNamedShape("request", fields)
        coEvery { shapeService.save("request", any()) } returns saved

        val input = buildJsonArray {
            add(buildJsonObject { put("name", "id"); put("type", "String") })
            add(buildJsonObject { put("name", "active"); put("type", "Boolean") })
        }
        assertSame(saved, controller.saveShape(auth, "request", input))
        assertTrue(controller.deleteShape(auth, "request"))

        coVerify(exactly = 1) {
            shapeService.save(
                "request",
                match { decoded -> decoded.map { it.name to it.type } == fields.map { it.name to it.type } },
            )
        }
        coVerify(exactly = 1) { shapeService.delete("request") }
    }

    // ---------------------------------------------------------------------------------------------
    // addPermission / deletePermission
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `addPermission rejects a non-admin`() = runTest {
        rejectAdmin()
        assertFailsWith<IllegalStateException> { controller.addPermission(auth, permissionInput()) }
        coVerify(exactly = 0) { service.addPermission(any(), any(), any()) }
    }

    @Test
    fun `addPermission delegates to the service`() = runTest {
        val perm = permissionInput()
        assertTrue(controller.addPermission(auth, perm))
        coVerify(exactly = 1) { service.addPermission(perm.entityId, perm.groupId, perm.action) }
    }

    @Test
    fun `deletePermission rejects a non-admin`() = runTest {
        rejectAdmin()
        assertFailsWith<IllegalStateException> { controller.deletePermission(auth, permissionInput()) }
        coVerify(exactly = 0) { service.deletePermission(any(), any(), any()) }
    }

    @Test
    fun `deletePermission delegates to the service`() = runTest {
        val perm = permissionInput()
        assertTrue(controller.deletePermission(auth, perm))
        coVerify(exactly = 1) { service.deletePermission(perm.entityId, perm.groupId, perm.action) }
    }

    // ---------------------------------------------------------------------------------------------
    // PipelineInput @Serializable round-trip + equality arms
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `PipelineInput round-trips through json`() {
        val original = PipelineInput(
            id = null,
            name = "P",
            description = "d",
            acceptedInputType = "JSON",
            tags = listOf("release"),
            triggered = true,
            key = "k",
            api = true,
            public = false,
            schedule = "0 0 * * *",
            maxConcurrentRuns = 3,
            maxRunsPerMinute = 10,
            version = 2,
            graph = buildJsonObject { put("nodes", "x") },
        )
        val encoded = json.encodeToString(PipelineInput.serializer(), original)
        val decoded = json.decodeFromString(PipelineInput.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(
            listOf("partial"),
            PipelineInput(
                name = "Partial",
                acceptedInputType = "JSON",
                tags = listOf("partial"),
                graph = JsonObject(emptyMap()),
            ).tags,
        )
    }

    @Test
    fun `PipelineInput decodes a minimal json using defaults`() {
        val decoded = json.decodeFromString(
            PipelineInput.serializer(),
            """{"name":"P","acceptedInputType":"JSON","graph":{}}""",
        )
        assertNull(decoded.id)
        assertEquals("P", decoded.name)
        assertNull(decoded.description)
        assertNull(decoded.triggered)
        assertNull(decoded.maxConcurrentRuns)
        assertEquals(JsonObject(emptyMap()), decoded.graph)
    }

    @Test
    fun `PipelineBackfillEntryInput round-trips through json`() {
        val original = PipelineBackfillEntryInput(pipelineId = UUID.random(), gitPath = "pipelines/x.yaml")
        val encoded = json.encodeToString(PipelineBackfillEntryInput.serializer(), original)
        assertEquals(original, json.decodeFromString(PipelineBackfillEntryInput.serializer(), encoded))
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private fun existingScheduledJob(jobId: UUID, pipelineId: UUID): ScheduledJob = ScheduledJob(
        id = jobId,
        name = "Pipeline schedule",
        jobName = PipelineScheduledRunExecutor.NAME,
        jobParameters = buildJsonObject { put("pipelineId", pipelineId.toString()) },
        cronExpression = "0 0 * * *",
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        createdBy = UUID.NIL,
    )

    private fun schemaPipeline(id: UUID): Pipeline {
        // An InputNode declaring JSON input with a schema requiring a property the empty payload lacks.
        val schema = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add(JsonPrimitive("needed")) })
        }
        val inputNode = InputNode(
            id = "in",
            acceptedType = InputNode.JSON_TYPE,
            schema = schema,
        )
        return pipeline(id = id, public = true, acceptedInputType = InputNode.JSON_TYPE)
            .copy(nodes = listOf(inputNode))
    }

    private fun permissionInput() = PermissionInput(
        action = PermissionAction.EXECUTE,
        entityId = UUID.random(),
        groupId = UUID.random(),
    )

    @Test
    fun `provideInput stages the value and resumes the run after an EXECUTE check`() = runTest {
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        provides<PipelineRunResultStore> { store }
        val pipe = pipeline()
        val runId = UUID.random()
        coEvery { runService.get(runId) } returns mockk { every { pipelineId } returns pipe.id }
        coEvery { service.get(pipe.id) } returns pipe
        val value = buildJsonObject { put("decision", "approved") }

        val ok = controller.provideInput(auth, runId, "approve", value)

        assertTrue(ok)
        coVerify { permissionEvaluator.verifyAllowed(auth, pipe, PermissionAction.EXECUTE) }
        coVerify { store.put(runId, "approve", value, any()) }
        coVerify { runService.resume(runId, "approve", true, null) }
    }

    @Test
    fun `provideInput fails when the run is gone`() = runTest {
        coEvery { runService.get(any()) } returns null
        assertFailsWith<IllegalStateException> { controller.provideInput(auth, UUID.random(), "approve", JsonObject(emptyMap())) }
    }

    @Test
    fun `provideInput fails when the run pipeline is gone`() = runTest {
        val runId = UUID.random()
        val missingPipelineId = UUID.random()
        coEvery { runService.get(runId) } returns mockk { every { pipelineId } returns missingPipelineId }
        coEvery { service.get(missingPipelineId) } returns null

        assertFailsWith<IllegalStateException> {
            controller.provideInput(auth, runId, "approve", JsonObject(emptyMap()))
        }
    }

    @Test
    fun `resolveGate approval resumes WITHOUT overwriting the staged output — the inbound value flows on`() = runTest {
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        provides<PipelineRunResultStore> { store }
        val pipe = pipeline()
        val runId = UUID.random()
        coEvery { runService.get(runId) } returns mockk { every { pipelineId } returns pipe.id }
        coEvery { service.get(pipe.id) } returns pipe

        val ok = controller.resolveGate(auth, runId, "gate", approved = true)

        assertTrue(ok)
        coVerify { permissionEvaluator.verifyAllowed(auth, pipe, PermissionAction.EXECUTE) }
        // The critical difference from provideInput: the gate's staged INBOUND value must survive.
        coVerify(exactly = 0) { store.put(any(), any(), any(), any()) }
        coVerify { runService.resume(runId, "gate", true, null) }
    }

    @Test
    fun `resolveGate rejection fails the run at the gate with the note`() = runTest {
        val pipe = pipeline()
        val runId = UUID.random()
        coEvery { runService.get(runId) } returns mockk { every { pipelineId } returns pipe.id }
        coEvery { service.get(pipe.id) } returns pipe

        val ok = controller.resolveGate(auth, runId, "gate", approved = false, note = "not this week")

        assertTrue(ok)
        coVerify { runService.resume(runId, "gate", false, "Gate rejected: not this week") }
    }

    @Test
    fun `resolveGate rejection without a meaningful note uses the generic reason`() = runTest {
        val pipe = pipeline()
        val runId = UUID.random()
        coEvery { runService.get(runId) } returns mockk { every { pipelineId } returns pipe.id }
        coEvery { service.get(pipe.id) } returns pipe

        assertTrue(controller.resolveGate(auth, runId, "gate", approved = false, note = null))
        assertTrue(controller.resolveGate(auth, runId, "gate", approved = false, note = "  "))

        coVerify(exactly = 2) { runService.resume(runId, "gate", false, "Gate rejected") }
    }

    @Test
    fun `resolveGate fails when its run or pipeline no longer exists`() = runTest {
        val missingRun = UUID.random()
        coEvery { runService.get(missingRun) } returns null
        assertFailsWith<IllegalStateException> {
            controller.resolveGate(auth, missingRun, "gate", approved = true)
        }

        val runId = UUID.random()
        val missingPipelineId = UUID.random()
        coEvery { runService.get(runId) } returns mockk { every { pipelineId } returns missingPipelineId }
        coEvery { service.get(missingPipelineId) } returns null
        assertFailsWith<IllegalStateException> {
            controller.resolveGate(auth, runId, "gate", approved = true)
        }
    }
}
