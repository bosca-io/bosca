@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.AgentMode
import bosca.git.model.AgentStatus
import bosca.git.model.Pipeline
import bosca.git.model.PipelineAgent
import bosca.git.model.PipelineArtifact
import bosca.git.model.PipelineConcurrency
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineSecret
import bosca.git.model.PipelineStep
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTrigger
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTriggerType
import bosca.git.model.Visibility
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.asCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for CI/CD pipeline repositories against a real
 * PostgreSQL instance via Testcontainers. Verifies that all KSP-generated
 * SQL queries, JSONB casts, enum casts, and unique constraints work
 * correctly against a live database.
 */
class CiPipelineRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_ci_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "test",
            )
        )

        private var schemaInitialized = false
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val pipelineRepo = PipelineRepositoryImpl()
    private val runRepo = PipelineRunRepositoryImpl()
    private val jobRepo = PipelineJobRepositoryImpl()
    private val stepRepo = PipelineStepRepositoryImpl()
    private val artifactRepo = PipelineArtifactRepositoryImpl()
    private val agentRepo = PipelineAgentRepositoryImpl()
    private val secretRepo = PipelineSecretRepositoryImpl()

    private val ownerId = UUID.random()
    private lateinit var repositoryId: UUID

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            withDb {
                transaction {
                    // The container is REUSED across JVMs (withReuse) — a previous run's schema (possibly
                    // older than the current migration list) may still be there. Rebuild from scratch so
                    // the schema always matches this run's migration list.
                    connection().useStatement("drop schema if exists git cascade") { it.execute() }
                    val minimalSchema = CiPipelineRepositoryIntegrationTest::class.java
                        .getResourceAsStream("/db/migrations/test/V1__test_minimal_schema.sql")!!
                        .bufferedReader().readText()
                    connection().useStatement(minimalSchema) { it.execute() }

                    val ciMigrations = listOf(
                        "/db/migrations/V12__ci_cd_pipelines.sql",
                        "/db/migrations/V16__pipeline_artifacts.sql",
                        "/db/migrations/V17__pipeline_step_definitions.sql",
                        "/db/migrations/V21__pipeline_step_error_message.sql",
                        "/db/migrations/V22__pipeline_job_error_message.sql",
                        "/db/migrations/V25__pipeline_job_artifacts.sql",
                        "/db/migrations/V26__pipeline_job_requirements.sql",
                        "/db/migrations/V28__pipeline_job_pipeline_requirements.sql",
                        "/db/migrations/V29__pipeline_job_attempt.sql",
                        "/db/migrations/V30__release_promotion_trigger_types.sql",
                        "/db/migrations/V31__pipeline_job_deferred_condition.sql",
                        "/db/migrations/V32__pipeline_job_environment_approval.sql",
                        "/db/migrations/V33__pipeline_run_parameters.sql",
                        "/db/migrations/V34__pipeline_secret_permissions.sql",
                        "/db/migrations/V35__pipeline_job_kubernetes_dispatch.sql",
                        "/db/migrations/V38__pipeline_job_requirement_bypass.sql",
                        "/db/migrations/V41__pipeline_trigger_occurrences.sql",
                        "/db/migrations/V43__pipeline_catalog_archival.sql",
                    )
                    for (path in ciMigrations) {
                        val sql = CiPipelineRepositoryIntegrationTest::class.java
                            .getResourceAsStream(path)!!
                            .bufferedReader().readText()
                        connection().useStatement(sql) { it.execute() }
                    }
                }
            }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM git.pipeline_steps") { it.execute() }
                connection().useStatement("DELETE FROM git.pipeline_jobs") { it.execute() }
                connection().useStatement("DELETE FROM git.pipeline_runs") { it.execute() }
                connection().useStatement("DELETE FROM git.pipeline_secrets") { it.execute() }
                connection().useStatement("DELETE FROM git.pipelines") { it.execute() }
                connection().useStatement("UPDATE git.pipeline_agents SET job_id = NULL") { it.execute() }
                connection().useStatement("DELETE FROM git.pipeline_agents") { it.execute() }
                connection().useStatement("DELETE FROM git.repositories") { it.execute() }

                connection().useStatement("""
                    INSERT INTO git.repositories (id, slug, name, owner_id, visibility, default_branch)
                    VALUES ('${UUID.random()}'::uuid, 'test-repo', 'Test', '$ownerId'::uuid, 'private', 'main')
                    RETURNING id
                """.trimIndent()) { stmt ->
                    val rs = stmt.executeQuery()
                    rs.next()
                    repositoryId = UUID.parse(rs.getString("id"))
                }
            }
        }
    }

    // ─── Pipeline Repository ────────────────────────────────────

    @Test
    fun `pipeline create and findById roundtrips`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(
            repositoryId = repositoryId,
            filePath = ".bosca/pipelines/build.yaml",
            name = "Build",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH, branches = listOf("main"))).toJsonElement(),
            concurrency = PipelineConcurrency(group = "ci-main", cancelInProgress = true).toJsonElement(),
            configHash = "abc123"
        ))

        val found = pipelineRepo.findById(pipeline.id)
        assertNotNull(found)
        assertEquals("Build", found.name)
        assertEquals(".bosca/pipelines/build.yaml", found.filePath)
        assertEquals("abc123", found.configHash)
        val triggers = json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(PipelineTrigger.serializer()), found.triggers)
        assertEquals(1, triggers.size)
        assertEquals(PipelineTriggerType.PUSH, triggers[0].type)
        assertEquals(listOf("main"), triggers[0].branches)
        assertNotNull(found.concurrency)
        val concurrency = json.decodeFromJsonElement(PipelineConcurrency.serializer(), found.concurrency!!)
        assertEquals("ci-main", concurrency.group)
        assertTrue(concurrency.cancelInProgress)
    }

    @Test
    fun `pipeline unique constraint on repository_id and file_path`() = withDb {
        pipelineRepo.create(Pipeline(
            repositoryId = repositoryId,
            filePath = ".bosca/pipelines/build.yaml",
            name = "Build",
            configHash = "abc"
        ))

        try {
            pipelineRepo.create(Pipeline(
                repositoryId = repositoryId,
                filePath = ".bosca/pipelines/build.yaml",
                name = "Build Duplicate",
                configHash = "def"
            ))
            assertTrue(false, "Should have thrown")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `pipeline findByRepository returns all pipelines for repo`() = withDb {
        pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "a.yaml", name = "A", configHash = "h1"))
        pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "b.yaml", name = "B", configHash = "h2"))

        val found = pipelineRepo.findByRepository(repositoryId)
        assertEquals(2, found.size)
    }

    @Test
    fun `pipeline update changes name and config hash`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(
            repositoryId = repositoryId, filePath = "build.yaml", name = "Old", configHash = "old"
        ))

        val updated = pipelineRepo.update(pipeline.copy(name = "New", configHash = "new"))
        assertNotNull(updated)
        assertEquals("New", updated.name)
        assertEquals("new", updated.configHash)
    }

    @Test
    fun `pipeline delete removes record`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(
            repositoryId = repositoryId, filePath = "del.yaml", name = "Del", configHash = "h"
        ))
        pipelineRepo.delete(pipeline.id)
        assertNull(pipelineRepo.findById(pipeline.id))
    }

    @Test
    fun `pipeline findByRepositoryAndFilePath returns matching pipeline`() = withDb {
        pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "a.yaml", name = "A", configHash = "h1"))

        val found = pipelineRepo.findByRepositoryAndFilePath(repositoryId, "a.yaml")
        assertNotNull(found)
        assertEquals("A", found.name)

        assertNull(pipelineRepo.findByRepositoryAndFilePath(repositoryId, "nonexistent.yaml"))
    }

    // ─── Pipeline Run Repository ────────────────────────────────

    @Test
    fun `run create and findById with enum casts`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(
            repositoryId = repositoryId, filePath = "r.yaml", name = "R", configHash = "h"
        ))
        val run = runRepo.create(PipelineRun(
            pipelineId = pipeline.id,
            repositoryId = repositoryId,
            commitSha = "abc123def456",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.PUSH,
            status = PipelineRunStatus.QUEUED,
            number = 1,
            concurrencyGroup = "ci-main"
        ))

        val found = runRepo.findById(run.id)
        assertNotNull(found)
        assertEquals("abc123def456", found.commitSha)
        assertEquals(PipelineTriggerType.PUSH, found.triggerType)
        assertEquals(PipelineRunStatus.QUEUED, found.status)
        assertEquals(1, found.number)
        assertEquals("ci-main", found.concurrencyGroup)
    }

    @Test
    fun `run delete returns the run and cascades jobs steps and artifact records`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(
            repositoryId = repositoryId, filePath = "delete.yaml", name = "Delete", configHash = "h"
        ))
        val run = runRepo.create(PipelineRun(
            pipelineId = pipeline.id,
            repositoryId = repositoryId,
            commitSha = "abc123",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.PUSH,
            status = PipelineRunStatus.FAILURE,
            number = 3,
        ))
        val job = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "build"))
        val step = stepRepo.create(PipelineStep(pipelineJobId = job.id, name = "Build", ordinal = 0))
        artifactRepo.upsert(PipelineArtifact(
            repositoryId = repositoryId,
            pipelineRunId = run.id,
            runNumber = run.number,
            name = "server",
        ))

        assertEquals(run.id, runRepo.findByIdForUpdate(run.id)?.id)
        assertEquals(run.id, runRepo.delete(run.id)?.id)

        assertNull(runRepo.findById(run.id))
        assertNull(jobRepo.findById(job.id))
        assertNull(stepRepo.findById(step.id))
        assertTrue(artifactRepo.findByRun(run.id).isEmpty())
    }

    @Test
    fun `run markStarted sets started timestamp and status`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "s.yaml", name = "S", configHash = "h"))
        val run = runRepo.create(PipelineRun(
            pipelineId = pipeline.id, repositoryId = repositoryId,
            commitSha = "abc", ref = "main", triggerType = PipelineTriggerType.MANUAL,
            status = PipelineRunStatus.QUEUED, number = 1
        ))

        runRepo.markStarted(run.id, PipelineRunStatus.RUNNING)
        val found = runRepo.findById(run.id)!!
        assertEquals(PipelineRunStatus.RUNNING, found.status)
        assertNotNull(found.started)
    }

    @Test
    fun `run markFinished sets finished timestamp and status`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "f.yaml", name = "F", configHash = "h"))
        val run = runRepo.create(PipelineRun(
            pipelineId = pipeline.id, repositoryId = repositoryId,
            commitSha = "abc", ref = "main", triggerType = PipelineTriggerType.PUSH,
            status = PipelineRunStatus.RUNNING, number = 1
        ))

        runRepo.markFinished(run.id, PipelineRunStatus.SUCCESS)
        val found = runRepo.findById(run.id)!!
        assertEquals(PipelineRunStatus.SUCCESS, found.status)
        assertNotNull(found.finished)
    }

    @Test
    fun `run findByRepository orders by created desc`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "o.yaml", name = "O", configHash = "h"))
        runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "b", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 2))

        val runs = runRepo.findByRepository(repositoryId, 0, 10)
        assertEquals(2, runs.size)
        assertTrue(runs[0].number > runs[1].number || runs[0].created >= runs[1].created)
    }

    @Test
    fun `run findActiveByConcurrencyGroup returns queued and running only`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "c.yaml", name = "C", configHash = "h"))
        runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1, concurrencyGroup = "grp"))
        runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "b", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.SUCCESS, number = 2, concurrencyGroup = "grp"))

        val active = runRepo.findActiveByConcurrencyGroup("grp")
        assertEquals(1, active.size)
        assertEquals(PipelineRunStatus.QUEUED, active[0].status)
    }

    // ─── Pipeline Job Repository ────────────────────────────────

    @Test
    fun `job create with JSONB matrix values and array depends_on`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "j.yaml", name = "J", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))

        val job = jobRepo.create(PipelineJob(
            pipelineRunId = run.id,
            name = "test (java=21, os=linux)",
            runnerLabel = "linux-8cpu",
            matrixValues = kotlinx.serialization.json.buildJsonObject { put("java", "21"); put("os", "linux") },
            dependsOn = listOf("build", "lint")
        ))

        val found = jobRepo.findById(job.id)
        assertNotNull(found)
        assertEquals("test (java=21, os=linux)", found.name)
        assertEquals("linux-8cpu", found.runnerLabel)
        assertTrue(found.matrixValues.toString().contains("java"))
        assertEquals(listOf("build", "lint"), found.dependsOn)
    }

    @Test
    fun `a requirement-gated job is unclaimable until its satisfied stamp is set`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "req.yaml", name = "Req", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "refs/tags/v1", triggerType = PipelineTriggerType.TAG, status = PipelineRunStatus.QUEUED, number = 1))
        val requirements = kotlinx.serialization.json.Json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(bosca.git.model.ArtifactRequirement.serializer()),
            listOf(bosca.git.model.ArtifactRequirement("maven", "bosca-maven", "io.bosca:core-content:1.0")),
        )
        val gated = jobRepo.create(
            PipelineJob(
                pipelineRunId = run.id,
                name = "build",
                runnerLabel = "linux",
                requirements = requirements,
                requirementsDeadline = java.time.OffsetDateTime.now().plusMinutes(30),
            ),
        )

        // the gate — the claim query must skip a queued job with unverified requirements.
        assertEquals(null, jobRepo.findNextAvailable(listOf("linux"))?.id)
        // The checker's working set sees it (globally and per run).
        assertEquals(listOf(gated.id), jobRepo.findAwaitingRequirements().map { it.id })
        assertEquals(listOf(gated.id), jobRepo.findAwaitingRequirementsByRun(run.id).map { it.id })

        // Stamping (idempotently) flips the gate: claimable, and out of the working set.
        jobRepo.markRequirementsSatisfied(gated.id)
        jobRepo.markRequirementsSatisfied(gated.id)
        assertEquals(gated.id, jobRepo.findNextAvailable(listOf("linux"))?.id)
        assertEquals(emptyList(), jobRepo.findAwaitingRequirements().map { it.id })

        // Round-trip: the persisted row carries the requirement fields.
        val found = jobRepo.findById(gated.id)
        assertNotNull(found)
        assertTrue(found.requirements.toString().contains("io.bosca:core-content:1.0"))
        assertNotNull(found.requirementsSatisfiedAt)
        assertNotNull(found.requirementsDeadline)
    }

    @Test
    fun `requirement bypass is audited without bypassing same-run dependencies`() = withDb {
        val pipeline = pipelineRepo.create(
            Pipeline(repositoryId = repositoryId, filePath = "override.yaml", name = "Override", configHash = "h")
        )
        val run = runRepo.create(
            PipelineRun(
                pipelineId = pipeline.id,
                repositoryId = repositoryId,
                commitSha = "a",
                ref = "refs/heads/main",
                triggerType = PipelineTriggerType.PUSH,
                status = PipelineRunStatus.RUNNING,
                number = 1,
            )
        )
        val compile = jobRepo.create(
            PipelineJob(pipelineRunId = run.id, name = "compile", runnerLabel = "override-lane")
        )
        val gated = jobRepo.create(
            PipelineJob(
                pipelineRunId = run.id,
                name = "publish",
                runnerLabel = "override-lane",
                dependsOn = listOf("compile"),
                pipelineRequirements = Json.parseToJsonElement(
                    """[{"repository":"bosca","pipeline":"release"}]"""
                ),
            )
        )
        val actor = UUID.random()

        val bypassed = jobRepo.bypassRequirements(gated.id, actor, "upstream is being repaired")

        assertNotNull(bypassed)
        assertNotNull(bypassed.requirementsSatisfiedAt)
        assertNotNull(bypassed.requirementsBypassedAt)
        assertEquals(actor, bypassed.requirementsBypassedBy)
        assertEquals("upstream is being repaired", bypassed.requirementsBypassReason)

        val agent = agentRepo.create(
            PipelineAgent(name = "override-agent", labels = listOf("override-lane"), tokenHash = "hash")
        )
        assertNotNull(
            jobRepo.claimJobById(
                compile.id,
                agent.id,
                previousAgentId = null,
                status = PipelineRunStatus.RUNNING,
            )
        )
        assertNull(jobRepo.findNextAvailable(listOf("override-lane")))
    }

    @Test
    fun `end to end - a consumer's gated build parks until the provider's artifact publishes`() = withDb {
        // Two repos' pipelines through the REAL yaml parser — the same shapes as the workspace
        // (provider: publishes io.bosca:* maven modules) and bosca-server (consumer: requires them).
        val parser = bosca.git.ci.parser.PipelineYamlParser()
        val providerDef = parser.parse(
            """
            name: Provider Release
            on:
              tag:
                patterns: ["*"]
            jobs:
              publish:
                runner: linux
                artifacts:
                  - { type: maven, namespace: bosca-maven, coordinate: "io.bosca:core:${'$'}{{ version }}" }
                steps:
                  - { name: Publish, run: ./gradlew publish }
            """.trimIndent(),
            "provider.yaml",
        )
        val consumerDef = parser.parse(
            """
            name: Consumer Release
            on:
              tag:
                patterns: ["*"]
            jobs:
              build:
                runner: linux
                requires:
                  - { type: maven, namespace: bosca-maven, coordinate: "io.bosca:core:${'$'}{{ version }}", timeout: 30m }
                steps:
                  - { name: Build, run: ./gradlew build }
            """.trimIndent(),
            "consumer.yaml",
        )

        // Real services over the real database; the registry is faked at the SPI seam — exactly
        // where production wires the workops registry verifier (whose exact/prefix resolution has
        // its own tests in that module).
        val jobService = bosca.git.ci.service.PipelineJobServiceImpl(
            jobRepo, stepRepo, io.mockk.mockk(relaxed = true), json,
            bosca.git.ci.configuration.KubernetesCiDispatchConfiguration.disabled,
            io.mockk.mockk(relaxed = true),
        )
        val runService = bosca.git.ci.service.PipelineRunServiceImpl(
            runRepo, jobService, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), json,
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
        )
        provides<bosca.git.service.PipelineRunService>(singleton = true) { runService }
        val pipelineService = bosca.git.ci.service.PipelineServiceImpl(
            pipelineRepo, io.mockk.mockk(relaxed = true), bosca.git.ci.parser.PipelineYamlParser(),
            json, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
        )
        val checker = bosca.git.ci.service.PipelineRequirementChecker(
            jobService, runService, io.mockk.mockk(relaxed = true),
            pipelineService, io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true), json,
        )
        provides<bosca.git.ci.service.PipelineRequirementChecker>(singleton = true) { checker }
        val published = mutableSetOf<String>()
        provides<bosca.git.service.RequiredArtifactVerifier> {
            object : bosca.git.service.RequiredArtifactVerifier {
                override suspend fun unsatisfied(requirements: List<bosca.git.model.ArtifactRequirement>) =
                    requirements.filter { it.coordinate !in published }
            }
        }

        val providerPipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "provider.yaml", name = "Provider Release", configHash = "p"))
        val consumerPipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "consumer.yaml", name = "Consumer Release", configHash = "c"))

        // Both repos tag v9.9.9 "simultaneously" — the consumer's run is even created FIRST.
        val consumerRun = runService.createRun(consumerPipeline.id, repositoryId, consumerDef, "sha-c", "refs/tags/v9.9.9", PipelineTriggerType.TAG, null, emptyMap())
        runService.createRun(providerPipeline.id, repositoryId, providerDef, "sha-p", "refs/tags/v9.9.9", PipelineTriggerType.TAG, null, emptyMap())

        // The consumer's build parked with its coordinate RESOLVED (${{ version }} → 9.9.9); the
        // claim query skips it and hands an agent the provider's publish job instead, even though
        // the consumer's job is older.
        val parked = jobRepo.findAwaitingRequirementsByRun(consumerRun.id).single()
        assertTrue(parked.requirements.toString().contains("io.bosca:core:9.9.9"))
        assertEquals("publish", jobRepo.findNextAvailable(listOf("linux"))?.name)

        // The provider publishes — the registry event fires the checker (simulated directly here).
        published += "io.bosca:core:9.9.9"
        checker.checkAwaiting()

        // The gate is open: the consumer's build is now the oldest claimable job.
        assertEquals("build", jobRepo.findNextAvailable(listOf("linux"))?.name)
        assertNotNull(jobRepo.findById(parked.id)?.requirementsSatisfiedAt)
    }

    @Test
    fun `end to end - a gated build past its deadline fails naming the unmet coordinate`() = withDb {
        // The failure path publishes run-status updates.
        provides<bosca.pubsub.PubSubService>(singleton = true) { io.mockk.mockk(relaxed = true) }
        val jobService = bosca.git.ci.service.PipelineJobServiceImpl(
            jobRepo, stepRepo, io.mockk.mockk(relaxed = true), json,
            bosca.git.ci.configuration.KubernetesCiDispatchConfiguration.disabled,
            io.mockk.mockk(relaxed = true),
        )
        val runService = bosca.git.ci.service.PipelineRunServiceImpl(
            runRepo, jobService, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), json,
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
        )
        provides<bosca.git.service.PipelineRunService>(singleton = true) { runService }
        val pipelineService = bosca.git.ci.service.PipelineServiceImpl(
            pipelineRepo, io.mockk.mockk(relaxed = true), bosca.git.ci.parser.PipelineYamlParser(),
            json, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
        )
        val checker = bosca.git.ci.service.PipelineRequirementChecker(
            jobService, runService, io.mockk.mockk(relaxed = true),
            pipelineService, io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true), json,
        )
        provides<bosca.git.service.RequiredArtifactVerifier> {
            object : bosca.git.service.RequiredArtifactVerifier {
                override suspend fun unsatisfied(requirements: List<bosca.git.model.ArtifactRequirement>) = requirements
            }
        }

        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "late.yaml", name = "Late", configHash = "l"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "refs/tags/v1", triggerType = PipelineTriggerType.TAG, status = PipelineRunStatus.QUEUED, number = 1))
        val gated = jobRepo.create(
            PipelineJob(
                pipelineRunId = run.id,
                name = "build",
                runnerLabel = "linux",
                requirements = json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(bosca.git.model.ArtifactRequirement.serializer()),
                    listOf(bosca.git.model.ArtifactRequirement("maven", "bosca-maven", "io.bosca:never:1.0")),
                ),
                requirementsDeadline = java.time.OffsetDateTime.now().minusMinutes(1),
            ),
        )

        checker.checkAwaiting()

        // The job failed loudly, naming the artifact that never arrived, and the run failed with it.
        val failed = jobRepo.findById(gated.id)
        assertNotNull(failed)
        assertEquals(PipelineRunStatus.FAILURE, failed.status)
        assertTrue(failed.errorMessage.orEmpty().contains("io.bosca:never:1.0"))
        assertEquals(PipelineRunStatus.FAILURE, runRepo.findById(run.id)?.status)
    }

    /** Builds the real service stack for the e2e tests, with repository resolution faked
     *  at the service seam (the requirement names `acme/repo`, which resolves to this test's repo). */
    private fun pipelineRequirementStack(): Triple<bosca.git.ci.service.PipelineRunServiceImpl, bosca.git.ci.service.PipelineRequirementChecker, bosca.git.ci.parser.PipelineYamlParser> {
        val jobService = bosca.git.ci.service.PipelineJobServiceImpl(
            jobRepo, stepRepo, io.mockk.mockk(relaxed = true), json,
            bosca.git.ci.configuration.KubernetesCiDispatchConfiguration.disabled,
            io.mockk.mockk(relaxed = true),
        )
        val runService = bosca.git.ci.service.PipelineRunServiceImpl(
            runRepo, jobService, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), json,
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
        )
        provides<bosca.git.service.PipelineRunService>(singleton = true) { runService }
        val repositoryService = io.mockk.mockk<bosca.git.service.RepositoryService>(relaxed = true)
        io.mockk.coEvery { repositoryService.findByOwnerAndSlug("acme", "repo") } returns
            bosca.git.model.Repository(id = repositoryId, slug = "repo", name = "Repo", ownerId = ownerId)
        val pipelineService = bosca.git.ci.service.PipelineServiceImpl(
            pipelineRepo, io.mockk.mockk(relaxed = true), bosca.git.ci.parser.PipelineYamlParser(),
            json, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
        )
        val checker = bosca.git.ci.service.PipelineRequirementChecker(
            jobService, runService, io.mockk.mockk(relaxed = true),
            pipelineService, repositoryService, io.mockk.mockk(relaxed = true), json,
        )
        provides<bosca.git.ci.service.PipelineRequirementChecker>(singleton = true) { checker }
        return Triple(runService, checker, bosca.git.ci.parser.PipelineYamlParser())
    }

    @Test
    fun `end to end - a pipeline-gated build parks until the provider's run for the same tag succeeds`() = withDb {
        val (runService, checker, parser) = pipelineRequirementStack()
        val consumerDef = parser.parse(
            """
            name: Pipeline Consumer
            on:
              tag:
                patterns: ["*"]
            jobs:
              build:
                runner: spec6-lane
                requires:
                  - { pipeline: "Pipeline Provider", repository: acme/repo, timeout: 30m }
                steps:
                  - { name: Build, run: ./gradlew build }
            """.trimIndent(),
            "consumer.yaml",
        )
        val providerDef = parser.parse(
            """
            name: Pipeline Provider
            on:
              tag:
                patterns: ["*"]
            jobs:
              publish:
                runner: spec6-lane
                steps:
                  - { name: Publish, run: ./gradlew publish }
            """.trimIndent(),
            "provider.yaml",
        )
        val providerPipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "p6.yaml", name = "Pipeline Provider", configHash = "p6"))
        val consumerPipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "c6.yaml", name = "Pipeline Consumer", configHash = "c6"))

        // The consumer tags FIRST — its build parks with the correlation ref resolved to its own tag,
        // and the claim query skips it (nothing else claimable in this lane yet).
        val consumerRun = runService.createRun(consumerPipeline.id, repositoryId, consumerDef, "sha-c", "refs/tags/6.6.6", PipelineTriggerType.TAG, null, emptyMap())
        val parked = jobRepo.findAwaitingRequirementsByRun(consumerRun.id).single()
        assertTrue(parked.pipelineRequirements.toString().contains("refs/tags/6.6.6"))
        assertNull(jobRepo.findNextAvailable(listOf("spec6-lane")))

        // The provider's run appears but is still queued — the consumer keeps waiting; the agent
        // gets the provider's publish job (the consumer's older build stays gated).
        val providerRun = runService.createRun(providerPipeline.id, repositoryId, providerDef, "sha-p", "refs/tags/6.6.6", PipelineTriggerType.TAG, null, emptyMap())
        checker.checkAwaiting()
        assertNull(jobRepo.findById(parked.id)?.requirementsSatisfiedAt)
        assertEquals("publish", jobRepo.findNextAvailable(listOf("spec6-lane"))?.name)

        // The provider's run SUCCEEDS — the status event fires the checker (simulated directly) and
        // the gate opens: the consumer's build is now the oldest claimable job in the lane.
        runRepo.markFinished(providerRun.id, PipelineRunStatus.SUCCESS)
        checker.checkAwaiting()
        assertNotNull(jobRepo.findById(parked.id)?.requirementsSatisfiedAt)
        assertEquals("build", jobRepo.findNextAvailable(listOf("spec6-lane"))?.name)
    }

    @Test
    fun `end to end - a failed provider run fails the pipeline-gated build naming the dependency`() = withDb {
        // The failure path publishes run-status updates.
        provides<bosca.pubsub.PubSubService>(singleton = true) { io.mockk.mockk(relaxed = true) }
        val (runService, checker, parser) = pipelineRequirementStack()
        val consumerDef = parser.parse(
            """
            name: Doomed Consumer
            on:
              tag:
                patterns: ["*"]
            jobs:
              build:
                runner: spec6-fail-lane
                requires:
                  - { pipeline: "Doomed Provider", repository: acme/repo, timeout: 30m }
                steps:
                  - { name: Build, run: ./gradlew build }
            """.trimIndent(),
            "consumer.yaml",
        )
        val providerPipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "dp.yaml", name = "Doomed Provider", configHash = "dp"))
        val consumerPipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "dc.yaml", name = "Doomed Consumer", configHash = "dc"))

        val consumerRun = runService.createRun(consumerPipeline.id, repositoryId, consumerDef, "sha-c", "refs/tags/7.7.7", PipelineTriggerType.TAG, null, emptyMap())
        val parked = jobRepo.findAwaitingRequirementsByRun(consumerRun.id).single()

        val providerRun = runRepo.create(PipelineRun(pipelineId = providerPipeline.id, repositoryId = repositoryId, commitSha = "sha-p", ref = "refs/tags/7.7.7", triggerType = PipelineTriggerType.TAG, status = PipelineRunStatus.QUEUED, number = 1))
        runRepo.markFinished(providerRun.id, PipelineRunStatus.FAILURE)

        checker.checkAwaiting()

        // The consumer failed IMMEDIATELY — no deadline wait — naming the failed dependency.
        val failed = jobRepo.findById(parked.id)
        assertNotNull(failed)
        assertEquals(PipelineRunStatus.FAILURE, failed.status)
        assertTrue(failed.errorMessage.orEmpty().contains("Doomed Provider"))
        assertTrue(failed.errorMessage.orEmpty().contains("FAILURE"))
        assertEquals(PipelineRunStatus.FAILURE, runRepo.findById(consumerRun.id)?.status)
    }

    @Test
    fun `release and promotion trigger types round-trip through the database`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "rt.yaml", name = "RT", configHash = "rt"))
        val release = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "refs/heads/main", triggerType = PipelineTriggerType.RELEASE, status = PipelineRunStatus.QUEUED, number = 1))
        val promotion = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "refs/heads/main", triggerType = PipelineTriggerType.PROMOTION, status = PipelineRunStatus.QUEUED, number = 2))

        assertEquals(PipelineTriggerType.RELEASE, runRepo.findById(release.id)?.triggerType)
        assertEquals(PipelineTriggerType.PROMOTION, runRepo.findById(promotion.id)?.triggerType)
    }

    @Test
    fun `release run lookup correlates release and promotion runs by release id`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "release.yaml", name = "Release", configHash = "release"))
        val releaseId = UUID.random()
        val otherReleaseId = UUID.random()
        val releaseParameters = buildJsonObject { put("release.id", releaseId.toString()) }
        runRepo.create(PipelineRun(
            pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "refs/heads/main",
            triggerType = PipelineTriggerType.RELEASE, status = PipelineRunStatus.QUEUED, number = 1,
            parameters = releaseParameters,
        ))
        runRepo.create(PipelineRun(
            pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "b", ref = "refs/heads/main",
            triggerType = PipelineTriggerType.PROMOTION, status = PipelineRunStatus.QUEUED, number = 2,
            parameters = releaseParameters,
        ))
        runRepo.create(PipelineRun(
            pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "c", ref = "refs/heads/main",
            triggerType = PipelineTriggerType.RELEASE, status = PipelineRunStatus.QUEUED, number = 3,
            parameters = buildJsonObject { put("release.id", otherReleaseId.toString()) },
        ))

        val correlated = runRepo.findByReleaseId(releaseId.toString(), 0, 20)

        assertEquals(listOf(2, 1), correlated.map { it.number })
    }

    // ─── Deferred conditions: failure routing ───────────────────────

    /** A deploy job, a failure-routed rollback, and an always-run notify — the release-pipeline shape. */
    private val failureRoutedYaml = """
        name: Failure Routed
        on:
          tag:
            patterns: ["*"]
        jobs:
          deploy:
            runner: defer-lane
            steps:
              - { name: Deploy, run: ./deploy.sh }
          rollback:
            runner: defer-lane
            needs: [deploy]
            if: needs.deploy.result == 'failure'
            steps:
              - { name: Rollback, run: ./rollback.sh }
          notify:
            runner: defer-lane
            needs: [deploy]
            if: always()
            steps:
              - { name: Notify, run: ./notify.sh }
    """.trimIndent()

    private fun deferredStack(): Triple<bosca.git.ci.service.PipelineJobServiceImpl, bosca.git.ci.service.PipelineRunServiceImpl, bosca.git.ci.service.PipelineRunFinalizer> {
        provides<bosca.pubsub.PubSubService>(singleton = true) { io.mockk.mockk(relaxed = true) }
        val jobService = bosca.git.ci.service.PipelineJobServiceImpl(
            jobRepo, stepRepo, io.mockk.mockk(relaxed = true), json,
            bosca.git.ci.configuration.KubernetesCiDispatchConfiguration.disabled,
            io.mockk.mockk(relaxed = true),
        )
        val runService = bosca.git.ci.service.PipelineRunServiceImpl(
            runRepo, jobService, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), json,
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
        )
        provides<bosca.git.service.PipelineRunService>(singleton = true) { runService }
        val finalizer = bosca.git.ci.service.PipelineRunFinalizer(jobService, runService, io.mockk.mockk(relaxed = true))
        return Triple(jobService, runService, finalizer)
    }

    @Test
    fun `end to end - a failure-routed rollback job runs when its dependency fails`() = withDb {
        val (jobService, runService, finalizer) = deferredStack()
        val definition = bosca.git.ci.parser.PipelineYamlParser().parse(failureRoutedYaml, "fr.yaml")
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "fr.yaml", name = "Failure Routed", configHash = "fr"))
        val run = runService.createRun(pipeline.id, repositoryId, definition, "sha", "refs/tags/1.0.0", PipelineTriggerType.TAG, null, emptyMap())

        val jobs = jobRepo.findByRun(run.id).associateBy { it.name }
        // Deferred jobs exist with their conditions stored, unclaimable until evaluated.
        assertEquals("needs.deploy.result == 'failure'", jobs.getValue("rollback").condition)
        assertEquals("always()", jobs.getValue("notify").condition)
        assertEquals("deploy", jobRepo.findNextAvailable(listOf("defer-lane"))?.name)

        // The deploy FAILS: the rollback is NOT cancelled — its condition evaluates true and it
        // becomes claimable over the failed dependency; notify (always) becomes claimable too.
        jobService.updateStatus(jobs.getValue("deploy").id, PipelineRunStatus.FAILURE, "boom")
        finalizer.finalizeJob(jobs.getValue("deploy").id, PipelineRunStatus.FAILURE, releaseAgent = false)

        val rollback = jobRepo.findById(jobs.getValue("rollback").id)
        assertNotNull(rollback)
        assertEquals(PipelineRunStatus.QUEUED, rollback.status)
        assertNotNull(rollback.conditionSatisfiedAt)
        assertNotNull(jobRepo.findById(jobs.getValue("notify").id)?.conditionSatisfiedAt)
        assertEquals("rollback", jobRepo.findNextAvailable(listOf("defer-lane"))?.name)

        // The rollback and notify complete; the run settles FAILURE (the deploy failed).
        jobService.updateStatus(rollback.id, PipelineRunStatus.SUCCESS)
        finalizer.finalizeJob(rollback.id, PipelineRunStatus.SUCCESS, releaseAgent = false)
        jobService.updateStatus(jobs.getValue("notify").id, PipelineRunStatus.SUCCESS)
        finalizer.finalizeJob(jobs.getValue("notify").id, PipelineRunStatus.SUCCESS, releaseAgent = false)
        assertEquals(PipelineRunStatus.FAILURE, runRepo.findById(run.id)?.status)
    }

    @Test
    fun `end to end - a failure-routed rollback job is skipped when its dependency succeeds`() = withDb {
        val (jobService, runService, finalizer) = deferredStack()
        val definition = bosca.git.ci.parser.PipelineYamlParser().parse(failureRoutedYaml, "fr2.yaml")
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "fr2.yaml", name = "Failure Routed", configHash = "fr2"))
        val run = runService.createRun(pipeline.id, repositoryId, definition, "sha", "refs/tags/1.0.1", PipelineTriggerType.TAG, null, emptyMap())
        val jobs = jobRepo.findByRun(run.id).associateBy { it.name }

        jobService.updateStatus(jobs.getValue("deploy").id, PipelineRunStatus.SUCCESS)
        finalizer.finalizeJob(jobs.getValue("deploy").id, PipelineRunStatus.SUCCESS, releaseAgent = false)

        // The rollback is SKIPPED (steps cascaded), notify runs; skipped jobs don't fail the run.
        assertEquals(PipelineRunStatus.SKIPPED, jobRepo.findById(jobs.getValue("rollback").id)?.status)
        assertTrue(stepRepo.findByJob(jobs.getValue("rollback").id).all { it.status == PipelineRunStatus.SKIPPED })
        val notify = jobRepo.findById(jobs.getValue("notify").id)
        assertNotNull(notify?.conditionSatisfiedAt)
        assertEquals("notify", jobRepo.findNextAvailable(listOf("defer-lane"))?.name)

        jobService.updateStatus(jobs.getValue("notify").id, PipelineRunStatus.SUCCESS)
        finalizer.finalizeJob(jobs.getValue("notify").id, PipelineRunStatus.SUCCESS, releaseAgent = false)
        assertEquals(PipelineRunStatus.SUCCESS, runRepo.findById(run.id)?.status)
    }

    @Test
    fun `end to end - a lone always job is immediately claimable and a dead-end deferred run settles`() = withDb {
        val (_, runService, _) = deferredStack()
        val parser = bosca.git.ci.parser.PipelineYamlParser()

        // A single always() job must not hang: no dependency will ever settle to trigger evaluation.
        val aloneDef = parser.parse(
            """
            name: Alone
            on:
              tag:
            jobs:
              cleanup:
                runner: defer-alone-lane
                if: always()
                steps:
                  - { name: Cleanup, run: ./cleanup.sh }
            """.trimIndent(),
            "alone.yaml",
        )
        val alonePipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "alone.yaml", name = "Alone", configHash = "al"))
        val aloneRun = runService.createRun(alonePipeline.id, repositoryId, aloneDef, "sha", "refs/tags/2.0.0", PipelineTriggerType.TAG, null, emptyMap())
        assertEquals("cleanup", jobRepo.findNextAvailable(listOf("defer-alone-lane"))?.name)
        assertEquals(PipelineRunStatus.QUEUED, runRepo.findById(aloneRun.id)?.status)

        // A deferred job whose referenced dependency does not exist evaluates false at creation and
        // the run settles instead of hanging.
        val deadDef = parser.parse(
            """
            name: Dead End
            on:
              tag:
            jobs:
              rollback:
                runner: defer-dead-lane
                if: needs.ghost.result == 'failure'
                steps:
                  - { name: Rollback, run: ./rollback.sh }
            """.trimIndent(),
            "dead.yaml",
        )
        val deadPipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "dead.yaml", name = "Dead End", configHash = "de"))
        val deadRun = runService.createRun(deadPipeline.id, repositoryId, deadDef, "sha", "refs/tags/2.0.1", PipelineTriggerType.TAG, null, emptyMap())
        assertEquals(PipelineRunStatus.SKIPPED, jobRepo.findByRun(deadRun.id).single().status)
        assertEquals(PipelineRunStatus.SUCCESS, runRepo.findById(deadRun.id)?.status)
    }

    // ─── Environments, approvals, and gate jobs ─────────────────────

    private val approvalReleaseYaml = """
        name: Approval Release
        on:
          release: true
        environments:
          production:
            deploy-on: release
        jobs:
          approve:
            runner: %LANE%
            approval: true
          deploy:
            runner: %LANE%
            environment: production
            needs: [approve]
            steps:
              - { name: Deploy, run: ./deploy.sh }
    """.trimIndent()

    @Test
    fun `end to end - an approval gate parks the run until approved then completes server-side`() = withDb {
        val (jobService, runService, finalizer) = deferredStack()
        val parser = bosca.git.ci.parser.PipelineYamlParser()
        val definition = parser.parse(approvalReleaseYaml.replace("%LANE%", "appr-lane"), "ar.yaml")
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "ar.yaml", name = "Approval Release", configHash = "ar"))
        val run = runService.createRun(pipeline.id, repositoryId, definition, "sha", "refs/tags/9.0.0", PipelineTriggerType.RELEASE, null, emptyMap())

        // The gate is queued, approval-required, and steps-less; NOTHING is claimable — the gate
        // never dispatches to an agent and the deploy's dependency has not succeeded.
        val jobs = jobRepo.findByRun(run.id).associateBy { it.name }
        val gate = jobs.getValue("approve")
        assertTrue(gate.approvalRequired)
        assertNull(jobRepo.findNextAvailable(listOf("appr-lane")))
        assertTrue(jobService.isAwaitingApproval(gate))

        // Approval completes the gate server-side (no agent) and unblocks the deploy.
        val approver = UUID.random()
        jobService.approve(gate.id, approver, "LGTM")
        for (completed in jobService.completeGateJobs(run.id)) {
            finalizer.finalizeJob(completed.id, PipelineRunStatus.SUCCESS, releaseAgent = false)
        }
        val approved = jobRepo.findById(gate.id)
        assertNotNull(approved)
        assertEquals(PipelineRunStatus.SUCCESS, approved.status)
        assertEquals(approver, approved.approvedBy)
        assertEquals("LGTM", approved.approvalComment)
        assertNotNull(approved.approvedAt)
        assertEquals("deploy", jobRepo.findNextAvailable(listOf("appr-lane"))?.name)

        jobService.updateStatus(jobs.getValue("deploy").id, PipelineRunStatus.SUCCESS)
        finalizer.finalizeJob(jobs.getValue("deploy").id, PipelineRunStatus.SUCCESS, releaseAgent = false)
        assertEquals(PipelineRunStatus.SUCCESS, runRepo.findById(run.id)?.status)
    }

    @Test
    fun `end to end - a rejected approval fails the gate and cancels everything behind it`() = withDb {
        val (jobService, runService, finalizer) = deferredStack()
        val parser = bosca.git.ci.parser.PipelineYamlParser()
        val definition = parser.parse(approvalReleaseYaml.replace("%LANE%", "rej-lane"), "rj.yaml")
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "rj.yaml", name = "Rejected Release", configHash = "rj"))
        val run = runService.createRun(pipeline.id, repositoryId, definition, "sha", "refs/tags/9.0.1", PipelineTriggerType.RELEASE, null, emptyMap())
        val jobs = jobRepo.findByRun(run.id).associateBy { it.name }
        val gate = jobs.getValue("approve")

        val rejecter = UUID.random()
        jobService.rejectApproval(gate.id, rejecter, "not this build")
        finalizer.finalizeJob(gate.id, PipelineRunStatus.FAILURE, releaseAgent = false)

        val rejected = jobRepo.findById(gate.id)
        assertNotNull(rejected)
        assertEquals(PipelineRunStatus.FAILURE, rejected.status)
        assertTrue(rejected.errorMessage.orEmpty().contains("Approval rejected"))
        assertTrue(rejected.errorMessage.orEmpty().contains("not this build"))
        assertEquals(PipelineRunStatus.CANCELLED, jobRepo.findById(jobs.getValue("deploy").id)?.status)
        assertEquals(PipelineRunStatus.FAILURE, runRepo.findById(run.id)?.status)
    }

    @Test
    fun `end to end - environment approval policy binds its jobs and enforces approve-when-ready`() = withDb {
        val (jobService, runService, finalizer) = deferredStack()
        val parser = bosca.git.ci.parser.PipelineYamlParser()
        val definition = parser.parse(
            """
            name: Policy Release
            on:
              release: true
            environments:
              production:
                deploy-on: release
                approval: required
            jobs:
              build:
                runner: pol-lane
                steps:
                  - { name: Build, run: ./gradlew build }
              deploy:
                runner: pol-lane
                environment: production
                needs: [build]
                steps:
                  - { name: Deploy, run: ./deploy.sh }
            """.trimIndent(),
            "pol.yaml",
        )
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "pol.yaml", name = "Policy Release", configHash = "pol"))
        val run = runService.createRun(pipeline.id, repositoryId, definition, "sha", "refs/tags/9.0.2", PipelineTriggerType.RELEASE, null, emptyMap())
        val jobs = jobRepo.findByRun(run.id).associateBy { it.name }
        val deploy = jobs.getValue("deploy")

        // The environment's `approval: required` bound the deploy without the job saying so, but
        // the gate hasn't ENGAGED yet — the build is still pending, so approving now is refused.
        assertTrue(deploy.approvalRequired)
        assertFalse(jobService.isAwaitingApproval(deploy))
        assertFailsWith<IllegalStateException> { jobService.approve(deploy.id, UUID.random(), null) }

        jobService.updateStatus(jobs.getValue("build").id, PipelineRunStatus.SUCCESS)
        finalizer.finalizeJob(jobs.getValue("build").id, PipelineRunStatus.SUCCESS, releaseAgent = false)

        // Dependencies cleared: the job now awaits approval and stays unclaimable until it lands.
        val ready = jobRepo.findById(deploy.id)
        assertNotNull(ready)
        assertTrue(jobService.isAwaitingApproval(ready))
        assertNull(jobRepo.findNextAvailable(listOf("pol-lane")))
        jobService.approve(deploy.id, UUID.random(), "ship it")
        assertEquals("deploy", jobRepo.findNextAvailable(listOf("pol-lane"))?.name)
    }

    @Test
    fun `end to end - a requirement-gated steps-less gate completes without an agent once upstream succeeds`() = withDb {
        provides<bosca.pubsub.PubSubService>(singleton = true) { io.mockk.mockk(relaxed = true) }
        val (runService, checker, parser) = pipelineRequirementStack()
        val consumerDef = parser.parse(
            """
            name: Gate Consumer
            on:
              tag:
                patterns: ["*"]
            jobs:
              await-builds:
                requires:
                  - { pipeline: "Gate Provider", repository: acme/repo, timeout: 30m }
              deploy:
                runner: gate-lane
                needs: [await-builds]
                steps:
                  - { name: Deploy, run: ./deploy.sh }
            """.trimIndent(),
            "gc.yaml",
        )
        val providerPipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "gp.yaml", name = "Gate Provider", configHash = "gp"))
        val consumerPipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "gc.yaml", name = "Gate Consumer", configHash = "gc"))

        val consumerRun = runService.createRun(consumerPipeline.id, repositoryId, consumerDef, "sha-c", "refs/tags/8.8.8", PipelineTriggerType.TAG, null, emptyMap())
        val gate = jobRepo.findByRun(consumerRun.id).first { it.name == "await-builds" }
        assertNull(jobRepo.findNextAvailable(listOf("gate-lane")))

        val providerRun = runRepo.create(PipelineRun(pipelineId = providerPipeline.id, repositoryId = repositoryId, commitSha = "sha-p", ref = "refs/tags/8.8.8", triggerType = PipelineTriggerType.TAG, status = PipelineRunStatus.QUEUED, number = 1))
        runRepo.markFinished(providerRun.id, PipelineRunStatus.SUCCESS)
        checker.checkAwaiting()

        // The gate went straight to SUCCESS server-side — no agent ever saw it — and the deploy
        // behind it became the lane's claimable job.
        assertEquals(PipelineRunStatus.SUCCESS, jobRepo.findById(gate.id)?.status)
        assertEquals("deploy", jobRepo.findNextAvailable(listOf("gate-lane"))?.name)
    }

    @Test
    fun `run parameters round-trip and drive the promotion-history queries`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "pp.yaml", name = "Param Pipe", configHash = "pp"))
        fun params(vararg pairs: Pair<String, String>) = buildJsonObject {
            for ((k, v) in pairs) put(k, v)
        }
        val release = runRepo.create(PipelineRun(
            pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "sha1", ref = "refs/tags/7.0.0",
            triggerType = PipelineTriggerType.RELEASE, status = PipelineRunStatus.QUEUED, number = 1,
            parameters = params("release.version" to "7.0.0"),
        ))
        val stagingPromotion = runRepo.create(PipelineRun(
            pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "sha1", ref = "refs/tags/7.0.0",
            triggerType = PipelineTriggerType.PROMOTION, status = PipelineRunStatus.QUEUED, number = 2,
            parameters = params("promotion.environment" to "staging", "release.version" to "7.0.0"),
        ))
        val productionPromotion = runRepo.create(PipelineRun(
            pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "sha1", ref = "refs/tags/7.0.0",
            triggerType = PipelineTriggerType.PROMOTION, status = PipelineRunStatus.QUEUED, number = 3,
            parameters = params("promotion.environment" to "production", "release.version" to "7.0.0"),
        ))

        // The stored jsonb reads back with its dotted keys intact.
        assertEquals(
            "staging",
            (runRepo.findById(stagingPromotion.id)?.parameters as? kotlinx.serialization.json.JsonObject)
                ?.get("promotion.environment")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )

        // Only SUCCESSFUL runs count for the chain/downgrade queries.
        assertNull(runRepo.findLatestSuccessfulRelease(pipeline.id, "refs/tags/7.0.0"))
        assertNull(runRepo.findLatestSuccessfulPromotion(pipeline.id, "refs/tags/7.0.0", "staging"))
        runRepo.markFinished(release.id, PipelineRunStatus.SUCCESS)
        runRepo.markFinished(stagingPromotion.id, PipelineRunStatus.SUCCESS)
        runRepo.markFinished(productionPromotion.id, PipelineRunStatus.FAILURE)

        assertEquals(release.id, runRepo.findLatestSuccessfulRelease(pipeline.id, "refs/tags/7.0.0")?.id)
        assertEquals(stagingPromotion.id, runRepo.findLatestSuccessfulPromotion(pipeline.id, "refs/tags/7.0.0", "staging")?.id)
        // The failed production promotion doesn't count; staging's latest is env-filtered correctly.
        assertNull(runRepo.findLatestSuccessfulPromotionToEnvironment(pipeline.id, "production"))
        assertEquals(stagingPromotion.id, runRepo.findLatestSuccessfulPromotionToEnvironment(pipeline.id, "staging")?.id)
    }

    // ─── Run control: terminal guards + re-run failed ───────────────

    @Test
    fun `a cancelled job and step ignore late agent reports`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "tg.yaml", name = "TG", configHash = "tg"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        val job = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "build", runnerLabel = "linux"))
        val step = stepRepo.create(PipelineStep(pipelineJobId = job.id, name = "Build", ordinal = 0))

        // Server-side cancellation lands first…
        jobRepo.markFinished(job.id, PipelineRunStatus.CANCELLED, null)
        stepRepo.markFinished(step.id, PipelineRunStatus.CANCELLED, null, null)

        // …then the agent's late reports arrive: none of them may resurrect the rows.
        jobRepo.updateStatus(job.id, PipelineRunStatus.RUNNING)
        jobRepo.markFinished(job.id, PipelineRunStatus.SUCCESS, null)
        stepRepo.markStarted(step.id, PipelineRunStatus.RUNNING, null)
        stepRepo.markFinished(step.id, PipelineRunStatus.SUCCESS, 0, null)

        assertEquals(PipelineRunStatus.CANCELLED, jobRepo.findById(job.id)?.status)
        assertEquals(PipelineRunStatus.CANCELLED, stepRepo.findById(step.id)?.status)
    }

    @Test
    fun `end to end - rerun failed jobs resets only the failed jobs and reopens the run`() = withDb {
        // The re-open path publishes a run-status event.
        provides<bosca.pubsub.PubSubService>(singleton = true) { io.mockk.mockk(relaxed = true) }
        val jobService = bosca.git.ci.service.PipelineJobServiceImpl(
            jobRepo, stepRepo, io.mockk.mockk(relaxed = true), json,
            bosca.git.ci.configuration.KubernetesCiDispatchConfiguration.disabled,
            io.mockk.mockk(relaxed = true),
        )
        val runService = bosca.git.ci.service.PipelineRunServiceImpl(
            runRepo, jobService, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), json,
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
        )
        provides<bosca.git.service.PipelineRunService>(singleton = true) { runService }

        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "rr.yaml", name = "RR", configHash = "rr"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "refs/tags/8.8.8", triggerType = PipelineTriggerType.TAG, status = PipelineRunStatus.QUEUED, number = 1))
        val good = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "build", runnerLabel = "rerun-lane"))
        val goodStep = stepRepo.create(PipelineStep(pipelineJobId = good.id, name = "Build", ordinal = 0))
        val bad = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "publish", runnerLabel = "rerun-lane", dependsOn = listOf("build")))
        val badStep = stepRepo.create(PipelineStep(pipelineJobId = bad.id, name = "Publish", ordinal = 0))

        // First attempt: build succeeds, publish fails, the run fails.
        stepRepo.markFinished(goodStep.id, PipelineRunStatus.SUCCESS, 0, null)
        jobRepo.markFinished(good.id, PipelineRunStatus.SUCCESS, null)
        stepRepo.markFinished(badStep.id, PipelineRunStatus.FAILURE, 1, "boom")
        jobRepo.markFinished(bad.id, PipelineRunStatus.FAILURE, "Step 'Publish' failed")
        runRepo.markFinished(run.id, PipelineRunStatus.FAILURE)

        val reopened = runService.rerunFailedJobs(run.id, null)

        // Only the failed job reset — queued again, next attempt, clean slate — and its step with it.
        val resetJob = jobRepo.findById(bad.id)
        assertNotNull(resetJob)
        assertEquals(PipelineRunStatus.QUEUED, resetJob.status)
        assertEquals(2, resetJob.attempt)
        assertNull(resetJob.errorMessage)
        assertNull(resetJob.finished)
        assertEquals(PipelineRunStatus.QUEUED, stepRepo.findById(badStep.id)?.status)

        // The succeeded job is untouched, and its SUCCESS still satisfies the reset job's needs —
        // the claim query hands out the reset job without re-running its dependency.
        val keptJob = jobRepo.findById(good.id)
        assertEquals(PipelineRunStatus.SUCCESS, keptJob?.status)
        assertEquals(1, keptJob?.attempt)
        assertEquals("publish", jobRepo.findNextAvailable(listOf("rerun-lane"))?.name)

        assertEquals(PipelineRunStatus.QUEUED, reopened.status)
        assertNull(runRepo.findById(run.id)?.finished)
    }

    @Test
    fun `an ungated job is claimable with no requirement ceremony`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "ung.yaml", name = "Ung", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        val job = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "lint", runnerLabel = "linux"))

        assertEquals(job.id, jobRepo.findNextAvailable(listOf("linux"))?.id)
        assertEquals(emptyList(), jobRepo.findAwaitingRequirements().map { it.id })
    }

    @Test
    fun `blocked job cancellation considers dependencies only from its own run`() = withDb {
        val pipeline = pipelineRepo.create(
            Pipeline(
                repositoryId = repositoryId,
                filePath = "run-scoped-cancellation.yaml",
                name = "Run-scoped cancellation",
                configHash = "h",
            )
        )
        val historicalRun = runRepo.create(
            PipelineRun(
                pipelineId = pipeline.id,
                repositoryId = repositoryId,
                commitSha = "old",
                ref = "main",
                triggerType = PipelineTriggerType.PUSH,
                status = PipelineRunStatus.FAILURE,
                number = 1,
            )
        )
        jobRepo.create(
            PipelineJob(
                pipelineRunId = historicalRun.id,
                name = "publish",
                runnerLabel = "linux",
                status = PipelineRunStatus.FAILURE,
            )
        )
        val currentRun = runRepo.create(
            PipelineRun(
                pipelineId = pipeline.id,
                repositoryId = repositoryId,
                commitSha = "current",
                ref = "main",
                triggerType = PipelineTriggerType.PUSH,
                status = PipelineRunStatus.RUNNING,
                number = 2,
            )
        )
        jobRepo.create(
            PipelineJob(
                pipelineRunId = currentRun.id,
                name = "publish",
                runnerLabel = "linux",
                status = PipelineRunStatus.SUCCESS,
            )
        )
        val dependent = jobRepo.create(
            PipelineJob(
                pipelineRunId = currentRun.id,
                name = "update",
                runnerLabel = "linux",
                dependsOn = listOf("publish"),
            )
        )

        assertTrue(jobRepo.cancelBlockedJobs(currentRun.id).isEmpty())
        assertEquals(PipelineRunStatus.QUEUED, jobRepo.findById(dependent.id)?.status)
    }

    @Test
    fun `kubernetes dispatch selects only configured profiles and removes stamped jobs from polling`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "k8s.yaml", name = "K8s", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        val android = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "android", runnerLabel = "android"))
        val linux = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "linux", runnerLabel = "linux"))
        stepRepo.create(PipelineStep(pipelineJobId = android.id, name = "Build Android", ordinal = 0))
        stepRepo.create(PipelineStep(pipelineJobId = linux.id, name = "Build Linux", ordinal = 0))

        assertEquals(android.id, jobRepo.findNextKubernetesDispatchCandidate(listOf("android"))?.id)

        val dispatchId = UUID.random()
        val agentId = agentRepo.create(
            PipelineAgent(
                name = "kubernetes-android",
                labels = listOf("android"),
                ephemeral = true,
                jobId = android.id,
                tokenHash = "kubernetes-android-token",
            )
        ).id
        val stamped = jobRepo.markKubernetesDispatched(android.id, dispatchId, agentId)
        assertEquals(dispatchId, stamped?.kubernetesDispatchId)
        assertEquals(agentId, stamped?.agentId)
        assertEquals(
            listOf(android.id),
            jobRepo.findUnfinalizedKubernetesDispatched(10).map { it.id },
        )
        assertNull(jobRepo.findNextKubernetesDispatchCandidate(listOf("android")))
        assertNull(jobRepo.findNextAvailable(listOf("android")))
        assertEquals(linux.id, jobRepo.findNextAvailable(listOf("linux"))?.id)
        assertNull(
            jobRepo.claimJobById(
                android.id,
                UUID.random(),
                previousAgentId = null,
                status = PipelineRunStatus.RUNNING,
            )
        )
        assertEquals(
            agentId,
            jobRepo.claimJobById(
                android.id,
                agentId,
                previousAgentId = null,
                status = PipelineRunStatus.RUNNING,
            )?.agentId
        )
        assertEquals(
            listOf(android.id),
            jobRepo.findUnfinalizedKubernetesDispatched(10).map { it.id },
        )
        jobRepo.markFinished(android.id, PipelineRunStatus.SUCCESS, null)
        assertEquals(
            listOf(android.id),
            jobRepo.findUnfinalizedKubernetesDispatched(10).map { it.id },
        )
        assertEquals(android.id, jobRepo.claimKubernetesFinalization(android.id)?.id)
        assertNull(jobRepo.claimKubernetesFinalization(android.id))
        assertEquals(emptyList(), jobRepo.findUnfinalizedKubernetesDispatched(10))
    }

    @Test
    fun `job findByRun returns all jobs for a run`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "jr.yaml", name = "JR", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "build", runnerLabel = "linux"))
        jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "test", runnerLabel = "linux"))

        val jobs = jobRepo.findByRun(run.id)
        assertEquals(2, jobs.size)
    }

    // ─── Pipeline Step Repository ───────────────────────────────

    @Test
    fun `step create and findByJob with ordinal ordering`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "st.yaml", name = "ST", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        val job = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "build", runnerLabel = "linux"))

        stepRepo.create(PipelineStep(pipelineJobId = job.id, name = "Checkout", ordinal = 0))
        stepRepo.create(PipelineStep(pipelineJobId = job.id, name = "Build", ordinal = 1))
        stepRepo.create(PipelineStep(pipelineJobId = job.id, name = "Test", ordinal = 2))

        val steps = stepRepo.findByJob(job.id)
        assertEquals(3, steps.size)
        assertEquals("Checkout", steps[0].name)
        assertEquals("Build", steps[1].name)
        assertEquals("Test", steps[2].name)
        assertEquals(0, steps[0].ordinal)
        assertEquals(1, steps[1].ordinal)
        assertEquals(2, steps[2].ordinal)
    }

    @Test
    fun `step roundtrips uses run image condition workingDirectory with and env`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "sr.yaml", name = "SR", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        val job = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "build", runnerLabel = "linux", timeoutMinutes = 120))

        val withArgs = kotlinx.serialization.json.buildJsonObject {
            put("submodules", kotlinx.serialization.json.JsonPrimitive("recursive"))
            put("depth", kotlinx.serialization.json.JsonPrimitive("1"))
            put("unicode", kotlinx.serialization.json.JsonPrimitive("naïve—✓"))
        }
        val envMap = kotlinx.serialization.json.buildJsonObject {
            put("CI", kotlinx.serialization.json.JsonPrimitive("true"))
        }

        val checkout = stepRepo.create(PipelineStep(
            pipelineJobId = job.id,
            name = "Checkout",
            ordinal = 0,
            uses = "checkout",
            condition = "success()",
            workingDirectory = "subdir",
            with = withArgs,
            env = envMap,
        ))
        val script = stepRepo.create(PipelineStep(
            pipelineJobId = job.id,
            name = "Run",
            ordinal = 1,
            run = "echo hi",
            image = "alpine:3.20",
        ))

        val found = stepRepo.findByJob(job.id)
        assertEquals(2, found.size)

        val foundCheckout = found[0]
        assertEquals("checkout", foundCheckout.uses)
        assertNull(foundCheckout.run)
        assertEquals("success()", foundCheckout.condition)
        assertEquals("subdir", foundCheckout.workingDirectory)
        assertEquals(withArgs, foundCheckout.with)
        assertEquals(envMap, foundCheckout.env)

        val foundScript = found[1]
        assertNull(foundScript.uses)
        assertEquals("echo hi", foundScript.run)
        assertEquals("alpine:3.20", foundScript.image)
        assertEquals(kotlinx.serialization.json.JsonObject(emptyMap()), foundScript.with)
        assertEquals(kotlinx.serialization.json.JsonObject(emptyMap()), foundScript.env)

        // verify by id too — exercises the same SELECT * path
        val byId = stepRepo.findById(checkout.id)
        assertNotNull(byId)
        assertEquals(withArgs, byId.with)
    }

    @Test
    fun `job roundtrips timeoutMinutes`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "jt.yaml", name = "JT", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))

        val withTimeout = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "long", runnerLabel = "x", timeoutMinutes = 90))
        val noTimeout = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "short", runnerLabel = "x"))

        val foundLong = jobRepo.findById(withTimeout.id)
        assertNotNull(foundLong)
        assertEquals(90, foundLong.timeoutMinutes)

        val foundShort = jobRepo.findById(noTimeout.id)
        assertNotNull(foundShort)
        assertNull(foundShort.timeoutMinutes)
    }

    @Test
    fun `step markFinished sets exit code and finished timestamp`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "se.yaml", name = "SE", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        val job = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "build", runnerLabel = "linux"))
        val step = stepRepo.create(PipelineStep(pipelineJobId = job.id, name = "Build", ordinal = 0))

        stepRepo.markFinished(step.id, PipelineRunStatus.FAILURE, 1, "FAILURE: Build failed with an exception.")
        val found = stepRepo.findById(step.id)
        assertNotNull(found)
        assertEquals(PipelineRunStatus.FAILURE, found.status)
        assertEquals(1, found.exitCode)
        assertEquals("FAILURE: Build failed with an exception.", found.errorMessage)
        assertNotNull(found.finished)
    }

    @Test
    fun `job markFinished persists the failure summary`() = withDb {
        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "je.yaml", name = "JE", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        val job = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "build", runnerLabel = "linux"))

        jobRepo.markFinished(job.id, PipelineRunStatus.FAILURE, "Agent stopped heartbeating while the job was running")
        val found = jobRepo.findById(job.id)
        assertNotNull(found)
        assertEquals(PipelineRunStatus.FAILURE, found.status)
        assertEquals("Agent stopped heartbeating while the job was running", found.errorMessage)
        assertNotNull(found.finished)
    }

    // ─── Pipeline Agent Repository ──────────────────────────────

    @Test
    fun `agent create persistent and findById`() = withDb {
        val agent = agentRepo.create(PipelineAgent(
            name = "runner-01",
            labels = listOf("linux", "gpu"),
            mode = AgentMode.RUNNER,
            status = AgentStatus.ONLINE,
            tokenHash = "hash123"
        ))

        val found = agentRepo.findById(agent.id)
        assertNotNull(found)
        assertEquals("runner-01", found.name)
        assertEquals(listOf("linux", "gpu"), found.labels)
        assertEquals(AgentMode.RUNNER, found.mode)
        assertEquals(AgentStatus.ONLINE, found.status)
        assertEquals(false, found.ephemeral)
    }

    @Test
    fun `agent create ephemeral with parent and job`() = withDb {
        val parent = agentRepo.create(PipelineAgent(
            name = "orchestrator-01", mode = AgentMode.ORCHESTRATOR,
            status = AgentStatus.ONLINE, tokenHash = "parent-hash"
        ))

        val pipeline = pipelineRepo.create(Pipeline(repositoryId = repositoryId, filePath = "ae.yaml", name = "AE", configHash = "h"))
        val run = runRepo.create(PipelineRun(pipelineId = pipeline.id, repositoryId = repositoryId, commitSha = "a", ref = "main", triggerType = PipelineTriggerType.PUSH, status = PipelineRunStatus.QUEUED, number = 1))
        val job = jobRepo.create(PipelineJob(pipelineRunId = run.id, name = "build", runnerLabel = "linux"))

        val ephemeral = agentRepo.create(PipelineAgent(
            name = "ephemeral-01",
            mode = AgentMode.RUNNER,
            status = AgentStatus.BUSY,
            ephemeral = true,
            jobId = job.id,
            parentAgentId = parent.id,
            tokenHash = "eph-hash"
        ))

        val found = agentRepo.findById(ephemeral.id)
        assertNotNull(found)
        assertTrue(found.ephemeral)
        assertEquals(job.id, found.jobId)
        assertEquals(parent.id, found.parentAgentId)
    }

    @Test
    fun `agent findByTokenHash returns matching agent`() = withDb {
        agentRepo.create(PipelineAgent(name = "token-test", tokenHash = "unique-hash-xyz", status = AgentStatus.ONLINE))

        val found = agentRepo.findByTokenHash("unique-hash-xyz")
        assertNotNull(found)
        assertEquals("token-test", found.name)

        assertNull(agentRepo.findByTokenHash("nonexistent-hash"))
    }

    @Test
    fun `agent heartbeat updates last_heartbeat`() = withDb {
        val agent = agentRepo.create(PipelineAgent(name = "hb-test", tokenHash = "hb-hash", status = AgentStatus.ONLINE))
        assertNull(agentRepo.findById(agent.id)!!.lastHeartbeat)

        agentRepo.heartbeat(agent.id)
        assertNotNull(agentRepo.findById(agent.id)!!.lastHeartbeat)
    }

    @Test
    fun `agent updateStatus changes status`() = withDb {
        val agent = agentRepo.create(PipelineAgent(name = "status-test", tokenHash = "s-hash", status = AgentStatus.OFFLINE))
        agentRepo.updateStatus(agent.id, AgentStatus.BUSY)
        assertEquals(AgentStatus.BUSY, agentRepo.findById(agent.id)!!.status)
    }

    @Test
    fun `agent delete removes record`() = withDb {
        val agent = agentRepo.create(PipelineAgent(name = "del-test", tokenHash = "d-hash", status = AgentStatus.OFFLINE))
        agentRepo.delete(agent.id)
        assertNull(agentRepo.findById(agent.id))
    }

    // ─── Pipeline Secret Repository ─────────────────────────────

    @Test
    fun `secret upsert creates and updates`() = withDb {
        val created = secretRepo.upsert(PipelineSecret(
            repositoryId = repositoryId, name = "API_KEY", encryptedValue = "encrypted-v1"
        ))
        assertEquals("API_KEY", created.name)
        assertEquals("encrypted-v1", created.encryptedValue)

        val updated = secretRepo.upsert(PipelineSecret(
            repositoryId = repositoryId, name = "API_KEY", encryptedValue = "encrypted-v2"
        ))
        assertEquals("encrypted-v2", updated.encryptedValue)

        val all = secretRepo.findByRepository(repositoryId)
        assertEquals(1, all.size)
    }

    @Test
    fun `secret findByName returns matching secret`() = withDb {
        secretRepo.upsert(PipelineSecret(repositoryId = repositoryId, name = "TOKEN", encryptedValue = "enc"))

        val found = secretRepo.findByName(repositoryId, "TOKEN")
        assertNotNull(found)
        assertEquals("TOKEN", found.name)

        assertNull(secretRepo.findByName(repositoryId, "NONEXISTENT"))
    }

    @Test
    fun `secret delete removes by repository and name`() = withDb {
        secretRepo.upsert(PipelineSecret(repositoryId = repositoryId, name = "DEL_ME", encryptedValue = "enc"))
        secretRepo.delete(repositoryId, "DEL_ME")
        assertNull(secretRepo.findByName(repositoryId, "DEL_ME"))
    }

    @Test
    fun `secret findByRepository returns all secrets for repo`() = withDb {
        secretRepo.upsert(PipelineSecret(repositoryId = repositoryId, name = "KEY1", encryptedValue = "e1"))
        secretRepo.upsert(PipelineSecret(repositoryId = repositoryId, name = "KEY2", encryptedValue = "e2"))

        val secrets = secretRepo.findByRepository(repositoryId)
        assertEquals(2, secrets.size)
    }

    @OptIn(bosca.core.annotations.Internal::class)
    @Test
    fun `push executor persists attributed runs only while execution permission is granted`() = withDb {
        val actorId = UUID.random()
        val group = bosca.security.model.Group(UUID.random(), "builders", "Builders", bosca.security.model.GroupType.SYSTEM)
        val repository = bosca.git.model.Repository(id = repositoryId, ownerId = ownerId, slug = "test-repo", name = "Test")
        val yaml = """
            name: Build
            on:
              push:
                branches: [main]
              manual: true
            jobs:
              build:
                steps:
                  - { name: Build, run: ./gradlew build }
            """.trimIndent()
        val repositories = io.mockk.mockk<bosca.git.service.RepositoryService>(relaxed = true)
        val security = io.mockk.mockk<bosca.security.service.SecurityService>(relaxed = true)
        val groups = io.mockk.mockk<bosca.security.service.GroupEvaluator>(relaxed = true)
        val browse = io.mockk.mockk<bosca.git.service.RepositoryBrowseService>()
        val schedules = io.mockk.mockk<bosca.git.service.PipelineScheduleService>(relaxed = true)
        val pipelines = bosca.git.ci.service.PipelineServiceImpl(
            pipelineRepo, browse, bosca.git.ci.parser.PipelineYamlParser(), json, repositories, schedules,
        )
        io.mockk.coEvery { security.getPrincipalById(actorId) } returns bosca.security.model.Principal(id = actorId)
        io.mockk.coEvery { security.getPrincipalGroups(actorId) } returns listOf(group)
        io.mockk.coEvery { repositories.findById(repositoryId) } returns repository
        io.mockk.coEvery { repositories.getPermissions(repository) } returns listOf(
            bosca.git.model.RepositoryPermission(repositoryId, group.id, bosca.security.model.PermissionAction.EDIT),
        )
        val entry = bosca.git.model.TreeEntry(
            name = "build.yaml", path = ".bosca/pipelines/build.yaml", type = bosca.git.model.TreeEntryType.BLOB,
            mode = 0x100644, sha = "after", size = yaml.length.toLong(),
        )
        io.mockk.coEvery { browse.listTree(repositoryId, "after", ".bosca/pipelines") } returns listOf(entry)
        io.mockk.coEvery { browse.readBlob(repositoryId, "after", entry.path) } returns
            bosca.git.model.Blob(content = yaml, size = yaml.length.toLong(), sha = "after", isBinary = false)
        io.mockk.coEvery { browse.resolveRef(repositoryId, "refs/heads/main") } returns "after"
        // The ref has moved to a commit with a different file and no matching branch trigger.
        io.mockk.coEvery { browse.listTree(repositoryId, "later", ".bosca/pipelines") } returns
            listOf(entry.copy(name = "later.yaml", path = ".bosca/pipelines/later.yaml"))
        io.mockk.coEvery { browse.readBlob(repositoryId, "later", ".bosca/pipelines/later.yaml") } returns
            bosca.git.model.Blob(content = yaml.replace("branches: [main]", "branches: [develop]"),
                size = yaml.length.toLong(), sha = "later", isBinary = false)
        val jobService = bosca.git.ci.service.PipelineJobServiceImpl(
            jobRepo, stepRepo, io.mockk.mockk(relaxed = true), json,
            bosca.git.ci.configuration.KubernetesCiDispatchConfiguration.disabled, io.mockk.mockk(relaxed = true),
        )
        val runs = bosca.git.ci.service.PipelineRunServiceImpl(
            runRepo, jobService, io.mockk.mockk(relaxed = true), pipelines, json,
            io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true),
        )
        provides<bosca.git.service.RepositoryService> { repositories }
        provides<bosca.security.service.SecurityService> { security }
        provides<bosca.git.security.RepositoryPermissionEvaluator> {
            bosca.git.security.RepositoryPermissionEvaluator(repositories, security, groups)
        }
        provides<bosca.git.service.PipelineService> { pipelines }
        provides<bosca.git.service.PipelineRunService> { runs }
        val statuses = io.mockk.mockk<bosca.git.service.CommitStatusService>(relaxed = true)
        provides<bosca.git.service.CommitStatusService> { statuses }
        provides<bosca.pubsub.PubSubService> { io.mockk.mockk(relaxed = true) }
        val queue = io.mockk.mockk<bosca.sharedqueue.jobs.JobQueue>(relaxed = true)

        suspend fun dispatch(principal: UUID?, triggerId: UUID = UUID.random(), afterSha: String = "after") {
            val job = bosca.sharedqueue.jobs.InternalJobConstructor(
                definition = json.encodeToJsonElement(
                    bosca.git.model.PipelineTriggerJob.serializer(),
                    bosca.git.model.PipelineTriggerJob(repositoryId, "refs/heads/main", "before", afterSha, principal),
                ),
                executor = bosca.git.ci.jobs.PipelineTriggerExecutor::class,
            )
            job.setPersistentId(triggerId)
            withContext(queue.asCoroutineContext(job)) { bosca.git.ci.jobs.PipelineTriggerExecutor().execute() }
        }

        dispatch(actorId)
        assertTrue(runRepo.findByRepository(repositoryId, 0, 100).isEmpty())
        val pipeline = pipelines.findByRepository(repositoryId).single()
        assertEquals(".bosca/pipelines/build.yaml", pipeline.filePath)
        assertEquals("Build", pipeline.name)
        dispatch(null)
        assertTrue(runRepo.findByRepository(repositoryId, 0, 100).isEmpty())
        assertEquals(pipeline.id, pipelines.findByRepository(repositoryId).single().id)
        io.mockk.coVerify { schedules.sync(match { it.id == pipeline.id }, any()) }
        io.mockk.coEvery { repositories.getPermissions(repository) } returns listOf(
            bosca.git.model.RepositoryPermission(repositoryId, group.id, bosca.security.model.PermissionAction.EXECUTE),
        )
        val triggerId = UUID.random()
        io.mockk.coEvery { browse.resolveRef(repositoryId, "refs/heads/main") } returns "later"
        dispatch(actorId, triggerId)
        val run = runRepo.findByRepository(repositoryId, 0, 100).single()
        assertEquals(actorId, run.triggeredBy)
        assertEquals("after", run.commitSha)
        assertEquals("build", jobRepo.findByRun(run.id).single().name)
        assertEquals("Build", stepRepo.findByJob(jobRepo.findByRun(run.id).single().id).single().name)

        dispatch(actorId, triggerId)
        assertEquals(listOf(run.id), runRepo.findByRepository(repositoryId, 0, 100).map { it.id })
        io.mockk.coVerify(exactly = 1) { statuses.recordStatus(any(), any(), any(), any(), any(), any()) }

        val failedTriggerId = UUID.random()
        io.mockk.coEvery { statuses.recordStatus(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("status persistence failed")
        assertFailsWith<IllegalStateException> { dispatch(actorId, failedTriggerId) }
        assertEquals(listOf(run.id), runRepo.findByRepository(repositoryId, 0, 100).map { it.id })
        io.mockk.coEvery { statuses.recordStatus(any(), any(), any(), any(), any(), any()) } returns io.mockk.mockk(relaxed = true)
        dispatch(actorId, failedTriggerId)
        val persistedIds = runRepo.findByRepository(repositoryId, 0, 100).map { it.id }.toSet()
        assertEquals(2, persistedIds.size)
        dispatch(actorId, failedTriggerId)
        assertEquals(persistedIds, runRepo.findByRepository(repositoryId, 0, 100).map { it.id }.toSet())

        io.mockk.coEvery { repositories.getPermissions(repository) } returns listOf(
            bosca.git.model.RepositoryPermission(repositoryId, group.id, bosca.security.model.PermissionAction.EDIT),
        )
        dispatch(actorId)
        assertEquals(persistedIds, runRepo.findByRepository(repositoryId, 0, 100).map { it.id }.toSet())

        val withoutPush = yaml.replace("branches: [main]", "branches: [develop]")
        // The queued commit excludes main even though the ref now contains a matching trigger.
        io.mockk.coEvery { browse.resolveRef(repositoryId, "refs/heads/main") } returns "after"
        io.mockk.coEvery { browse.listTree(repositoryId, "without-push", ".bosca/pipelines") } returns listOf(entry)
        io.mockk.coEvery { browse.readBlob(repositoryId, "without-push", entry.path) } returns
            bosca.git.model.Blob(content = withoutPush, size = withoutPush.length.toLong(), sha = "without-push", isBinary = false)
        io.mockk.coEvery { repositories.getPermissions(repository) } returns listOf(
            bosca.git.model.RepositoryPermission(repositoryId, group.id, bosca.security.model.PermissionAction.EXECUTE),
        )
        dispatch(actorId, afterSha = "without-push")
        assertEquals(persistedIds, runRepo.findByRepository(repositoryId, 0, 100).map { it.id }.toSet())
        io.mockk.coVerify(exactly = 0) { browse.listTree(repositoryId, "refs/heads/main", any()) }
        io.mockk.coVerify(exactly = 0) { browse.readBlob(repositoryId, "refs/heads/main", any()) }
    }



    @OptIn(bosca.core.annotations.Internal::class)
    @Test
    fun `stale push and tag redelivery preserves newer history and deduplication across archival`() = withDb {
        fun yaml(name: String, command: String) = """
            name: $name
            on:
              push:
                branches: [main]
              tag:
                patterns: ["*"]
            jobs:
              build:
                steps:
                  - { name: Build, run: $command }
            """.trimIndent()
        val buildPath = ".bosca/pipelines/build.yaml"
        val deployPath = ".bosca/pipelines/deploy.yaml"
        val commits = mapOf(
            "older" to mapOf(buildPath to yaml("Build", "echo older")),
            "newer" to mapOf(buildPath to yaml("Current Build", "echo newer"), deployPath to yaml("Deploy", "echo deploy")),
            "removed" to emptyMap(),
            "restored" to mapOf(buildPath to yaml("Restored Build", "echo restored"), deployPath to yaml("Restored Deploy", "echo restored-deploy")),
        )
        var head = "older"
        val repositories = io.mockk.mockk<bosca.git.service.RepositoryService>(relaxed = true)
        val browse = io.mockk.mockk<bosca.git.service.RepositoryBrowseService>()
        val schedules = io.mockk.mockk<bosca.git.service.PipelineScheduleService>(relaxed = true)
        val repository = bosca.git.model.Repository(id = repositoryId, ownerId = ownerId, slug = "test-repo", name = "Test")
        io.mockk.coEvery { repositories.findById(repositoryId) } returns repository
        io.mockk.coEvery { browse.resolveRef(repositoryId, "refs/heads/main") } answers { head }
        io.mockk.coEvery { browse.listTree(repositoryId, any(), ".bosca/pipelines") } answers {
            commits.getValue(secondArg()).map { (path, content) ->
                bosca.git.model.TreeEntry(path.substringAfterLast('/'), path, bosca.git.model.TreeEntryType.BLOB, 0x100644, "blob", content.length.toLong())
            }
        }
        io.mockk.coEvery { browse.readBlob(repositoryId, any(), any()) } answers {
            commits.getValue(secondArg())[thirdArg<String>()]?.let { content ->
                bosca.git.model.Blob(content, content.length.toLong(), "blob", false)
            }
        }
        val pipelines = bosca.git.ci.service.PipelineServiceImpl(
            pipelineRepo, browse, bosca.git.ci.parser.PipelineYamlParser(), json, repositories, schedules,
        )
        deferredStack()
        val actorId = UUID.random()
        val group = bosca.security.model.Group(UUID.random(), "builders", "Builders", bosca.security.model.GroupType.SYSTEM)
        val security = io.mockk.mockk<bosca.security.service.SecurityService>(relaxed = true)
        io.mockk.coEvery { security.getPrincipalById(actorId) } returns bosca.security.model.Principal(id = actorId)
        io.mockk.coEvery { security.getPrincipalGroups(actorId) } returns listOf(group)
        io.mockk.coEvery { repositories.getPermissions(repository) } returns listOf(
            bosca.git.model.RepositoryPermission(repositoryId, group.id, bosca.security.model.PermissionAction.EXECUTE),
        )
        provides<bosca.git.service.RepositoryService> { repositories }
        provides<bosca.security.service.SecurityService> { security }
        provides<bosca.git.security.RepositoryPermissionEvaluator> {
            bosca.git.security.RepositoryPermissionEvaluator(repositories, security, io.mockk.mockk(relaxed = true))
        }
        provides<bosca.git.service.PipelineService> { pipelines }
        val statuses = io.mockk.mockk<bosca.git.service.CommitStatusService>(relaxed = true)
        provides<bosca.git.service.CommitStatusService> { statuses }
        val queue = io.mockk.mockk<bosca.sharedqueue.jobs.JobQueue>(relaxed = true)
        suspend fun dispatch(triggerId: UUID, commit: String, ref: String = "refs/heads/main") {
            val job = bosca.sharedqueue.jobs.InternalJobConstructor(
                definition = json.encodeToJsonElement(
                    bosca.git.model.PipelineTriggerJob.serializer(),
                    bosca.git.model.PipelineTriggerJob(repositoryId, ref, "before", commit, actorId),
                ),
                executor = bosca.git.ci.jobs.PipelineTriggerExecutor::class,
            )
            job.setPersistentId(triggerId)
            withContext(queue.asCoroutineContext(job)) { bosca.git.ci.jobs.PipelineTriggerExecutor().execute() }
        }
        suspend fun runIds() = runRepo.findByRepository(repositoryId, 0, 100).map { it.id }.toSet()

        val olderTrigger = UUID.random()
        dispatch(olderTrigger, "older")
        val buildId = pipelines.findByRepository(repositoryId).single().id
        head = "newer"
        val newerTrigger = UUID.random()
        dispatch(newerTrigger, "newer")
        val current = pipelines.findByRepository(repositoryId).associateBy { it.filePath }
        val deployId = current.getValue(deployPath).id
        val newerRuns = runRepo.findByRepository(repositoryId, 0, 100)
        val newerRunIds = newerRuns.map { it.id }.toSet()
        assertEquals(3, newerRunIds.size)
        val persistedJobs = newerRuns.flatMap { jobRepo.findByRun(it.id) }
        val persistedSteps = persistedJobs.flatMap { stepRepo.findByJob(it.id) }

        dispatch(olderTrigger, "older")
        dispatch(newerTrigger, "newer")
        assertEquals(newerRunIds, runIds())
        assertEquals(current, pipelines.findByRepository(repositoryId).associateBy { it.filePath })
        assertTrue(persistedJobs.all { jobRepo.findById(it.id) != null })
        assertTrue(persistedSteps.all { stepRepo.findById(it.id) != null })

        // A delayed first delivery still executes its immutable job definition under the stable ID.
        dispatch(UUID.random(), "older")
        val delayedRun = runRepo.findByRepository(repositoryId, 0, 100).single { it.id !in newerRunIds }
        assertEquals(buildId, delayedRun.pipelineId)
        assertEquals("older", delayedRun.commitSha)
        assertEquals("echo older", stepRepo.findByJob(jobRepo.findByRun(delayedRun.id).single().id).single().run)
        val tagTrigger = UUID.random()
        dispatch(tagTrigger, "older", "refs/tags/v1")
        val beforeArchival = runIds()
        dispatch(tagTrigger, "older", "refs/tags/v1")
        assertEquals(beforeArchival, runIds())
        assertEquals(current, pipelines.findByRepository(repositoryId).associateBy { it.filePath })

        head = "removed"
        dispatch(UUID.random(), "removed")
        assertTrue(pipelines.findByRepository(repositoryId).isEmpty())
        assertTrue(pipelineRepo.findAll().isEmpty())
        assertNull(pipelines.findByRepositoryAndName(repositoryId, "Current Build"))
        assertNotNull(pipelineRepo.findById(buildId)?.deletedAt)
        assertNotNull(pipelineRepo.findByRepositoryAndFilePath(repositoryId, deployPath)?.deletedAt)
        assertEquals(beforeArchival, runIds())
        dispatch(olderTrigger, "older")
        dispatch(newerTrigger, "newer")
        assertEquals(beforeArchival, runIds())
        assertTrue(pipelines.findByRepository(repositoryId).isEmpty())

        head = "restored"
        dispatch(UUID.random(), "restored")
        val restored = pipelines.findByRepository(repositoryId).associateBy { it.filePath }
        assertEquals(buildId, restored.getValue(buildPath).id)
        assertEquals(deployId, restored.getValue(deployPath).id)
        assertEquals("Restored Build", restored.getValue(buildPath).name)
        assertEquals("Restored Deploy", restored.getValue(deployPath).name)
        assertTrue(restored.values.all { it.deletedAt == null })
        val afterRestoration = runIds()
        assertEquals(beforeArchival.size + 2, afterRestoration.size)
        dispatch(olderTrigger, "older")
        dispatch(newerTrigger, "newer")
        dispatch(tagTrigger, "older", "refs/tags/v1")
        assertEquals(afterRestoration, runIds())
        assertEquals(restored, pipelines.findByRepository(repositoryId).associateBy { it.filePath })
        assertTrue(persistedJobs.all { jobRepo.findById(it.id) != null })
        assertTrue(persistedSteps.all { stepRepo.findById(it.id) != null })
        io.mockk.coVerify { schedules.deleteByPipeline(buildId) }
        io.mockk.coVerify { schedules.deleteByPipeline(deployId) }
    }

    @Test
    fun `catalog synchronization lock excludes other requests until commit`() = withDb {
        suspend fun contenderCanLock(): Boolean {
            val contender = pool.connection()
            return try {
                withContext(contender.asCoroutineContext()) {
                    transaction {
                        connection().useStatement(
                            "select pg_try_advisory_xact_lock(hashtextextended(cast(? as text), 0))"
                        ) { statement ->
                            statement.setString(1, repositoryId.toString())
                            statement.executeQuery().use { result ->
                                result.next()
                                result.getBoolean(1)
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { contender.release() }
            }
        }

        transaction {
            assertTrue(pipelineRepo.lockForSync(repositoryId))
            assertFalse(contenderCanLock())
        }
        assertTrue(contenderCanLock())
    }

    // ─── Helpers ────────────────────────────────────────────────

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) {
                    block()
                }
            } finally {
                withContext(NonCancellable) {
                    manager.release()
                }
            }
        }
    }
}
