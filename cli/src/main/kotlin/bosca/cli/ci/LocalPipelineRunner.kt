package bosca.cli.ci

import java.io.File

class LocalPipelineRunner(
    private val projectDir: File,
    private val serverUrl: String = "",
    private val agentToken: String = "",
    private val registryUrl: String = "",
    private val envOverrides: Map<String, String> = emptyMap(),
    private val secretValues: Map<String, String> = emptyMap(),
) {
    private val yamlParser = PipelineYamlParser()
    private val expressionParser = ExpressionParser()

    companion object {
        private const val SUBMODULE_TIMEOUT_MINUTES = 30L
    }

    fun run(
        pipelineFile: File,
        jobFilter: String? = null,
        refOverride: String? = null,
        echo: (String) -> Unit,
    ): Boolean {
        val definition = yamlParser.parse(pipelineFile.readText())
        echo("\u001b[1m== Pipeline: ${definition.name} ==\u001b[0m")
        if (definition.hasServerOrchestration()) {
            echo(
                "Note: release/promotion triggers, pipeline requirements, environments, and approvals " +
                    "are enforced by the Git server and are not simulated by the local runner."
            )
        }

        val jobsToRun = resolveJobOrder(definition, jobFilter)
        if (jobsToRun.isEmpty()) {
            if (jobFilter != null) {
                echo("Error: job '$jobFilter' not found. Available: ${definition.jobs.keys.joinToString(", ")}")
            } else {
                echo("No jobs to run.")
            }
            return false
        }

        val ref = refOverride ?: detectGitRef()
        val branch = ref.removePrefix("refs/heads/").removePrefix("refs/tags/")
        val commitSha = detectGitCommitSha()
        val cloneUrl = detectGitCloneUrl()
        val repositoryId = ""

        val pipelineEnv = buildMap {
            putAll(definition.env)
            putAll(envOverrides)
        }

        val completedJobs = mutableMapOf<String, String>()
        var pipelineSuccess = true

        for ((jobName, jobDef) in jobsToRun) {
            val depsSkipped = jobDef.needs.any { completedJobs[it] != "success" }
            if (depsSkipped) {
                echo("\n\u001b[33m-- Skipping job: $jobName (dependency failed) --\u001b[0m")
                completedJobs[jobName] = "skipped"
                continue
            }

            if (jobDef.condition != null) {
                val context = ExpressionContext(
                    ref = ref,
                    branch = branch,
                    event = "manual",
                    jobStatus = "success",
                    env = System.getenv().toMap() + pipelineEnv,
                    secrets = secretValues,
                )
                if (!expressionParser.evaluateBoolean(jobDef.condition, context)) {
                    echo("\n\u001b[33m-- Skipping job: $jobName (condition not met) --\u001b[0m")
                    completedJobs[jobName] = "skipped"
                    continue
                }
            }

            echo("\n\u001b[1m-- Job: $jobName --\u001b[0m")
            val jobResult = runJob(
                jobName, jobDef, ref, branch, commitSha, cloneUrl, repositoryId, pipelineEnv, echo,
            )
            completedJobs[jobName] = if (jobResult) "success" else "failure"
            if (!jobResult) pipelineSuccess = false
        }

        echo("")
        if (pipelineSuccess) {
            echo("\u001b[32m== Pipeline completed successfully ==\u001b[0m")
        } else {
            echo("\u001b[31m== Pipeline failed ==\u001b[0m")
        }
        return pipelineSuccess
    }

    private fun runJob(
        jobName: String,
        jobDef: JobDefinition,
        ref: String,
        branch: String,
        commitSha: String,
        cloneUrl: String,
        repositoryId: String,
        pipelineEnv: Map<String, String>,
        echo: (String) -> Unit,
    ): Boolean {
        val jobDir = File(projectDir, ".bosca/local-run/$jobName")
        jobDir.mkdirs()
        val sharedEnvFile = File(jobDir, ".bosca_env").also { it.createNewFile() }
        val sharedPathFile = File(jobDir, ".bosca_path").also { it.createNewFile() }

        var jobStatus = "success"
        val timeoutMinutes = jobDef.timeoutMinutes ?: 60

        try {
            for (step in jobDef.steps) {
                val envFromFile = readSharedEnvFile(sharedEnvFile)
                val context = ExpressionContext(
                    ref = ref,
                    branch = branch,
                    event = "manual",
                    jobStatus = jobStatus,
                    env = System.getenv().toMap() + pipelineEnv + envFromFile + step.env,
                    secrets = secretValues,
                    fileHasher = { globs -> hashFilesInDir(projectDir, globs) },
                )

                if (step.condition != null) {
                    val shouldRun = expressionParser.evaluateBoolean(step.condition, context)
                    if (!shouldRun) {
                        echo("\u001b[33m   ⊘ ${step.name} (skipped)\u001b[0m")
                        continue
                    }
                } else if (jobStatus != "success") {
                    echo("\u001b[33m   ⊘ ${step.name} (skipped)\u001b[0m")
                    continue
                }

                if (step.uses == "checkout") {
                    val submodulesRaw = step.with["submodules"]
                    val submodules = submodulesRaw?.lowercase()?.trim()
                    val truthy = submodules in setOf("true", "recursive", "yes", "1")
                    val falsy = submodules.isNullOrEmpty() || submodules in setOf("false", "no", "0")
                    if (!truthy && !falsy) {
                        echo("[33m     Warning: unknown submodules value '$submodulesRaw', expected one of: true|recursive|false — treating as false[0m")
                    }
                    if (truthy) {
                        val args = mutableListOf("git", "submodule", "update", "--init")
                        if (submodules == "recursive") args += "--recursive"
                        val proc = ProcessBuilder(args)
                            .directory(projectDir)
                            .redirectErrorStream(true)
                            .start()
                        proc.inputStream.bufferedReader().useLines { lines -> lines.forEach { echo("     $it") } }
                        if (!proc.waitFor(SUBMODULE_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES)) {
                            proc.destroyForcibly()
                            echo("[31m   ✗ ${step.name} (submodule update timed out after $SUBMODULE_TIMEOUT_MINUTES min)[0m")
                            jobStatus = "failure"
                            continue
                        }
                        if (proc.exitValue() != 0) {
                            jobStatus = "failure"
                            continue
                        }
                    }
                    echo("\u001b[36m   ✓ ${step.name} (local — already in working directory)\u001b[0m")
                    continue
                }

                echo("\u001b[36m   ▶ ${step.name}\u001b[0m")
                val startTime = System.currentTimeMillis()

                val logBuffer = ConsoleLogBuffer(step.name, secretValues.values.toSet())

                val stepWorkDir = if (step.workingDirectory != null) {
                    val resolved = File(projectDir, step.workingDirectory)
                    resolved.mkdirs()
                    resolved
                } else projectDir

                val stepEnv = step.env.mapValues { (_, v) -> expressionParser.interpolate(v, context) }

                val executor = StepExecutor(
                    workDir = stepWorkDir,
                    serverUrl = serverUrl,
                    agentToken = agentToken,
                    registryUrl = registryUrl,
                    commitSha = commitSha,
                    ref = ref,
                    repositoryId = repositoryId,
                    cloneUrl = cloneUrl,
                    pipelineRunId = "",
                    env = pipelineEnv + stepEnv,
                    secrets = secretValues,
                    logBuffer = logBuffer,
                    timeoutMinutes = timeoutMinutes,
                    sharedEnvFile = sharedEnvFile,
                    sharedPathFile = sharedPathFile,
                )

                val result = kotlinx.coroutines.runBlocking {
                    executor.execute(step, context)
                }

                val durationMs = System.currentTimeMillis() - startTime
                val durationStr = formatDuration(durationMs)

                if (result.success) {
                    echo("\u001b[32m   ✓ ${step.name} ($durationStr)\u001b[0m")
                } else {
                    echo("\u001b[31m   ✗ ${step.name} (exit ${result.exitCode}, $durationStr)\u001b[0m")
                    jobStatus = "failure"
                }
            }
        } finally {
            jobDir.deleteRecursively()
        }

        return jobStatus == "success"
    }

    private fun resolveJobOrder(
        definition: PipelineDefinition,
        jobFilter: String?,
    ): List<Pair<String, JobDefinition>> {
        if (jobFilter != null) {
            val job = definition.jobs[jobFilter] ?: return emptyList()
            val ordered = mutableListOf<Pair<String, JobDefinition>>()
            val visited = mutableSetOf<String>()
            collectDeps(jobFilter, definition.jobs, ordered, visited)
            return ordered
        }

        val ordered = mutableListOf<Pair<String, JobDefinition>>()
        val visited = mutableSetOf<String>()
        for (name in definition.jobs.keys) {
            collectDeps(name, definition.jobs, ordered, visited)
        }
        return ordered
    }

    private fun collectDeps(
        name: String,
        jobs: Map<String, JobDefinition>,
        ordered: MutableList<Pair<String, JobDefinition>>,
        visited: MutableSet<String>,
    ) {
        if (name in visited) return
        visited.add(name)
        val job = jobs[name] ?: return
        for (dep in job.needs) {
            collectDeps(dep, jobs, ordered, visited)
        }
        ordered.add(name to job)
    }

    private fun detectGitRef(): String {
        return try {
            val process = ProcessBuilder("git", "symbolic-ref", "HEAD")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotEmpty()) output else "refs/heads/main"
        } catch (_: Exception) {
            "refs/heads/main"
        }
    }

    private fun detectGitCommitSha(): String {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotEmpty()) output else "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun detectGitCloneUrl(): String {
        return try {
            val process = ProcessBuilder("git", "remote", "get-url", "origin")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotEmpty()) output else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> "%.1fs".format(ms / 1000.0)
            else -> "%dm %ds".format(ms / 60_000, (ms % 60_000) / 1000)
        }
    }
}
