@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.service

import bosca.artifacts.model.ArtifactCompleted
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.repository.*
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.ArtifactRepositoryServiceImpl
import bosca.workops.service.RegistryProducedArtifactVerifier

import bosca.db.*
import bosca.di.*
import bosca.git.ci.configuration.KubernetesCiDispatchConfiguration
import bosca.git.ci.repository.*
import bosca.git.model.*
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineLogService
import bosca.git.service.PipelineRunService
import bosca.git.service.ProducedArtifactVerifier
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.pipelines.PipelineEventDispatcher
import bosca.pipelines.trigger.PipelineDispatchJob
import bosca.pipelines.trigger.PipelineEventDispatcherImpl
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Real migrations, generated job associations and event dispatch, and transaction-deferred NATS jobs. */
class PipelineArtifactCompletionIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var nats: SharedNatsContainer
    private lateinit var pool: ConnectionPool
    private lateinit var json: Json
    private lateinit var queue: JobQueue
    private lateinit var queueName: String
    private lateinit var jobs: PipelineJobService
    private lateinit var runs: PipelineRunService
    private lateinit var run: PipelineRun
    private lateinit var graphService: bosca.pipelines.service.PipelineService
    private lateinit var verifier: ProducedArtifactVerifier
    private lateinit var artifacts: ArtifactRepositoryService
    private lateinit var version: bosca.artifacts.model.ArtifactVersion
    private val artifact = ArtifactDefinition("raw", "builds", "tool:1.0.0")
    private val eventName = "bosca.artifacts.model.ArtifactCompleted"

    @BeforeTest fun setup() = runBlocking {
        ProviderRegistry.clear()
        postgres = SharedPostgreSQLContainer().withDatabaseName("ci_artifact_completion")
        postgres.start()
        nats = SharedNatsContainer()
        nats.start()
        pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 5,
        ), key = "artifact-completion-test"))
        json = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    development: false\n".byteInputStream())).json
        provides<ConnectionPool>(singleton = true) { pool }
        provides<bosca.cache.CacheManager> { mockk(relaxed = true) }
        provides<bosca.cache.RequestCacheSerializer> { mockk(relaxed = true) }
        val natsPool = nats.newConnectionPool(1)
        queueName = "artifact-completion-${UUID.random()}"
        queue = NatsJobQueueFactory(natsPool, json, NatsDistributedLockFactory(natsPool), null, emptyList(), false).create(queueName)
        queue.expireAllJobs()
        provides<JobQueue>(name = bosca.pipelines.configuration.PipelinesJobQueueNames.jobQueue, singleton = true) { queue }
        graphService = mockk()
        coEvery { graphService.triggeredEventTypes() } returns setOf(eventName)
        provides<PipelineEventDispatcher>(singleton = true) { PipelineEventDispatcherImpl(graphService, json) }
        artifacts = ArtifactRepositoryServiceImpl(NamespaceRepositoryImpl(), NamespacePermissionRepositoryImpl(),
            ArtifactRepoRepositoryImpl(), VersionRepositoryImpl(), TagRepositoryImpl(), UploadSessionRepositoryImpl(),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        verifier = spyk(RegistryProducedArtifactVerifier(artifacts))
        provides<ProducedArtifactVerifier>(singleton = true) { verifier }
        jobs = PipelineJobServiceImpl(PipelineJobRepositoryImpl(), PipelineStepRepositoryImpl(),
            mockk<PipelineLogService>(relaxed = true), json, KubernetesCiDispatchConfiguration.disabled,
            mockk<bosca.kubernetes.service.KubernetesJobDispatchService>(relaxed = true).asProvider())
        provides<PipelineJobService>(singleton = true) { jobs }
        runs = mockk()
        coEvery { runs.findById(any()) } coAnswers { PipelineRunRepositoryImpl().findById(firstArg()) }
        coEvery { runs.findByIdForUpdate(any()) } coAnswers { PipelineRunRepositoryImpl().findByIdForUpdate(firstArg()) }
        coEvery { runs.updateStatus(any(), any()) } coAnswers { PipelineRunRepositoryImpl().markFinished(firstArg(), secondArg()) }
        provides<PipelineRunService>(singleton = true) { runs }
        withDb {
            connection().useStatement("DROP SCHEMA IF EXISTS git CASCADE") { it.execute() }
            connection().useStatement("DROP SCHEMA IF EXISTS artifacts CASCADE") { it.execute() }
            val paths = listOf(
                "test/V1__test_minimal_schema.sql", "V12__ci_cd_pipelines.sql", "V17__pipeline_step_definitions.sql",
                "V21__pipeline_step_error_message.sql", "V22__pipeline_job_error_message.sql", "V25__pipeline_job_artifacts.sql",
                "V26__pipeline_job_requirements.sql", "V28__pipeline_job_pipeline_requirements.sql", "V29__pipeline_job_attempt.sql",
                "V31__pipeline_job_deferred_condition.sql", "V32__pipeline_job_environment_approval.sql", "V33__pipeline_run_parameters.sql",
                "V34__pipeline_secret_permissions.sql", "V35__pipeline_job_kubernetes_dispatch.sql", "V38__pipeline_job_requirement_bypass.sql",
                "V43__pipeline_catalog_archival.sql",
            )
            for (path in paths) {
                val sql = javaClass.getResource("/db/migrations/$path")?.readText() ?: error("Missing migration $path")
                connection().useStatement(sql) { it.execute() }
            }
            for (name in ArtifactsMigration().resources) {
                val sql = javaClass.getResource("/db/migrations/$name")?.readText() ?: error("Missing migration $name")
                connection().useStatement(sql) { it.execute() }
            }
            version = uploadedVersion("tool")
            val repositoryId = UUID.random()
            connection().useStatement("INSERT INTO git.repositories (id, slug, name, owner_id) VALUES ('$repositoryId', 'tool', 'Tool', '${UUID.random()}')") { it.execute() }
            val pipeline = PipelineRepositoryImpl().create(Pipeline(repositoryId = repositoryId,
                filePath = ".bosca/pipelines/build.yaml", name = "Build", configHash = "test"))
            run = PipelineRunRepositoryImpl().create(PipelineRun(
                pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "1".repeat(40),
                ref = "refs/tags/v1.0.0", triggerType = PipelineTriggerType.TAG, triggeredBy = UUID.random(),
                number = 1, status = PipelineRunStatus.RUNNING,
            ))
        }
    }

    @AfterTest fun cleanup() = runBlocking {
        if (::pool.isInitialized) pool.close()
        if (::postgres.isInitialized) postgres.stop()
        if (::nats.isInitialized) nats.stop()
        ProviderRegistry.clear()
    }

    private suspend fun <T> withDb(block: suspend () -> T): T = withConnectionManager { block() }
    private suspend fun uploadedVersion(name: String): bosca.artifacts.model.ArtifactVersion {
        val repository = artifacts.findOrCreateRepository("builds", name, ArtifactType.RAW)
        val created = artifacts.createVersion(repository.id, "1.0.0")
        val digest = "sha256:" + "a".repeat(64)
        BlobRepositoryImpl().insert(digest, 1)
        artifacts.addVersionBlob(created.id, digest, "file", "$name.zip", "application/zip")
        return created
    }
    private suspend fun producers(vararg names: String): List<PipelineJob> = jobs.createJobs(run.id, names.associateWith {
        JobDefinition(artifacts = listOf(artifact), steps = listOf(StepDefinition(name = "Upload", run = "true")))
    })
    private suspend fun queuedCount(): Long = nats.newConnection().jetStreamManagement().getStreamInfo("jobs-$queueName").streamState.msgCount
    private suspend fun payload(): ArtifactCompleted {
        val job = assertNotNull(queue.dequeue())
        val definition = json.decodeFromJsonElement(PipelineDispatchJob.serializer(), job.getDefinition())
        assertEquals(eventName, definition.eventName)
        val event = json.decodeFromJsonElement(ArtifactCompleted.serializer(), definition.eventPayload)
        queue.markComplete(job)
        return event
    }

    @Test fun `successful producer dispatches through the normal pipeline queue before unrelated jobs finish`() = runBlocking {
        withDb {
            val producer = producers("publish").single()
            jobs.createJobs(run.id, mapOf("notify" to JobDefinition(steps = listOf(StepDefinition(name = "Notify", run = "true")))))
            assertTrue(jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS))
            PipelineRunFinalizer(jobs, runs, mockk<PipelineAgentService>(relaxed = true))
                .finalizeJob(producer.id, PipelineRunStatus.SUCCESS, releaseAgent = false)
            assertEquals(PipelineRunStatus.RUNNING, assertNotNull(runs.findById(run.id)).status)
            assertEquals(1L, queuedCount())
            val event = payload()
            assertEquals(listOf(producer.id), event.jobIds)
            assertEquals(version.id, event.versionId)
            assertEquals(run.commitSha, event.commitSha)
            assertEquals(run.triggeredBy, event.principalId)
            assertFalse(jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS))
            assertEquals(0L, queuedCount())
        }
    }

    @Test fun `job artifacts round trip through JSONB as a structured list including environments`() = runBlocking {
        withDb {
            val definitions = listOf(artifact.copy(environments = listOf("production", "staging")),
                ArtifactDefinition("maven", "libraries", "io.bosca:core:1.0.0"))
            val producer = jobs.createJobs(run.id, mapOf("typed" to JobDefinition(artifacts = definitions))).single()
            assertEquals(definitions, producer.artifacts)
            assertEquals(definitions, assertNotNull(jobs.findById(producer.id)).artifacts)
            val empty = jobs.createJobs(run.id, mapOf("empty" to JobDefinition())).single()
            assertTrue(assertNotNull(jobs.findById(empty.id)).artifacts.isEmpty())
        }
    }

    @Test fun `shared artifact waits for every verified producer and dispatches once`() = runBlocking {
        withDb {
            val producers = producers("linux", "macos")
            jobs.finishIfActive(producers[0].id, PipelineRunStatus.SUCCESS)
            assertEquals(0L, queuedCount())
            jobs.finishIfActive(producers[1].id, PipelineRunStatus.SUCCESS)
            assertEquals(1L, queuedCount())
            assertEquals(producers.map { it.id }.toSet(), payload().jobIds.toSet())
            assertFalse(jobs.finishIfActive(producers[0].id, PipelineRunStatus.SUCCESS))
            assertFalse(jobs.finishIfActive(producers[1].id, PipelineRunStatus.SUCCESS))
            assertEquals(0L, queuedCount())
        }
    }

    @Test fun `failed cancelled and skipped producers block only their own artifact`() = runBlocking {
        withDb {
            for (status in listOf(PipelineRunStatus.FAILURE, PipelineRunStatus.CANCELLED, PipelineRunStatus.SKIPPED)) {
                val producers = producers("success-$status", "blocked-$status")
                jobs.finishIfActive(producers[0].id, PipelineRunStatus.SUCCESS)
                jobs.finishIfActive(producers[1].id, status)
            }
            assertEquals(0L, queuedCount())
            val other = ArtifactDefinition("raw", "builds", "other:1.0.0")
            val otherVersion = uploadedVersion("other")
            val producer = jobs.createJobs(run.id, mapOf("other" to JobDefinition(artifacts = listOf(other)))).single()
            jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS)
            assertEquals(1L, queuedCount())
            assertEquals(otherVersion.id, payload().versionId)
        }
    }

    @Test fun `rollback restores active producer and emits no event`() = runBlocking {
        withDb {
            val producer = producers("publish").single()
            assertFailsWith<IllegalStateException> {
                transaction {
                    jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS)
                    assertEquals(PipelineRunStatus.SUCCESS, jobs.findById(producer.id)?.status)
                    assertEquals(0L, queuedCount())
                    error("rollback")
                }
            }
            assertEquals(PipelineRunStatus.QUEUED, jobs.findById(producer.id)?.status)
            assertEquals(0L, queuedCount())
            jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS)
            assertEquals(1L, queuedCount())
        }
    }

    @Test fun `empty stored versions cannot announce completion and roll back producer success`() = runBlocking {
        withDb {
            val repository = artifacts.findOrCreateRepository("builds", "empty", ArtifactType.RAW)
            artifacts.createVersion(repository.id, "1.0.0")
            val producer = jobs.createJobs(run.id, mapOf("empty" to JobDefinition(
                artifacts = listOf(ArtifactDefinition("raw", "builds", "empty:1.0.0")),
            ))).single()
            assertFailsWith<IllegalArgumentException> { jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS) }
            assertEquals(PipelineRunStatus.QUEUED, jobs.findById(producer.id)?.status)
            assertEquals(0L, queuedCount())
        }
    }

    @Test fun `an outer transaction defers ordinary event dispatch until commit`() = runBlocking {
        withDb {
            val producer = producers("publish").single()
            transaction {
                jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS)
                assertEquals(0L, queuedCount())
            }
            assertEquals(1L, queuedCount())
        }
    }

    @Test fun `a producer with a missing second output cannot prematurely complete its shared artifact`() = runBlocking {
        withDb {
            val missing = ArtifactDefinition("raw", "builds", "missing:1.0.0")
            coEvery { verifier.missing(any()) } coAnswers {
                firstArg<List<ArtifactDefinition>>().filter { it.coordinate == missing.coordinate }
            }
            val producers = jobs.createJobs(run.id, mapOf(
                "linux" to JobDefinition(artifacts = listOf(artifact, missing)),
                "macos" to JobDefinition(artifacts = listOf(artifact)),
            )).associateBy { it.name }
            val linux = producers.getValue("linux")
            val macos = producers.getValue("macos")
            jobs.finishIfActive(macos.id, PipelineRunStatus.SUCCESS)
            assertEquals(0L, queuedCount())
            jobs.finishIfActive(linux.id, PipelineRunStatus.SUCCESS)
            val failed = assertNotNull(jobs.findById(linux.id))
            assertEquals(PipelineRunStatus.FAILURE, failed.status)
            assertContains(failed.errorMessage.orEmpty(), missing.coordinate)
            assertEquals(0L, queuedCount())
            PipelineRunFinalizer(jobs, runs, mockk<PipelineAgentService>(relaxed = true))
                .finalizeJob(linux.id, PipelineRunStatus.SUCCESS, releaseAgent = false)
            assertEquals(PipelineRunStatus.FAILURE, runs.findById(run.id)?.status)
        }
    }

    @Test fun `an unavailable old output does not block completion of a later healthy artifact`() = runBlocking {
        withDb {
            val missing = ArtifactDefinition("raw", "builds", "removed:0.1.0")
            coEvery { verifier.missing(any()) } coAnswers {
                firstArg<List<ArtifactDefinition>>().filter { it.coordinate == missing.coordinate }
            }
            val old = jobs.createJobs(run.id, mapOf("old" to JobDefinition(artifacts = listOf(missing)))).single()
            val healthy = producers("healthy").single()
            jobs.finishIfActive(old.id, PipelineRunStatus.SUCCESS)
            jobs.finishIfActive(healthy.id, PipelineRunStatus.SUCCESS)
            assertEquals(PipelineRunStatus.FAILURE, jobs.findById(old.id)?.status)
            assertEquals(1L, queuedCount())
            assertEquals(version.id, payload().versionId)
        }
    }

    @Test fun `concurrent final producers serialize success and emit one occurrence`() = runBlocking {
        val producers = withDb { producers("linux", "macos") }
        coroutineScope {
            producers.map { producer -> async {
                withDb { jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS) }
            } }.awaitAll().forEach { assertTrue(it) }
        }
        withDb {
            assertEquals(1L, queuedCount())
            assertEquals(producers.map { it.id }.toSet(), payload().jobIds.toSet())
        }
    }

    @Test fun `gate completion uses the same verified terminal transition`() = runBlocking {
        withDb {
            val gate = jobs.createJobs(run.id, mapOf("gate" to JobDefinition(artifacts = listOf(artifact)))).single()
            assertEquals(listOf(gate.id), jobs.completeGateJobs(run.id).map { it.id })
            assertEquals(PipelineRunStatus.SUCCESS, jobs.findById(gate.id)?.status)
            assertEquals(1L, queuedCount())
            assertEquals(listOf(gate.id), payload().jobIds)
        }
    }

    @Test fun `missing verifier and verification errors persist failure before dispatch`() = runBlocking {
        withDb {
            val producer = producers("publish").single()
            coEvery { verifier.missing(any()) } throws IllegalStateException("registry unreachable")
            jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS)
            assertEquals(PipelineRunStatus.FAILURE, jobs.findById(producer.id)?.status)
            assertContains(jobs.findById(producer.id)?.errorMessage.orEmpty(), "registry unreachable")
            ProviderRegistry.clear()
            provides<Json>(singleton = true) { json }
            provides<PipelineRunService>(singleton = true) { runs }
            val unverified = producers("unverified").single()
            jobs.finishIfActive(unverified.id, PipelineRunStatus.SUCCESS)
            assertEquals(PipelineRunStatus.FAILURE, jobs.findById(unverified.id)?.status)
            assertContains(jobs.findById(unverified.id)?.errorMessage.orEmpty(), "no artifact verifier")
            assertEquals(0L, queuedCount())
        }
    }

    @Test fun `verification cancellation preserves active state and propagates`() = runBlocking {
        withDb {
            val producer = producers("publish").single()
            val cancellation = CancellationException("cancelled")
            coEvery { verifier.missing(any()) } throws cancellation
            assertSame(cancellation, assertFailsWith<CancellationException> {
                jobs.finishIfActive(producer.id, PipelineRunStatus.SUCCESS)
            })
            assertEquals(PipelineRunStatus.QUEUED, jobs.findById(producer.id)?.status)
            assertEquals(0L, queuedCount())
        }
    }
}
