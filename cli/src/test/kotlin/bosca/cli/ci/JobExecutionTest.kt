package bosca.cli.ci

import bosca.cli.api.NetworkClient
import bosca.graphql.gen.ClaimJobData
import bosca.graphql.gen.DecryptPipelineSecretsData
import bosca.graphql.gen.GetPipelineRunData
import bosca.graphql.gen.GetRepositoryByIdData
import bosca.graphql.gen.ListPipelineSecretsData
import bosca.graphql.gen.ResolveJobSecretsData
import bosca.graphql.gen.UpdateJobStatusData
import bosca.graphql.gen.GitAgentMode
import bosca.graphql.gen.GitAgentStatus
import bosca.graphql.gen.GitCommitStatusState
import bosca.graphql.gen.GitPipelineRunStatus
import bosca.graphql.gen.GitPipelineTriggerType
import bosca.graphql.gen.LogLineInput
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class JobExecutionTest {

    private val repoId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val runId = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val jobId = Uuid.parse("33333333-3333-3333-3333-333333333333")
    private val step1Id = Uuid.parse("44444444-4444-4444-4444-444444444444")
    private val step2Id = Uuid.parse("55555555-5555-5555-5555-555555555555")
    private val step3Id = Uuid.parse("66666666-6666-6666-6666-666666666666")
    private val agentId = Uuid.parse("77777777-7777-7777-7777-777777777777")

    @Test
    fun `api exposes transient authentication for an unconfigured ephemeral agent`() = runTest {
        val network = NetworkClient("https://bosca.example/graphql")
        network.tokenProvider = { "kubernetes-secret-token" }
        val api = CiApi(network)

        assertEquals("https://bosca.example/graphql", api.serverUrl)
        assertEquals("kubernetes-secret-token", api.bearerToken())
    }

    @Test
    fun `targeted agent exits cleanly when its job became terminal before claim`() = runTest {
        val mockApi = MockCiApi()
        mockApi.targetedStatus = GitPipelineRunStatus.CANCELLED
        val workDir = Files.createTempDirectory("job-terminal-before-claim-test").toFile()
        try {
            AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = true,
                workDir = workDir,
                processEnvironment = mapOf("BOSCA_KUBERNETES_JOB_ID" to jobId.toString()),
            ).executeSingleJob(jobId)

            assertEquals(jobId, mockApi.targetedStatusJobId)
            assertTrue(mockApi.jobStatusUpdates.isEmpty())
            assertTrue(!mockApi.deregistered)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `targeted agent fails loudly when an active job cannot be claimed`() = runTest {
        val mockApi = MockCiApi()
        mockApi.targetedStatus = GitPipelineRunStatus.QUEUED
        val workDir = Files.createTempDirectory("job-active-claim-loss-test").toFile()
        try {
            val failure = assertFailsWith<IllegalStateException> {
                AgentRunner(
                    mockApi,
                    agentId,
                    listOf("default"),
                    ephemeral = true,
                    workDir = workDir,
                    processEnvironment = emptyMap(),
                ).executeSingleJob(jobId)
            }

            assertTrue(failure.message.orEmpty().contains("QUEUED"))
            assertEquals(jobId, mockApi.targetedStatusJobId)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `executes all steps in order and reports success`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(listOf(
            makeStep(step1Id, "Init", 0, run = "echo 'init'"),
            makeStep(step2Id, "Build", 1, run = "echo 'building'"),
            makeStep(step3Id, "Test", 2, run = "echo 'testing'"),
        ))

        val workDir = Files.createTempDirectory("job-exec-test").toFile()
        try {
            val runner = AgentRunner(mockApi, agentId, listOf("default"), ephemeral = false, workDir = workDir, processEnvironment = emptyMap())
            runner.executeSingleJob(jobId)

            assertEquals(jobId, mockApi.targetedClaimJobId)
            assertEquals(GitPipelineRunStatus.RUNNING, mockApi.jobStatusUpdates[0])
            assertEquals(GitPipelineRunStatus.SUCCESS, mockApi.jobStatusUpdates.last())

            assertTrue(mockApi.stepStatusUpdates.containsKey(step2Id))
            assertTrue(mockApi.stepStatusUpdates.containsKey(step3Id))

            val step2Statuses = mockApi.stepStatusUpdates[step2Id]!!
            assertEquals(GitPipelineRunStatus.RUNNING, step2Statuses.first())
            assertEquals(GitPipelineRunStatus.SUCCESS, step2Statuses.last())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `agents execute jobs in their configured work directories`() = runTest {
        val root = Files.createTempDirectory("agent-configured-workdirs").toFile()
        try {
            for (name in listOf("agent-one", "agent-two")) {
                val configuredDir = File(root, name)
                val config = AgentConfig(
                    agentId = Uuid.random().toString(),
                    token = "test-token",
                    serverUrl = "https://bosca.example",
                    workDir = configuredDir.absolutePath,
                )
                val mockApi = MockCiApi().apply {
                    pipelineRun = makePipelineRun()
                    claimedJob = makeJob(emptyList())
                }

                AgentRunner(
                    mockApi,
                    Uuid.parse(config.agentId),
                    listOf("default"),
                    agentConfig = config,
                    processEnvironment = emptyMap(),
                ).executeSingleJob(jobId)

                assertEquals(GitPipelineRunStatus.SUCCESS, mockApi.jobStatusUpdates.last())
                assertTrue(File(configuredDir, "cache").isDirectory)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `single job execution keeps heartbeating until the job finishes`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(
            listOf(makeStep(step1Id, "Wait", 0, run = "sleep 0.1"))
        )

        val workDir = Files.createTempDirectory("job-heartbeat-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = false,
                workDir = workDir,
                heartbeatIntervalMs = 10,
                processEnvironment = emptyMap(),
            )

            runner.executeSingleJob(jobId)

            assertTrue(mockApi.heartbeatCount >= 2)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `single ephemeral execution deregisters when its initial heartbeat fails`() = runTest {
        val mockApi = MockCiApi()
        mockApi.heartbeatFailure = IllegalStateException("agent was retired")
        val workDir = Files.createTempDirectory("job-heartbeat-failure-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = true,
                workDir = workDir,
                processEnvironment = emptyMap(),
            )

            assertFailsWith<IllegalStateException> {
                runner.executeSingleJob(jobId)
            }

            assertTrue(mockApi.deregistered)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `polling runner preserves cancellation and shuts down heartbeat`() = runTest {
        val mockApi = MockCiApi()
        val workDir = Files.createTempDirectory("job-run-cancellation-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                pollIntervalSeconds = 1,
                heartbeatIntervalMs = 1,
                workDir = workDir,
                processEnvironment = emptyMap(),
            )

            assertFailsWith<CancellationException> {
                withTimeout(50) { runner.run() }
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `assigned job cancellation stops a persistent runner job`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(
            listOf(makeStep(step1Id, "Long build", 0, run = "sleep 60"))
        )
        mockApi.targetedStatus = GitPipelineRunStatus.CANCELLED
        val workDir = Files.createTempDirectory("job-targeted-cancellation-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                workDir = workDir,
                cancellationCheckIntervalMs = 10,
                processEnvironment = emptyMap(),
            )

            runner.executeSingleJob(jobId)

            assertEquals(jobId, mockApi.targetedStatusJobId)
            assertEquals(1, mockApi.targetedStatusAttempt)
            assertEquals(GitPipelineRunStatus.CANCELLED, mockApi.jobStatusUpdates.last())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `heartbeat loop backs off after a transient failure`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(
            listOf(makeStep(step1Id, "Wait", 0, run = "sleep 0.1"))
        )
        mockApi.heartbeatFailureAfter = 1
        val workDir = Files.createTempDirectory("job-heartbeat-backoff-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                heartbeatIntervalMs = 1,
                workDir = workDir,
                processEnvironment = emptyMap(),
            )

            runner.executeSingleJob(jobId)
            assertTrue(mockApi.heartbeatAttempts > 1)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `ephemeral cleanup logs deregistration failure without hiding the job failure`() = runTest {
        val mockApi = MockCiApi()
        mockApi.deregisterFailure = IllegalStateException("temporary cleanup failure")
        val workDir = Files.createTempDirectory("job-deregister-failure-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = true,
                workDir = workDir,
                processEnvironment = emptyMap(),
            )

            val failure = assertFailsWith<IllegalStateException> {
                runner.executeSingleJob(jobId)
            }
            assertTrue(failure.message.orEmpty().contains("no longer available"))
            assertTrue(mockApi.deregistered)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `ephemeral cleanup preserves deregistration cancellation`() = runTest {
        val mockApi = MockCiApi()
        mockApi.deregisterFailure = CancellationException("cleanup cancelled")
        val workDir = Files.createTempDirectory("job-deregister-cancellation-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = true,
                workDir = workDir,
                processEnvironment = emptyMap(),
            )

            val failure = assertFailsWith<CancellationException> {
                runner.executeSingleJob(jobId)
            }
            assertEquals("cleanup cancelled", failure.message)
            assertTrue(mockApi.deregistered)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `single ephemeral execution fails when its target is no longer claimable`() = runTest {
        val mockApi = MockCiApi()
        val workDir = Files.createTempDirectory("job-missing-target-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = true,
                workDir = workDir,
                processEnvironment = emptyMap(),
            )

            assertFailsWith<IllegalStateException> {
                runner.executeSingleJob(jobId)
            }

            assertTrue(mockApi.deregistered)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `missing pipeline run fails the claimed job`() = runTest {
        val mockApi = MockCiApi()
        mockApi.claimedJob = makeJob(emptyList())
        val workDir = Files.createTempDirectory("job-missing-run-test").toFile()
        try {
            AgentRunner(mockApi, agentId, listOf("default"), workDir = workDir, processEnvironment = emptyMap())
                .executeSingleJob(jobId)

            assertEquals(GitPipelineRunStatus.FAILURE, mockApi.jobStatusUpdates.last())
            assertTrue(mockApi.jobErrorMessage.orEmpty().contains("Pipeline run not found"))
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `secret resolution failure settles the claimed job`() = runTest {
        val mockApi = MockCiApi()
        mockApi.claimedJob = makeJob(emptyList())
        mockApi.pipelineRun = makePipelineRun()
        mockApi.resolveSecretsFailure = IllegalStateException("vault unavailable")
        val workDir = Files.createTempDirectory("job-secret-resolution-test").toFile()
        try {
            AgentRunner(mockApi, agentId, listOf("default"), workDir = workDir, processEnvironment = emptyMap())
                .executeSingleJob(jobId)

            assertEquals(GitPipelineRunStatus.FAILURE, mockApi.jobStatusUpdates.last())
            assertTrue(mockApi.jobErrorMessage.orEmpty().contains("secret", ignoreCase = true))
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `skips remaining steps after failure`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(listOf(
            makeStep(step1Id, "Init", 0, run = "echo 'init'"),
            makeStep(step2Id, "Fail Step", 1, run = "exit 1"),
            makeStep(step3Id, "Should Skip", 2, run = "echo 'should not run'"),
        ))

        val workDir = Files.createTempDirectory("job-exec-test").toFile()
        try {
            val runner = AgentRunner(mockApi, agentId, listOf("default"), ephemeral = false, workDir = workDir, processEnvironment = emptyMap())
            runner.executeSingleJob(jobId)

            assertEquals(GitPipelineRunStatus.FAILURE, mockApi.jobStatusUpdates.last())
            assertEquals("Step 'Fail Step' failed", mockApi.jobErrorMessage)

            val step3Statuses = mockApi.stepStatusUpdates[step3Id]!!
            assertTrue(step3Statuses.contains(GitPipelineRunStatus.SKIPPED))
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `reports a failure summary from the failed step's stderr tail`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(listOf(
            makeStep(
                step1Id, "Build", 0,
                run = "echo 'compiling'; echo 'e: Main.kt:10:5 unresolved reference' >&2; " +
                    "echo 'FAILURE: Build failed with an exception.' >&2; exit 1",
            ),
        ))

        val workDir = Files.createTempDirectory("job-exec-test").toFile()
        try {
            val runner = AgentRunner(mockApi, agentId, listOf("default"), ephemeral = false, workDir = workDir, processEnvironment = emptyMap())
            runner.executeSingleJob(jobId)

            val summary = mockApi.stepErrorMessages[step1Id]
            assertNotNull(summary, "Failed step should report an error summary")
            assertTrue(summary.contains("e: Main.kt:10:5 unresolved reference"))
            assertTrue(summary.contains("FAILURE: Build failed with an exception."))
            assertTrue(!summary.contains("compiling"), "stdout noise should not displace stderr in the summary")
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `reports no failure summary on success`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(listOf(
            makeStep(step1Id, "Build", 0, run = "echo 'ok'"),
        ))

        val workDir = Files.createTempDirectory("job-exec-test").toFile()
        try {
            val runner = AgentRunner(mockApi, agentId, listOf("default"), ephemeral = false, workDir = workDir, processEnvironment = emptyMap())
            runner.executeSingleJob(jobId)

            assertNull(mockApi.stepErrorMessages[step1Id])
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `runs step with failure() condition after prior step fails`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(listOf(
            makeStep(step1Id, "Init", 0, run = "echo 'init'"),
            makeStep(step2Id, "Fail Step", 1, run = "exit 1"),
            makeStep(step3Id, "Notify on Failure", 2, run = "echo 'notifying'", condition = "failure()"),
        ))

        val workDir = Files.createTempDirectory("job-exec-test").toFile()
        try {
            val runner = AgentRunner(mockApi, agentId, listOf("default"), ephemeral = false, workDir = workDir, processEnvironment = emptyMap())
            runner.executeSingleJob(jobId)

            val step3Statuses = mockApi.stepStatusUpdates[step3Id]!!
            assertTrue(step3Statuses.contains(GitPipelineRunStatus.RUNNING),
                "Notify step should have run due to failure() condition. Got: $step3Statuses")
            assertTrue(step3Statuses.contains(GitPipelineRunStatus.SUCCESS))
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `builtin env vars are available in step expressions`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(listOf(
            makeStep(step1Id, "Check SHA", 0, run = "echo \"\$COMMIT_SHA\" > sha.txt && test -n \"\$COMMIT_SHA\"",
                env = kotlinx.serialization.json.buildJsonObject {
                    put("COMMIT_SHA", kotlinx.serialization.json.JsonPrimitive("\${{ env.COMMIT_SHA }}"))
                }),
        ))

        val workDir = Files.createTempDirectory("job-exec-test").toFile()
        try {
            val runner = AgentRunner(mockApi, agentId, listOf("default"), ephemeral = false, workDir = workDir, processEnvironment = emptyMap())
            runner.executeSingleJob(jobId)

            assertEquals(GitPipelineRunStatus.SUCCESS, mockApi.jobStatusUpdates.last(),
                "Step using \${{ env.COMMIT_SHA }} should succeed when builtin env is injected")
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `control plane tokens are not available to pipeline expressions`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(listOf(
            makeStep(
                step1Id,
                "Check control token isolation",
                0,
                run = "test -z \"\$ALIASED_CONTROL_TOKEN\"",
                env = kotlinx.serialization.json.buildJsonObject {
                    put(
                        "ALIASED_CONTROL_TOKEN",
                        kotlinx.serialization.json.JsonPrimitive("\${{ env.BOSCA_TOKEN }}"),
                    )
                },
            ),
        ))

        val workDir = Files.createTempDirectory("job-token-isolation-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = false,
                workDir = workDir,
                processEnvironment = mapOf(
                    "BOSCA_TOKEN" to "control-plane-secret",
                    "SAFE_ENVIRONMENT_VALUE" to "available",
                ),
            )

            runner.executeSingleJob(jobId)

            assertEquals(GitPipelineRunStatus.SUCCESS, mockApi.jobStatusUpdates.last())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `kubernetes agent exits unsuccessfully after reporting a logical job failure`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(listOf(
            makeStep(step1Id, "Fail", 0, run = "exit 1"),
        ))

        val workDir = Files.createTempDirectory("job-kubernetes-failure-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = true,
                workDir = workDir,
                processEnvironment = mapOf(
                    "BOSCA_KUBERNETES_JOB_ID" to "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                ),
            )

            val failure = assertFailsWith<IllegalStateException> {
                runner.executeSingleJob(jobId)
            }

            assertTrue(failure.message.orEmpty().contains("completed with FAILURE"))
            assertEquals(GitPipelineRunStatus.FAILURE, mockApi.jobStatusUpdates.last())
            assertTrue(!mockApi.deregistered, "Kubernetes reconciliation owns ephemeral-agent retirement")
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `successful kubernetes agent leaves retirement to durable reconciliation`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(
            listOf(makeStep(step1Id, "Build", 0, run = "echo ok"))
        )
        val workDir = Files.createTempDirectory("job-kubernetes-success-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = true,
                workDir = workDir,
                processEnvironment = mapOf("BOSCA_KUBERNETES_JOB_ID" to jobId.toString()),
            )

            runner.executeSingleJob(jobId)

            assertEquals(GitPipelineRunStatus.SUCCESS, mockApi.jobStatusUpdates.last())
            assertTrue(!mockApi.deregistered)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `kubernetes agent does not self-deregister when its initial heartbeat fails`() = runTest {
        val mockApi = MockCiApi()
        mockApi.heartbeatFailure = IllegalStateException("agent was retired")
        val workDir = Files.createTempDirectory("job-kubernetes-heartbeat-failure-test").toFile()
        try {
            val runner = AgentRunner(
                mockApi,
                agentId,
                listOf("default"),
                ephemeral = true,
                workDir = workDir,
                processEnvironment = mapOf("BOSCA_KUBERNETES_JOB_ID" to jobId.toString()),
            )

            assertFailsWith<IllegalStateException> { runner.executeSingleJob(jobId) }
            assertTrue(!mockApi.deregistered)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `reports commit status after job completes`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(listOf(
            makeStep(step1Id, "Init", 0, run = "echo 'init'"),
            makeStep(step2Id, "Build", 1, run = "echo 'building'"),
        ))

        val workDir = Files.createTempDirectory("job-exec-test").toFile()
        try {
            val runner = AgentRunner(mockApi, agentId, listOf("default"), ephemeral = false, workDir = workDir, processEnvironment = emptyMap())
            runner.executeSingleJob(jobId)

            assertTrue(mockApi.commitStatusReported)
            assertEquals(GitCommitStatusState.SUCCESS, mockApi.lastCommitState)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `commit status transport failure does not erase the completed job result`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(
            listOf(makeStep(step1Id, "Build", 0, run = "echo ok"))
        )
        mockApi.commitStatusFailure = IllegalStateException("status endpoint unavailable")
        val workDir = Files.createTempDirectory("job-commit-status-failure-test").toFile()
        try {
            AgentRunner(mockApi, agentId, listOf("default"), workDir = workDir, processEnvironment = emptyMap())
                .executeSingleJob(jobId)

            assertEquals(GitPipelineRunStatus.SUCCESS, mockApi.jobStatusUpdates.last())
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `commit status call preserves coroutine cancellation`() = runTest {
        val mockApi = MockCiApi()
        mockApi.pipelineRun = makePipelineRun()
        mockApi.claimedJob = makeJob(
            listOf(makeStep(step1Id, "Build", 0, run = "echo ok"))
        )
        mockApi.commitStatusFailure = CancellationException("cancel test")
        val workDir = Files.createTempDirectory("job-commit-status-cancel-test").toFile()
        try {
            assertFailsWith<CancellationException> {
                AgentRunner(mockApi, agentId, listOf("default"), workDir = workDir, processEnvironment = emptyMap())
                    .executeSingleJob(jobId)
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun makeStep(
        id: Uuid,
        name: String,
        ordinal: Int,
        uses: String? = null,
        run: String? = null,
        condition: String? = null,
        with: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonObject(emptyMap()),
        env: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonObject(emptyMap()),
    ) = ClaimJobData.Git.ClaimJob.Steps(
        id = id,
        pipelineJobId = jobId,
        name = name,
        ordinal = ordinal,
        status = GitPipelineRunStatus.QUEUED,
        uses = uses,
        run = run,
        image = null,
        condition = condition,
        workingDirectory = null,
        with = with,
        env = env,
        exitCode = null,
        started = null,
        finished = null,
    )

    private fun makeJob(steps: List<ClaimJobData.Git.ClaimJob.Steps>) = ClaimJobData.Git.ClaimJob(
        id = jobId,
        pipelineRunId = runId,
        name = "build",
        status = GitPipelineRunStatus.QUEUED,
        attempt = 1,
        runnerLabel = "default",
        agentId = null,
        matrixValues = null,
        dependsOn = emptyList(),
        timeoutMinutes = null,
        started = null,
        finished = null,
        steps = steps,
    )

    private fun makePipelineRun() = GetPipelineRunData.Git.PipelineRun(
        id = runId,
        pipelineId = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        repositoryId = repoId,
        commitSha = "abc123def456",
        ref = "refs/heads/main",
        triggerType = GitPipelineTriggerType.PUSH,
        triggeredBy = null,
        status = GitPipelineRunStatus.RUNNING,
        number = 1,
        concurrencyGroup = null,
        created = ZonedDateTime.now(),
        started = ZonedDateTime.now(),
        finished = null,
        durationSeconds = null,
        jobs = emptyList(),
    )
}

class MockCiApi : CiApi(NetworkClient("http://localhost:0")) {

    var claimedJob: ClaimJobData.Git.ClaimJob? = null
    var targetedClaimJobId: Uuid? = null
    var targetedStatus: GitPipelineRunStatus? = null
    var targetedStatusJobId: Uuid? = null
    var targetedStatusAttempt: Int? = null
    var heartbeatCount = 0
    var heartbeatAttempts = 0
    var heartbeatFailure: Exception? = null
    var heartbeatFailureAfter: Int? = null
    var deregistered = false
    var deregisterFailure: Exception? = null
    var pipelineRun: GetPipelineRunData.Git.PipelineRun? = null
    var resolveSecretsFailure: Exception? = null

    val jobStatusUpdates = mutableListOf<GitPipelineRunStatus>()
    var jobErrorMessage: String? = null
    val stepStatusUpdates = mutableMapOf<Uuid, MutableList<GitPipelineRunStatus>>()
    val stepErrorMessages = mutableMapOf<Uuid, String?>()
    val logLines = mutableListOf<LogLineInput>()
    var commitStatusReported = false
    var lastCommitState: GitCommitStatusState? = null
    var commitStatusFailure: Exception? = null

    override suspend fun agentHeartbeat(agentId: Uuid): Boolean {
        heartbeatAttempts++
        heartbeatFailure?.let { throw it }
        heartbeatFailureAfter?.let { successfulAttempts ->
            if (heartbeatCount >= successfulAttempts) {
                throw IllegalStateException("transient heartbeat failure")
            }
        }
        heartbeatCount++
        return true
    }

    override suspend fun deregisterAgent(id: Uuid): Boolean {
        deregistered = true
        deregisterFailure?.let { throw it }
        return true
    }

    override suspend fun claimJob(agentId: Uuid, labels: List<String>) = claimedJob.also { claimedJob = null }

    override suspend fun claimJobById(agentId: Uuid, jobId: Uuid) =
        claimedJob.also {
            targetedClaimJobId = jobId
            claimedJob = null
        }

    override suspend fun targetedJobStatus(agentId: Uuid, jobId: Uuid, attempt: Int?) =
        targetedStatus.also {
            targetedStatusJobId = jobId
            targetedStatusAttempt = attempt
        }

    override suspend fun getPipelineRun(id: Uuid) = pipelineRun

    override suspend fun updateJobStatus(jobId: Uuid, status: GitPipelineRunStatus, errorMessage: String?) =
        UpdateJobStatusData.Git.UpdateJobStatus(
            id = jobId, name = "build", status = status, started = null, finished = null
        ).also {
            jobStatusUpdates.add(status)
            if (status == GitPipelineRunStatus.FAILURE) {
                jobErrorMessage = errorMessage
            }
        }

    override suspend fun updateStepStatus(stepId: Uuid, status: GitPipelineRunStatus, exitCode: Int?, errorMessage: String?) =
        true.also {
            stepStatusUpdates.getOrPut(stepId) { mutableListOf() }.add(status)
            if (status == GitPipelineRunStatus.FAILURE) {
                stepErrorMessages[stepId] = errorMessage
            }
        }

    override suspend fun reportCommitStatus(
        repositoryId: Uuid, commitSha: String, context: String,
        state: GitCommitStatusState, description: String?, targetUrl: String?, jobId: Uuid
    ): Boolean {
        commitStatusFailure?.let { throw it }
        commitStatusReported = true
        lastCommitState = state
        return true
    }

    override suspend fun appendPipelineLogs(
        repositoryId: Uuid, runId: Uuid, jobId: Uuid, stepId: Uuid, lines: List<LogLineInput>
    ) = true.also { logLines.addAll(lines) }

    override suspend fun listPipelineSecrets(repositoryId: Uuid) =
        emptyList<ListPipelineSecretsData.Git.PipelineSecrets>()

    override suspend fun decryptPipelineSecrets(repositoryId: Uuid, names: List<String>) =
        emptyList<DecryptPipelineSecretsData.Git.DecryptPipelineSecrets>()

    override suspend fun resolveJobSecrets(jobId: Uuid): List<ResolveJobSecretsData.Git.ResolveJobSecrets> {
        resolveSecretsFailure?.let { throw it }
        return emptyList()
    }

    override suspend fun getRepositoryById(id: Uuid): GetRepositoryByIdData.Git.RepositoryById? = null
}
