@file:OptIn(ExperimentalCoroutinesApi::class)

package bosca.cli.ci

import bosca.graphql.gen.ClaimJobData
import bosca.graphql.gen.GitCommitStatusState
import bosca.graphql.gen.GitPipelineRunStatus
import bosca.graphql.gen.LogLineInput
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class AgentRunner(
    private val api: CiApi,
    private val agentId: Uuid,
    initialLabels: List<String>,
    private val pollIntervalSeconds: Int = 5,
    private val ephemeral: Boolean = false,
    agentConfig: AgentConfig? = AgentConfig.load(),
    private val workDir: File = agentConfig?.workDir?.let(::File)
        ?: File(System.getProperty("java.io.tmpdir"), "bosca-ci"),
    private val heartbeatIntervalMs: Long = 10_000,
    private val cancellationCheckIntervalMs: Long = CANCEL_CHECK_INTERVAL_MS,
    private val processEnvironment: Map<String, String> = System.getenv().toMap(),
) {
    private val running = AtomicBoolean(true)
    @Volatile private var labels: List<String> = initialLabels
    @Volatile private var currentJobId: Uuid? = null
    private val log = CiLogger(agentId, "runner")
    private val heartbeatDispatcher = newSingleThreadContext("agent-heartbeat")

    companion object {
        const val MIN_DISK_SPACE_MB = 500L
        const val CANCEL_CHECK_INTERVAL_MS = 30_000L
        const val HEARTBEAT_WARN_THRESHOLD_MS = 15_000L
        const val HEARTBEAT_MAX_BACKOFF_MS = 300_000L
        private val CONTROL_PLANE_ENVIRONMENT = setOf(
            "BOSCA_TOKEN",
            "BOSCA_CI_AGENT_TOKEN",
        )
        private val TERMINAL_JOB_STATUSES = setOf(
            GitPipelineRunStatus.SUCCESS,
            GitPipelineRunStatus.FAILURE,
            GitPipelineRunStatus.CANCELLED,
            GitPipelineRunStatus.SKIPPED,
        )
    }

    private val expressionParser = ExpressionParser()
    private val expressionEnvironment = processEnvironment - CONTROL_PLANE_ENVIRONMENT

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            running.set(false)
            val jobId = currentJobId ?: return@Thread
            runBlocking {
                try {
                    api.updateJobStatus(jobId, GitPipelineRunStatus.FAILURE, "Agent process shut down while the job was running")
                } catch (e: Exception) {
                    // Can't rethrow from a shutdown hook; at minimum leave a
                    // trace so the stuck-RUNNING job can be tied back to this.
                    System.err.println("bosca-agent: failed to report job $jobId failure during shutdown: ${e.message}")
                }
            }
        })
    }

    private fun CoroutineScope.launchHeartbeat() = launch(heartbeatDispatcher) {
        var consecutiveFailures = 0
        while (running.get()) {
            try {
                val start = System.currentTimeMillis()
                api.agentHeartbeat(agentId)
                val elapsed = System.currentTimeMillis() - start
                if (elapsed > HEARTBEAT_WARN_THRESHOLD_MS) {
                    log.warn("Heartbeat slow", "roundTripMs" to elapsed)
                }
                refreshLabels()
                consecutiveFailures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                val backoff = (heartbeatIntervalMs shl consecutiveFailures.coerceAtMost(5))
                    .coerceAtMost(HEARTBEAT_MAX_BACKOFF_MS)
                log.warn(
                    "Heartbeat failed, retrying in ${backoff}ms",
                    "error" to e.message,
                    "consecutiveFailures" to consecutiveFailures,
                )
                delay(backoff)
                continue
            }
            delay(heartbeatIntervalMs)
        }
    }

    suspend fun run() = coroutineScope {
        val heartbeatJob = launchHeartbeat()
        try {
            while (running.get()) {
                try {
                    val job = api.claimJob(agentId, labels)
                    if (job != null) {
                        executeJob(job)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Poll error", e)
                }
                delay(pollIntervalSeconds * 1000L)
            }
        } finally {
            running.set(false)
            withContext(NonCancellable) {
                heartbeatJob.cancelAndJoin()
                heartbeatDispatcher.close()
                if (ephemeral && !isKubernetesDispatch()) {
                    deregisterEphemeralAgent()
                }
            }
        }
    }

    suspend fun executeSingleJob(jobId: Uuid) = coroutineScope {
        var heartbeatJob: Job? = null
        try {
            api.agentHeartbeat(agentId)
            heartbeatJob = launchHeartbeat()
            val job = api.claimJobById(agentId, jobId)
            if (job != null) {
                executeJob(job)
            } else {
                val status = api.targetedJobStatus(agentId, jobId)
                if (status in TERMINAL_JOB_STATUSES) {
                    log.info(
                        "Target job became terminal before this agent claimed it",
                        "targetJobId" to jobId,
                        "status" to status,
                    )
                } else {
                    log.warn(
                        "No job available to claim",
                        "targetJobId" to jobId,
                        "status" to status,
                    )
                    error(
                        "Target job $jobId is no longer available to this ephemeral agent" +
                            status?.let { " while in state ${it.name}" }.orEmpty()
                    )
                }
            }
        } finally {
            running.set(false)
            withContext(NonCancellable) {
                heartbeatJob?.cancelAndJoin()
                heartbeatDispatcher.close()
                if (ephemeral && !isKubernetesDispatch()) {
                    deregisterEphemeralAgent()
                }
            }
        }
    }

    private fun isKubernetesDispatch(): Boolean =
        !processEnvironment["BOSCA_KUBERNETES_JOB_ID"].isNullOrBlank()

    private suspend fun deregisterEphemeralAgent() {
        try {
            api.deregisterAgent(agentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to deregister ephemeral agent", "error" to e.message)
        }
    }

    private suspend fun executeJob(job: ClaimJobData.Git.ClaimJob) = coroutineScope {
        // Poll the assigned job rather than the whole run: run cancellation settles every active
        // job too, while this also lets one job be stopped without touching its siblings.
        val initiallyCancelled = isJobCancelled(job.id, job.attempt)
        val jobCancelled = AtomicBoolean(initiallyCancelled)
        if (initiallyCancelled) {
            log.info("Job cancellation detected", "jobId" to job.id)
        }
        val watcher = launch {
            while (isActive && !jobCancelled.get()) {
                delay(cancellationCheckIntervalMs.milliseconds)
                if (isJobCancelled(job.id, job.attempt)) {
                    log.info("Job cancellation detected", "jobId" to job.id)
                    jobCancelled.set(true)
                    break
                }
            }
        }
        val terminalStatus = try {
            executeJobSteps(job, jobCancelled)
        } finally {
            watcher.cancel()
        }
        check(!isKubernetesDispatch() || terminalStatus == GitPipelineRunStatus.SUCCESS) {
            "Kubernetes CI job ${job.id} completed with ${terminalStatus.name}"
        }
    }

    private suspend fun executeJobSteps(
        job: ClaimJobData.Git.ClaimJob,
        jobCancelled: AtomicBoolean,
    ): GitPipelineRunStatus {
        val jobId = job.id
        val pipelineRunId = job.pipelineRunId
        currentJobId = jobId
        log.withJob(jobId)
        log.info("Job started", "jobName" to job.name, "runnerLabel" to job.runnerLabel)

        val jobDir = File(workDir, jobId.toString())
        jobDir.mkdirs()
        val srcDir = File(jobDir, "src")
        srcDir.mkdirs()
        val sharedEnvFile = File(jobDir, ".bosca_env")
        val sharedPathFile = File(jobDir, ".bosca_path")
        withContext(Dispatchers.IO) {
            sharedEnvFile.createNewFile()
        }
        withContext(Dispatchers.IO) {
            sharedPathFile.createNewFile()
        }

        try {
            val availableMb = workDir.usableSpace / (1024 * 1024)
            if (availableMb < MIN_DISK_SPACE_MB) {
                val ex = InsufficientDiskException(availableMb, MIN_DISK_SPACE_MB)
                log.error("Disk check failed", ex, "availableMb" to availableMb)
                api.updateJobStatus(jobId, GitPipelineRunStatus.FAILURE, ex.message)
                return GitPipelineRunStatus.FAILURE
            }

            withRetry { api.updateJobStatus(jobId, GitPipelineRunStatus.RUNNING) }

            log.info("Loading pipeline run", "runId" to pipelineRunId)
            val run = api.getPipelineRun(pipelineRunId)
            if (run == null) {
                log.error("Pipeline run not found", "runId" to pipelineRunId)
                api.updateJobStatus(jobId, GitPipelineRunStatus.FAILURE, "Pipeline run not found: $pipelineRunId")
                return GitPipelineRunStatus.FAILURE
            }

            val repositoryId = run.repositoryId
            val commitSha = run.commitSha
            val ref = run.ref
            val branch = ref.removePrefix("refs/heads/")

            log.info("Resolving repository", "repositoryId" to repositoryId)
            val cloneUrl = api.getRepositoryById(repositoryId)?.cloneUrl ?: ""

            val matrixValues = extractMatrixValues(job)

            log.info("Resolving secrets")
            // The server decides what this job may have: the pipeline's declared
            // secrets (or the legacy repository set), environment scopes honored, evaluated against
            // the run's initiating principal. A refusal names the secret and fails the job here —
            // never a silently missing value at step time.
            val secrets = try {
                api.resolveJobSecrets(jobId).associate { it.name to it.value }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                val ex = SecretDecryptionException(repositoryId, e)
                log.error("Secret resolution failed", ex)
                api.updateJobStatus(jobId, GitPipelineRunStatus.FAILURE, ex.message)
                return GitPipelineRunStatus.FAILURE
            }

            val agentConfig = AgentConfig.load()
            val serverUrl = (agentConfig?.serverUrl ?: api.serverUrl).let { url ->
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            }
            val agentToken = agentConfig?.token?.takeIf(String::isNotBlank) ?: api.bearerToken()
            val registryUrl = processEnvironment["BOSCA_REGISTRY_URL"]
                ?.takeIf(String::isNotBlank)
                ?: agentConfig?.registryUrl.orEmpty()

            val steps = job.steps.sortedBy { it.ordinal }
            var jobStatus = "success"
            var firstFailedStep: String? = null
            val pendingCacheSaves = mutableListOf<CacheSaveRequest>()

            for (step in steps) {
                if (!running.get()) break
                log.withStep(step.id)

                // The cancel watcher polls the server; this is just an atomic read, so every step
                // boundary sees a cancellation immediately.
                if (jobStatus != "cancelled" && jobCancelled.get()) {
                    log.info("Job cancelled, aborting remaining steps")
                    jobStatus = "cancelled"
                }
                if (jobStatus == "cancelled") {
                    withRetry { api.updateStepStatus(step.id, GitPipelineRunStatus.CANCELLED) }
                    continue
                }

                val stepDef = resolveStepDefinition(step)

                val envFromFile = readSharedEnvFile(sharedEnvFile)
                val builtinEnv = mapOf(
                    "COMMIT_SHA" to commitSha,
                    "CI_REF" to ref,
                    "CI_BRANCH" to branch,
                    "CI_EVENT" to run.triggerType.name.lowercase(),
                    "CI_PIPELINE_RUN_ID" to pipelineRunId.toString(),
                    "CI_JOB_ID" to jobId.toString(),
                    "CI_REPOSITORY_ID" to repositoryId.toString(),
                )
                val context = ExpressionContext(
                    ref = ref,
                    branch = branch,
                    event = run.triggerType.name.lowercase(),
                    jobStatus = jobStatus,
                    matrix = matrixValues,
                    env = expressionEnvironment + builtinEnv + envFromFile,
                    secrets = secrets,
                    fileHasher = { globs -> hashFilesInDir(jobDir, globs) },
                )

                if (stepDef?.condition != null) {
                    val shouldRun = expressionParser.evaluateBoolean(stepDef.condition, context)
                    if (!shouldRun) {
                        log.debug("Step skipped by condition", "condition" to stepDef.condition)
                        withRetry { api.updateStepStatus(step.id, GitPipelineRunStatus.SKIPPED) }
                        continue
                    }
                } else if (jobStatus != "success") {
                    withRetry { api.updateStepStatus(step.id, GitPipelineRunStatus.SKIPPED) }
                    continue
                }

                withRetry { api.updateStepStatus(step.id, GitPipelineRunStatus.RUNNING) }
                val stepStart = System.currentTimeMillis()

                val logBuffer = LogBuffer(
                    api = api,
                    repositoryId = repositoryId,
                    runId = pipelineRunId,
                    jobId = jobId,
                    stepId = step.id,
                    secretValues = (secrets.values + agentToken)
                        .filter(String::isNotBlank)
                        .toSet(),
                )
                logBuffer.start()
                log.setActiveBuffer(logBuffer)
                val timeoutMinutes = job.timeoutMinutes ?: 60

                val stepWorkDir = if (stepDef?.workingDirectory != null) {
                    val resolved = File(srcDir, stepDef.workingDirectory)
                    resolved.mkdirs()
                    resolved
                } else srcDir

                val executor = StepExecutor(
                    workDir = stepWorkDir,
                    serverUrl = serverUrl,
                    agentToken = agentToken,
                    registryUrl = registryUrl,
                    commitSha = commitSha,
                    ref = ref,
                    repositoryId = repositoryId.toString(),
                    cloneUrl = cloneUrl,
                    pipelineRunId = pipelineRunId.toString(),
                    api = api,
                    jobId = jobId,
                    env = if (stepDef != null) buildStepEnv(stepDef, context) else emptyMap(),
                    secrets = secrets,
                    logBuffer = logBuffer,
                    timeoutMinutes = timeoutMinutes,
                    sharedEnvFile = sharedEnvFile,
                    sharedPathFile = sharedPathFile,
                    cancelled = { jobCancelled.get() },
                )

                val result = try {
                    if (stepDef != null) {
                        if (stepDef.uses == "cache") {
                            val resolvedWith = stepDef.with.mapValues { (_, v) ->
                                expressionParser.interpolate(v, context)
                            }
                            pendingCacheSaves.add(CacheSaveRequest(
                                key = resolvedWith["key"] ?: "",
                                paths = resolvedWith["paths"]?.split(",")?.map { it.trim() } ?: emptyList(),
                            ))
                        }
                        executor.execute(stepDef, context)
                    } else {
                        logBuffer.add("Executing step: ${step.name}", "stdout")
                        logBuffer.flush()
                        StepResult(true, 0)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logBuffer.add("Step execution error: ${e.message}", "stderr")
                    StepResult(false, 1)
                } finally {
                    log.setActiveBuffer(null)
                    logBuffer.flush()
                    logBuffer.close()
                }

                val stepDurationMs = System.currentTimeMillis() - stepStart
                if (result.cancelled) {
                    withRetry { api.updateStepStatus(step.id, GitPipelineRunStatus.CANCELLED, result.exitCode) }
                    log.info("Step cancelled mid-execution", "step" to step.name, "durationMs" to stepDurationMs)
                    jobStatus = "cancelled"
                } else if (result.success) {
                    withRetry { api.updateStepStatus(step.id, GitPipelineRunStatus.SUCCESS, result.exitCode) }
                    log.info("Step succeeded", "step" to step.name, "durationMs" to stepDurationMs, "exitCode" to result.exitCode)
                } else {
                    val errorSummary = StepErrorSummary.build(logBuffer.tailLines())
                    withRetry { api.updateStepStatus(step.id, GitPipelineRunStatus.FAILURE, result.exitCode, errorSummary) }
                    log.warn("Step failed", "step" to step.name, "durationMs" to stepDurationMs, "exitCode" to result.exitCode)
                    if (firstFailedStep == null) firstFailedStep = step.name
                    jobStatus = "failure"
                }

                log.clearStep()
            }

            savePendingCaches(srcDir, pendingCacheSaves, serverUrl, agentToken, repositoryId)

            val finalStatus = when (jobStatus) {
                "success" -> GitPipelineRunStatus.SUCCESS
                "cancelled" -> GitPipelineRunStatus.CANCELLED
                else -> GitPipelineRunStatus.FAILURE
            }
            // The step record carries the detailed summary; the job-level
            // message just points at which step to look at.
            val jobError = firstFailedStep?.let { "Step '$it' failed" }
            val commitState = when (jobStatus) {
                "success" -> GitCommitStatusState.SUCCESS
                else -> GitCommitStatusState.FAILURE
            }
            try {
                api.reportCommitStatus(
                    repositoryId, commitSha, "ci/${job.name}",
                    commitState, "${job.name}: $finalStatus", jobId = jobId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to report commit status", "commitSha" to commitSha, "error" to e.message)
            }

            withRetry { api.updateJobStatus(jobId, finalStatus, jobError) }

            log.info("Job completed", "status" to finalStatus.name)
            return finalStatus
        } catch (e: CancellationException) {
            throw e
        } catch (e: CiException) {
            log.error("Job failed with CI error", e)
            reportJobFailure(jobId, e.message)
            return GitPipelineRunStatus.FAILURE
        } catch (e: Throwable) {
            // Includes Errors (OutOfMemoryError, StackOverflowError, etc.) — without this,
            // an Error kills the coroutine silently and leaves the job orphaned.
            log.error("Job failed with unexpected error", e)
            reportJobFailure(jobId, "Unexpected agent error: ${e.message ?: e.javaClass.simpleName}")
            if (e is Error) throw e
            return GitPipelineRunStatus.FAILURE
        } finally {
            jobDir.deleteRecursively()
            log.clearJob()
            currentJobId = null
        }
    }

    /**
     * Last-resort job failure report from an exception path. Retries like
     * the happy-path status updates; if the server still can't be reached,
     * logs loudly instead of throwing — we're already unwinding from a
     * failure, and while the reaper will eventually fail the job as
     * orphaned, the original cause would be lost without a trace here.
     */
    private suspend fun reportJobFailure(jobId: Uuid, errorMessage: String?) {
        try {
            withRetry { api.updateJobStatus(jobId, GitPipelineRunStatus.FAILURE, errorMessage) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to report job failure to server; job will be reaped as orphaned", e, "jobId" to jobId)
        }
    }

    private suspend fun refreshLabels() {
        try {
            val agents = api.listAgents(null)
            val agent = agents.find { it.id == agentId }
            if (agent != null && agent.labels != labels) {
                log.info("Labels updated", "old" to labels.joinToString(","), "new" to agent.labels.joinToString(","))
                labels = agent.labels
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun isJobCancelled(jobId: Uuid, attempt: Int): Boolean {
        return try {
            api.targetedJobStatus(agentId, jobId, attempt) == GitPipelineRunStatus.CANCELLED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveStepDefinition(step: ClaimJobData.Git.ClaimJob.Steps): StepDefinition? {
        if (step.uses == null && step.run == null) return null
        return StepDefinition(
            name = step.name,
            uses = step.uses,
            run = step.run,
            image = step.image,
            condition = step.condition,
            workingDirectory = step.workingDirectory,
            with = jsonElementToStringMap(step.with),
            env = jsonElementToStringMap(step.env),
        )
    }

    private fun jsonElementToStringMap(element: JsonElement?): Map<String, String> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.entries.associate { (k, v) ->
            k to when (v) {
                is JsonPrimitive -> v.content
                is JsonArray -> v.joinToString(",") { (it as? JsonPrimitive)?.content ?: it.toString() }
                else -> v.toString()
            }
        }
    }

    private fun savePendingCaches(jobDir: File, requests: List<CacheSaveRequest>, serverUrl: String, agentToken: String, repositoryId: Uuid) {
        val cacheDir = File(workDir, "cache")
        cacheDir.mkdirs()

        for (req in requests) {
            if (req.key.isEmpty() || req.paths.isEmpty()) continue
            val keyHash = hashString(req.key)
            val cacheFile = File(cacheDir, "$keyHash.tar.gz")
            if (cacheFile.exists()) continue

            try {
                val expandedPaths = req.paths.map { path ->
                    if (path.startsWith("~")) {
                        path.replaceFirst("~", System.getProperty("user.home"))
                    } else if (!File(path).isAbsolute) {
                        File(jobDir, path).absolutePath
                    } else path
                }.filter { File(it).exists() }

                if (expandedPaths.isEmpty()) continue

                val pathArgs = expandedPaths.joinToString(" ") { "'$it'" }
                val tarResult = ProcessBuilder("bash", "-c", "tar -czf '${cacheFile.absolutePath}' $pathArgs")
                    .directory(jobDir)
                    .start()
                    .waitFor()

                if (tarResult == 0 && cacheFile.exists()) {
                    try {
                        val uploadUrl = "$serverUrl/storage/$repositoryId/ci/cache/$keyHash.tar.gz"
                        ProcessBuilder(
                            "curl", "-sf", "-X", "PUT",
                            "-H", "Authorization: Bearer $agentToken",
                            "-H", "Content-Type: application/octet-stream",
                            "--data-binary", "@${cacheFile.absolutePath}",
                            uploadUrl
                        ).start().waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildStepEnv(step: StepDefinition, context: ExpressionContext): Map<String, String> {
        return step.env.mapValues { (_, v) -> expressionParser.interpolate(v, context) }
    }

    private fun extractMatrixValues(job: ClaimJobData.Git.ClaimJob): Map<String, String> {
        val matrixJson = job.matrixValues as? JsonObject ?: return emptyMap()
        return matrixJson.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
    }

}

data class StepResult(val success: Boolean, val exitCode: Int, val cancelled: Boolean = false)

data class CacheSaveRequest(val key: String, val paths: List<String>)

open class LogBuffer(
    private val api: CiApi,
    private val repositoryId: Uuid,
    private val runId: Uuid,
    private val jobId: Uuid,
    private val stepId: Uuid,
    private val secretValues: Set<String>,
    private val flushIntervalMs: Long = 500,
    private val maxBatchSize: Int = 100,
    private val maxTailLines: Int = 200,
) {
    private val lock = Any()
    private val buffer = mutableListOf<LogLineInput>()
    // Bounded ring of the most recent lines, retained across flushes so a
    // failure summary can still be derived after the upload buffer has
    // been flushed and cleared.
    private val tail = ArrayDeque<TailLine>()
    private var lineNumber = 0
    private val flushDispatcher = newSingleThreadContext("log-flush-$stepId")
    private val flushScope = CoroutineScope(flushDispatcher + SupervisorJob())
    private var flushJob: Job? = null
    @Volatile private var closing = false

    // Serializes *every* doFlush invocation — background loop, AgentRunner's
    // step-finally flush(), and close()'s final flush — so the server never
    // sees concurrent appendPipelineLogs calls for the same step. The server
    // currently does a read-modify-write on S3; without this mutex, two
    // overlapping flushes cause a lost-update race that silently drops lines.
    private val flushMutex = Mutex()

    fun start() {
        flushJob = flushScope.launch {
            while (!closing) {
                delay(flushIntervalMs)
                if (closing) break
                doFlush()
            }
        }
    }

    // Non-suspend primary entry. CiLogger's tee path is non-suspend, and the
    // suspend `add` overload below delegates here so a single place owns the
    // append-and-mask invariant.
    open fun addLine(content: String, stream: String) {
        synchronized(lock) {
            if (closing) return
            val masked = maskSecrets(content)
            buffer.add(
                LogLineInput(
                    lineNumber = ++lineNumber,
                    timestamp = ZonedDateTime.now(),
                    content = masked,
                    stream = stream,
                )
            )
            tail.addLast(TailLine(masked, stream))
            if (tail.size > maxTailLines) tail.removeFirst()
        }
    }

    /**
     * Snapshot of the most recent lines seen by this buffer, regardless
     * of flush state. Used to build the failure summary reported with a
     * step's terminal FAILURE status.
     */
    fun tailLines(): List<TailLine> = synchronized(lock) { tail.toList() }

    open suspend fun add(content: String, stream: String) {
        addLine(content, stream)
    }

    open suspend fun flush() {
        doFlush()
    }

    private suspend fun doFlush() = flushMutex.withLock {
        val snapshot: List<LogLineInput>
        synchronized(lock) {
            if (buffer.isEmpty()) return@withLock
            snapshot = buffer.toList()
        }
        try {
            withRetry { api.appendPipelineLogs(repositoryId, runId, jobId, stepId, snapshot) }
            synchronized(lock) {
                // Drop only the entries we uploaded; lines appended during the
                // network call remain at the tail for the next flush. Safe
                // because flushMutex guarantees no concurrent doFlush has
                // already trimmed the head.
                buffer.subList(0, snapshot.size).clear()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                buffer.subList(0, snapshot.size).clear()
            }
            // Direct stderr emit (not via CiLogger) prevents a tee feedback
            // loop where a dropped-batch warning would re-enter the same
            // buffer and amplify into one warning per flush interval.
            warnDropped(snapshot.size, e)
        }
    }

    private fun warnDropped(lineCount: Int, cause: Throwable) {
        val json = buildJsonObject {
            put("ts", Instant.now().toString())
            put("level", "WARN")
            put("component", "log-buffer")
            put("stepId", stepId.toString())
            put("lines", lineCount)
            put("error", cause.javaClass.simpleName)
            put("errorMessage", cause.message ?: "")
            put("msg", "Dropped log batch after retry exhaustion")
        }
        System.err.println(json)
    }

    open suspend fun close() {
        closing = true
        flushJob?.join()
        doFlush()
        flushScope.cancel()
        flushDispatcher.close()
    }

    private fun maskSecrets(content: String): String {
        var result = content
        for (secret in secretValues) {
            if (secret.isNotEmpty()) {
                result = result.replace(secret, "***")
            }
        }
        return result
    }
}

fun readSharedEnvFile(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .filter { it.contains('=') && !it.startsWith('#') }
        .associate { line ->
            val key = line.substringBefore('=')
            val value = line.substringAfter('=')
            key to value
        }
}

fun hashFilesInDir(dir: File, globs: List<String>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val matchedFiles = mutableListOf<File>()

    for (glob in globs) {
        val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$glob")
        dir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val rel = dir.toPath().relativize(file.toPath())
                matcher.matches(rel)
            }
            .forEach { matchedFiles.add(it) }
    }

    matchedFiles.sortBy { it.absolutePath }

    for (file in matchedFiles) {
        digest.update(file.readBytes())
    }

    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun hashString(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
