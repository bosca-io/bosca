@file:OptIn(bosca.core.annotations.Internal::class, bosca.di.annotation.InternalDI::class)

package bosca.git.ci.jobs

import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.JobDefinition
import bosca.git.model.Pipeline
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerJob
import bosca.git.model.PipelineTriggerType
import bosca.git.model.Repository
import bosca.git.model.RepositoryPermission
import bosca.git.model.StepDefinition
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.CommitStatusService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineService
import bosca.git.service.RepositoryService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PipelineTriggerAuthorizationTest {
    private val security = mockk<SecurityService>(relaxed = true)
    private val repositories = mockk<RepositoryService>(relaxed = true)
    private val pipelines = mockk<PipelineService>(relaxed = true)
    private val runs = mockk<PipelineRunService>(relaxed = true)
    private val statuses = mockk<CommitStatusService>(relaxed = true)
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val queue = mockk<JobQueue>(relaxed = true)
    private val connection = mockk<ConnectionManager>(relaxed = true)
    private val principalId = UUID.random()
    private val buildGroup = Group(UUID.random(), "builders", "Builders", GroupType.SYSTEM)
    private val repository = Repository(
        id = UUID.random(), ownerId = UUID.random(), slug = "repo", name = "Repo", visibility = Visibility.PUBLIC,
    )
    private val pipeline = Pipeline(
        id = UUID.random(), repositoryId = repository.id, filePath = ".bosca/pipelines/build.yaml",
        name = "Build", configHash = "hash",
        triggers = Json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(PipelineTrigger.serializer()),
            listOf(PipelineTrigger(PipelineTriggerType.PUSH)),
        ),
    )
    private val definition = PipelineDefinition(
        name = "Build",
        triggers = listOf(PipelineTrigger(PipelineTriggerType.PUSH)),
        jobs = mapOf("build" to JobDefinition(steps = listOf(StepDefinition(name = "Build", run = "build")))),
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<SecurityService> { security }
        provides<RepositoryService> { repositories }
        provides<PipelineService> { pipelines }
        provides<PipelineRunService> { runs }
        provides<CommitStatusService> { statuses }
        provides<RepositoryPermissionEvaluator> { RepositoryPermissionEvaluator(repositories, security, groups) }
        coEvery { security.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(buildGroup)
        coEvery { repositories.findById(repository.id) } returns repository
        coEvery { repositories.getPermissions(repository) } returns listOf(
            RepositoryPermission(repository.id, buildGroup.id, PermissionAction.EXECUTE),
        )
        coEvery { pipelines.syncPipelines(repository.id, any(), any()) } returns listOf(pipeline)
        coEvery { pipelines.parseDefinition(repository.id, any(), pipeline.filePath) } returns definition
        coEvery { runs.createTriggeredRun(any(), any(), any(), any(), any(), any(), any(), any()) } returns PipelineRun(
            pipelineId = pipeline.id, repositoryId = repository.id, ref = "refs/heads/main",
            commitSha = "after", triggerType = PipelineTriggerType.PUSH,
        )
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `unattributed push cannot start CI in a public repository`() = runTest {
        execute(null)
        coVerify(exactly = 1) { pipelines.syncPipelines(repository.id, "refs/heads/main", "after") }
        coVerify(exactly = 0) { runs.createTriggeredRun(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown or deleted principal cannot start CI`() = runTest {
        coEvery { security.getPrincipalById(principalId) } returns null
        execute(principalId)
        coEvery { security.getPrincipalById(principalId) } returns Principal(
            id = principalId, deletedAt = java.time.OffsetDateTime.now(),
        )
        execute(principalId)
        coVerify(exactly = 2) { pipelines.syncPipelines(repository.id, "refs/heads/main", "after") }
        coVerify(exactly = 0) { runs.createTriggeredRun(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `edit-only push refreshes the catalog without starting builds`() = runTest {
        coEvery { repositories.getPermissions(repository) } returns listOf(
            RepositoryPermission(repository.id, buildGroup.id, PermissionAction.EDIT),
        )
        execute(principalId)
        execute(principalId)
        coVerify(exactly = 2) { pipelines.syncPipelines(repository.id, "refs/heads/main", "after") }
        coVerify(exactly = 0) { pipelines.parseDefinition(any(), any(), any()) }
        coVerify(exactly = 0) { runs.createTriggeredRun(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `authorized branch build retains principal and reads the triggering commit`() = runTest {
        execute(principalId)
        coVerify(exactly = 1) { pipelines.parseDefinition(repository.id, "after", pipeline.filePath) }
        coVerify(exactly = 1) {
            runs.createTriggeredRun(any(), pipeline.id, repository.id, definition, "after", "refs/heads/main",
                PipelineTriggerType.PUSH, principalId)
        }
    }

    @Test
    fun `tag build requires the same execution grant`() = runTest {
        val tagDefinition = definition.copy(triggers = listOf(PipelineTrigger(PipelineTriggerType.TAG, tags = listOf("v*"))))
        coEvery { pipelines.syncPipelines(repository.id, any(), any()) } returns listOf(
            pipeline.copy(triggers = Json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(PipelineTrigger.serializer()), tagDefinition.triggers,
            )),
        )
        coEvery { pipelines.parseDefinition(repository.id, "after", pipeline.filePath) } returns tagDefinition
        execute(principalId, "refs/tags/v1")
        coVerify(exactly = 1) {
            runs.createTriggeredRun(any(), pipeline.id, repository.id, tagDefinition, "after", "refs/tags/v1",
                PipelineTriggerType.TAG, principalId)
        }
        coEvery { repositories.getPermissions(repository) } returns emptyList()
        execute(principalId, "refs/tags/v1")
        coVerify(exactly = 1) { runs.createTriggeredRun(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `editors and managers start builds without a repository grant`() = runTest {
        coEvery { repositories.getPermissions(repository) } returns emptyList()
        for (name in listOf("editors", "managers")) {
            coEvery { security.getPrincipalGroups(principalId) } returns listOf(
                Group(UUID.random(), name, name, GroupType.SYSTEM),
            )
            execute(principalId)
        }
        coVerify(exactly = 2) {
            runs.createTriggeredRun(any(), pipeline.id, repository.id, any(), "after", "refs/heads/main",
                PipelineTriggerType.PUSH, principalId)
        }
    }

    @Test
    fun `revoked execution group prevents replay from creating another build`() = runTest {
        execute(principalId)
        coEvery { security.getPrincipalGroups(principalId) } returns emptyList()
        execute(principalId)
        coVerify(exactly = 1) { runs.createTriggeredRun(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing repository fails before pipeline synchronization`() = runTest {
        coEvery { repositories.findById(repository.id) } returns null
        assertFailsWith<NoSuchElementException> { execute(principalId) }
        coVerify(exactly = 0) { pipelines.syncPipelines(any(), any(), any()) }
    }

    @Test
    fun `cancellation propagates from authorization and run creation`() = runTest {
        coEvery { security.getPrincipalGroups(principalId) } throws CancellationException("cancel")
        assertFailsWith<CancellationException> { execute(principalId) }
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(buildGroup)
        coEvery { runs.createTriggeredRun(any(), any(), any(), any(), any(), any(), any(), any()) } throws CancellationException("cancel")
        assertFailsWith<CancellationException> { execute(principalId) }
    }

    @Test
    fun `run creation failure remains visible to the job retry path`() = runTest {
        coEvery { runs.createTriggeredRun(any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("database")
        assertFailsWith<IllegalStateException> { execute(principalId) }
    }

    @Test
    fun `failing pipeline does not block later pipelines and still fails the job`() = runTest {
        val broken = pipeline.copy(id = UUID.random(), filePath = ".bosca/pipelines/broken.yaml", name = "Broken")
        val later = pipeline.copy(id = UUID.random(), filePath = ".bosca/pipelines/later.yaml", name = "Later")
        coEvery { pipelines.syncPipelines(repository.id, any(), any()) } returns listOf(pipeline, broken, later)
        coEvery { pipelines.parseDefinition(repository.id, any(), broken.filePath) } throws IllegalStateException("broken")
        coEvery { pipelines.parseDefinition(repository.id, any(), later.filePath) } returns definition

        val failure = assertFailsWith<IllegalStateException> { execute(principalId) }

        kotlin.test.assertEquals("broken", failure.message)
        coVerify(exactly = 1) { runs.createTriggeredRun(any(), pipeline.id, any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { runs.createTriggeredRun(any(), later.id, any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rejected plan skips that pipeline without failing the job`() = runTest {
        val rejected = pipeline.copy(id = UUID.random(), filePath = ".bosca/pipelines/deploy.yaml", name = "Deploy")
        coEvery { pipelines.syncPipelines(repository.id, any(), any()) } returns listOf(rejected, pipeline)
        coEvery { pipelines.parseDefinition(repository.id, any(), rejected.filePath) } returns definition
        coEvery {
            runs.createTriggeredRun(any(), rejected.id, any(), any(), any(), any(), any(), any())
        } throws bosca.git.model.PipelinePlanRejectedException("Every job in 'Deploy' was excluded")

        execute(principalId)

        coVerify(exactly = 1) { runs.createTriggeredRun(any(), pipeline.id, any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `every pipeline failure is reported on the job failure`() = runTest {
        val second = pipeline.copy(id = UUID.random(), filePath = ".bosca/pipelines/second.yaml", name = "Second")
        coEvery { pipelines.syncPipelines(repository.id, any(), any()) } returns listOf(pipeline, second)
        coEvery { pipelines.parseDefinition(repository.id, any(), pipeline.filePath) } throws IllegalStateException("first")
        coEvery { pipelines.parseDefinition(repository.id, any(), second.filePath) } throws IllegalArgumentException("second")

        val failure = assertFailsWith<IllegalStateException> { execute(principalId) }

        kotlin.test.assertEquals("first", failure.message)
        // Coroutine stack-trace recovery may rethrow a copy whose cause is the original exception.
        val original = generateSequence<Throwable>(failure) { it.cause }.last()
        kotlin.test.assertEquals(listOf("second"), original.suppressed.map { it.message })
    }

    @Test
    fun `already processed trigger does not reset commit statuses`() = runTest {
        coEvery { runs.createTriggeredRun(any(), any(), any(), any(), any(), any(), any(), any()) } returns null
        execute(principalId)
        coVerify(exactly = 0) { statuses.recordStatus(any(), any(), any(), any(), any(), any()) }
    }

    private suspend fun execute(actor: UUID?, ref: String = "refs/heads/main") {
        val job = InternalJobConstructor(
            definition = Json.encodeToJsonElement(
                PipelineTriggerJob.serializer(), PipelineTriggerJob(repository.id, ref, "before", "after", actor),
            ),
            executor = PipelineTriggerExecutor::class,
        )
        job.setPersistentId(UUID.random())
        withContext(queue.asCoroutineContext(job) + connection.asCoroutineContext()) { PipelineTriggerExecutor().execute() }
    }
}
