package bosca.cli.ci

import bosca.graphql.gen.GitReleaseAppStoreReviewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class StepExecutor(
    private val workDir: File,
    private val serverUrl: String,
    private val agentToken: String,
    private val registryUrl: String = "",
    private val commitSha: String,
    private val ref: String,
    private val repositoryId: String,
    private val cloneUrl: String = "",
    private val pipelineRunId: String = "",
    // Server-calling actions (uses: tag) go through the typed API with the job's id;
    // both default off so shell-only tests need no wiring.
    private val api: CiApi? = null,
    private val jobId: kotlin.uuid.Uuid? = null,
    private val env: Map<String, String>,
    private val secrets: Map<String, String>,
    private val logBuffer: LogBuffer,
    private val timeoutMinutes: Int = 60,
    private val sharedEnvFile: File = File(workDir, ".bosca_env"),
    private val sharedPathFile: File = File(workDir, ".bosca_path"),
    // How long to wait for a reader thread to reach EOF after the process tree
    // has been torn down. Surfaced as a parameter so tests can shorten it.
    private val drainTimeoutMs: Long = 5000,
    // Cancellation signal (run control): checked while a shell/docker process runs so a
    // cancelled job stops its in-flight step instead of letting it run to completion. The runner
    // flips this from its cancel watcher; the default never cancels.
    private val cancelled: () -> Boolean = { false },
    // How often the running process checks the cancellation signal. The signal itself is a cheap
    // atomic read (the network poll happens in the runner's watcher), so this can be tight.
    private val cancelPollMs: Long = 1000,
    /** Narrow test seam for the Transporter shell invocation; all preparation and cleanup remain real. */
    private val actionShellExecutor: (suspend (String, Map<String, String>) -> StepResult)? = null,
    private val operatingSystem: String = System.getProperty("os.name"),
) {
    private val expressionParser = ExpressionParser()

    suspend fun execute(step: StepDefinition, context: ExpressionContext): StepResult {
        val resolvedEnv = buildStepEnv(step, context)

        return when {
            step.uses != null -> executeAction(step.uses, step.with, resolvedEnv, context)
            step.run != null -> {
                val command = expressionParser.interpolate(step.run, context)
                if (step.image != null) {
                    executeDockerRun(step.image, command, resolvedEnv)
                } else {
                    executeShellCommand(command, resolvedEnv)
                }
            }
            else -> {
                logBuffer.add("Error: step has neither 'uses' nor 'run'", "stderr")
                StepResult(false, 1)
            }
        }
    }

    private suspend fun executeAction(
        action: String,
        with: Map<String, String>,
        env: Map<String, String>,
        context: ExpressionContext,
    ): StepResult {
        val resolvedWith = with.mapValues { (_, v) -> expressionParser.interpolate(v, context) }

        return when (action) {
            "checkout" -> executeCheckout(resolvedWith)
            "cache" -> executeCache(resolvedWith)
            "upload-artifact" -> executeUploadArtifact(resolvedWith)
            "download-artifact" -> executeDownloadArtifact(resolvedWith)
            "setup-registry" -> executeSetupRegistry(resolvedWith)
            "setup-java" -> executeSetupJava(resolvedWith)
            "setup-node" -> executeSetupNode(resolvedWith)
            "setup-pnpm" -> executeSetupPnpm(resolvedWith)
            "setup-python" -> executeSetupPython(resolvedWith)
            "setup-go" -> executeSetupGo(resolvedWith)
            "setup-rust" -> executeSetupRust(resolvedWith)
            "setup-gradle" -> executeSetupGradle(resolvedWith)
            "setup-k6" -> executeSetupK6(resolvedWith)
            "git-push" -> executeGitPush(resolvedWith)
            "git-push-submodules" -> executeGitPushSubmodules(resolvedWith)
            "git-pull-submodules" -> executeGitPullSubmodules(resolvedWith)
            "git-commit-submodules" -> executeGitCommitSubmodules(resolvedWith, context)
            "git-update-workspace-refs" -> executeGitUpdateWorkspaceRefs(resolvedWith, context)
            "docker-build" -> executeDockerBuild(resolvedWith, env)
            "registry-upload" -> executeRegistryUpload(resolvedWith)
            "notify" -> executeNotify(resolvedWith, env)
            "tag" -> executeTag(resolvedWith)
            "allocate-build-number" -> executeAllocateBuildNumber(resolvedWith)
            "deploy" -> executeDeploy(resolvedWith)
            "rollback" -> executeRollback(resolvedWith)
            "play-rollout" -> executePlayRollout(resolvedWith)
            "app-store-upload" -> executeAppStoreUpload(resolvedWith)
            "app-store-review" -> executeAppStoreReview(resolvedWith)
            "verify-store-health" -> executeVerifyStoreHealth(resolvedWith)
            "verify-deployment" -> executeVerifyDeployment(resolvedWith)
            "generate-release-notes" -> executeGenerateReleaseNotes()
            "mark-released" -> executeMarkReleased()
            else -> {
                logBuffer.add("Unknown action: $action", "stderr")
                StepResult(false, 1)
            }
        }
    }

    /**
     * `uses: tag`: creates a release tag in another repository through the server —
     * the server enforces the run's initiating principal and idempotency (same commit no-ops,
     * a different commit fails), and the runs the tag triggers inherit that principal. The tag
     * name defaults server-side to the run's `release.version` parameter.
     */
    private suspend fun executeTag(with: Map<String, String>): StepResult {
        val repository = with["repository"]
        if (repository.isNullOrBlank()) {
            logBuffer.add("Error: tag requires 'repository' (owner/slug or a sibling slug)", "stderr")
            return StepResult(false, 1)
        }
        val api = this.api
        val jobId = this.jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: tag requires the server API — not available in this runner", "stderr")
            return StepResult(false, 1)
        }
        return try {
            val tagged = api.createReleaseTag(jobId, repository, with["tag"], with["message"])
            logBuffer.add(
                if (tagged.created) "Created tag ${tagged.tag} at ${tagged.commitSha} in $repository"
                else "Tag ${tagged.tag} already at ${tagged.commitSha} in $repository — no-op",
                "stdout",
            )
            StepResult(true, 0)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: tag failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    /**
     * Allocates a store-safe build identity and exports it for all subsequent steps in this job.
     * The server keys idempotency to source commit + version + variant, so retries never silently
     * produce a differently numbered binary.
     */
    private suspend fun executeAllocateBuildNumber(with: Map<String, String>): StepResult {
        val platform = with["platform"]?.let { it.trim().lowercase() }
        if (platform != "android" && platform != "ios") {
            logBuffer.add("Error: allocate-build-number requires platform 'android' or 'ios'", "stderr")
            return StepResult(false, 1)
        }
        val applicationId = with["applicationId"]
        if (applicationId.isNullOrBlank()) {
            logBuffer.add("Error: allocate-build-number requires 'applicationId'", "stderr")
            return StepResult(false, 1)
        }
        val sourceVersion = with["version"]
        if (sourceVersion.isNullOrBlank()) {
            logBuffer.add("Error: allocate-build-number requires 'version'", "stderr")
            return StepResult(false, 1)
        }
        val api = this.api
        val jobId = this.jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: allocate-build-number requires the server API — not available in this runner", "stderr")
            return StepResult(false, 1)
        }
        return try {
            val allocation = api.allocateBuildNumberFromJob(
                jobId = jobId,
                platform = platform,
                applicationId = applicationId,
                sourceVersion = sourceVersion,
                buildKey = with["key"],
                minimum = with["minimum"],
            )
            val exportedName = if (platform == "android") "ANDROID_VERSION_CODE" else "IOS_BUILD_NUMBER"
            sharedEnvFile.appendText(
                buildString {
                    append("APP_BUILD_NUMBER=").append(allocation.value).append('\n')
                    append(exportedName).append('=').append(allocation.value).append('\n')
                },
            )
            logBuffer.add(
                "${if (allocation.reused) "Reused" else "Allocated"} $exportedName=${allocation.value} " +
                    "for $applicationId@$sourceVersion",
                "stdout",
            )
            StepResult(true, 0)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: allocate-build-number failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    /**
     * `uses: deploy`: deploys an environment through the server — deploy.yaml target
     * selection, artifact routing, environment permission (evaluated against the run's initiating
     * principal), adapter invocation, and EnvironmentDeployment recording all happen server-side.
     * `with:` keys beyond environment/repository/target pass through as config-key overrides
     * (already interpolated), so `phasedRelease: ${'$'}{{ inputs.phasedRelease }}` works.
     */
    private suspend fun executeDeploy(with: Map<String, String>): StepResult {
        val environment = with["environment"]
        if (environment.isNullOrBlank()) {
            logBuffer.add("Error: deploy requires 'environment' (the environment key)", "stderr")
            return StepResult(false, 1)
        }
        val api = this.api
        val jobId = this.jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: deploy requires the server API — not available in this runner", "stderr")
            return StepResult(false, 1)
        }
        val overrides = with.filterKeys { it !in DEPLOY_RESERVED_KEYS }
        return try {
            val outcome = api.deployFromJob(
                jobId,
                environment,
                with["repository"],
                with["target"],
                overrides.takeIf { it.isNotEmpty() }?.let { map ->
                    kotlinx.serialization.json.JsonObject(
                        map.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) }
                    )
                },
            )
            logBuffer.add("Deployed to '$environment' — ${outcome.reference} (${outcome.status})", "stdout")
            StepResult(true, 0)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: deploy failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    /**
     * `uses: rollback`: target-honest rollback through the server — helm restores the
     * revision AND reverts the ops-repo values; environment permission evaluated against the run's
     * initiating principal. `toRevision` 0 (the default) means the previous revision.
     */
    private suspend fun executeRollback(with: Map<String, String>): StepResult {
        val environment = with["environment"]
        if (environment.isNullOrBlank()) {
            logBuffer.add("Error: rollback requires 'environment' (the environment key)", "stderr")
            return StepResult(false, 1)
        }
        val api = this.api
        val jobId = this.jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: rollback requires the server API — not available in this runner", "stderr")
            return StepResult(false, 1)
        }
        val toRevision = with["toRevision"]?.let { value ->
            value.toIntOrNull()?.takeIf { it >= 0 } ?: run {
                logBuffer.add("Error: rollback 'toRevision' must be a non-negative integer, got '$value'", "stderr")
                return StepResult(false, 1)
            }
        }
        val overrides = with.filterKeys { it !in ROLLBACK_RESERVED_KEYS }
        return try {
            val outcome = api.rollbackFromJob(
                jobId,
                environment,
                with["repository"]?.takeIf { it.isNotBlank() },
                with["target"]?.takeIf { it.isNotBlank() },
                toRevision,
                overrides.takeIf { it.isNotEmpty() }?.let { map ->
                    kotlinx.serialization.json.JsonObject(
                        map.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) }
                    )
                },
            )
            logBuffer.add("Rolled back '$environment' — ${outcome.reference} (${outcome.status})", "stdout")
            StepResult(true, 0)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: rollback failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    /**
     * `uses: play-rollout`: advances the existing Google Play release on an
     * environment's target without uploading the bundle again. YAML uses the store-neutral
     * `rolloutPercentage` 0–100 vocabulary; the server maps it to Play's userFraction.
     */
    private suspend fun executePlayRollout(with: Map<String, String>): StepResult {
        val environment = with["environment"]
        if (environment.isNullOrBlank()) {
            logBuffer.add("Error: play-rollout requires 'environment' (the environment key)", "stderr")
            return StepResult(false, 1)
        }
        val percentageText = with["rolloutPercentage"]
        val rolloutPercentage = percentageText?.toDoubleOrNull()
        if (rolloutPercentage == null || !rolloutPercentage.isFinite() || rolloutPercentage !in 0.0..100.0) {
            logBuffer.add(
                "Error: play-rollout requires 'rolloutPercentage' between 0 and 100, got '${percentageText ?: ""}'",
                "stderr",
            )
            return StepResult(false, 1)
        }
        val api = this.api
        val jobId = this.jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: play-rollout requires the server API — not available in this runner", "stderr")
            return StepResult(false, 1)
        }
        return try {
            val outcome = api.playRolloutFromJob(
                jobId,
                environment,
                rolloutPercentage,
                with["repository"],
                with["target"],
            )
            logBuffer.add(
                "Advanced '$environment' Play rollout to ${displayPercentage(rolloutPercentage)}% — " +
                    "${outcome.reference} (${outcome.status})",
                "stdout",
            )
            StepResult(true, 0)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: play-rollout failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    private fun displayPercentage(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    /** Fetches the registry IPA and uploads it with Apple's Transporter on a macOS runner. */
    private suspend fun executeAppStoreUpload(with: Map<String, String>): StepResult {
        if (!operatingSystem.contains("mac", ignoreCase = true)) {
            logBuffer.add("Error: app-store-upload requires a macOS runner with Xcode Transporter", "stderr")
            return StepResult(false, 1)
        }
        val registry = registryUrl.ifEmpty {
            logBuffer.add("Error: app-store-upload requires a configured registry URL", "stderr")
            return StepResult(false, 1)
        }
        val required = listOf("namespace", "name", "version", "filename", "credentialSecret")
        val values = required.associateWith { key -> with[key]?.trim().orEmpty() }
        values.entries.firstOrNull { it.value.isBlank() }?.let {
            logBuffer.add("Error: app-store-upload requires '${it.key}'", "stderr")
            return StepResult(false, 1)
        }
        val filename = values.getValue("filename")
        if (!filename.endsWith(".ipa", ignoreCase = true)) {
            logBuffer.add("Error: app-store-upload filename must end in .ipa", "stderr")
            return StepResult(false, 1)
        }
        if (values.filterKeys { it != "credentialSecret" }.values.any { !isSafePathSegment(it) }) {
            logBuffer.add("Error: app-store-upload registry coordinates contain an unsafe path segment", "stderr")
            return StepResult(false, 1)
        }
        val secretName = values.getValue("credentialSecret")
        val secret = secrets[secretName]
        if (secret == null) {
            logBuffer.add("Error: app-store-upload CI secret '$secretName' is not available", "stderr")
            return StepResult(false, 1)
        }
        val credential = try {
            val objectValue = Json.parseToJsonElement(secret).jsonObject
            val issuerId = objectValue["issuerId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val keyId = objectValue["keyId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val privateKey = objectValue["privateKey"]?.jsonPrimitive?.contentOrNull
                ?: objectValue["p8"]?.jsonPrimitive?.contentOrNull
            require(ASC_ISSUER_ID.matches(issuerId)) { "issuerId is invalid" }
            require(ASC_KEY_ID.matches(keyId)) { "keyId is invalid" }
            require(!privateKey.isNullOrBlank()) { "privateKey (or p8) is missing" }
            Triple(issuerId, keyId, privateKey)
        } catch (e: Exception) {
            logBuffer.add("Error: app-store-upload secret '$secretName' is invalid: ${e.message}", "stderr")
            return StepResult(false, 1)
        }
        val tempDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory(workDir.toPath(), ".bosca-app-store-").toFile()
        }
        return try {
            val ipa = File(tempDir, filename)
            val url = "${registryBaseUrl(registry)}/raw/${values.getValue("namespace")}/" +
                "${values.getValue("name")}/${values.getValue("version")}/$filename"
            downloadRegistryFile(url, ipa)
            val keyDirectory = File(tempDir, "private_keys").apply { mkdirs() }
            val keyFile = File(keyDirectory, "AuthKey_${credential.second}.p8")
            withContext(Dispatchers.IO) {
                keyFile.writeText(credential.third)
                runCatching {
                    Files.setPosixFilePermissions(
                        keyFile.toPath(),
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    )
                }
            }
            val command = "cd ${shellQuote(tempDir.absolutePath)} && " +
                "xcrun iTMSTransporter -m upload -assetFile ${shellQuote(ipa.absolutePath)} " +
                "-apiKey ${shellQuote(credential.second)} -apiIssuer ${shellQuote(credential.first)}"
            logBuffer.add(
                "Uploading $filename from ${values.getValue("namespace")}/${values.getValue("name")}:" +
                    values.getValue("version") + " to App Store Connect",
                "stdout",
            )
            actionShellExecutor?.invoke(command, emptyMap()) ?: executeShellCommand(command, emptyMap())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: app-store-upload failed: ${e.message}", "stderr")
            StepResult(false, 1)
        } finally {
            withContext(Dispatchers.IO) { tempDir.deleteRecursively() }
        }
    }

    private suspend fun downloadRegistryFile(url: String, target: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Authorization", Credentials.basic("api_token", agentToken)).get().build()
        OkHttpClient().newCall(request).execute().use { response ->
            check(response.isSuccessful) { "registry returned HTTP ${response.code}" }
            target.outputStream().use { output -> response.body.byteStream().use { it.copyTo(output) } }
        }
    }

    /** Polls the selected build's typed beta/full-review state until approval, rejection, or timeout. */
    private suspend fun executeAppStoreReview(with: Map<String, String>): StepResult {
        val environment = with["environment"]?.takeIf { it.isNotBlank() } ?: run {
            logBuffer.add("Error: app-store-review requires 'environment'", "stderr")
            return StepResult(false, 1)
        }
        val mode = when (with["mode"]?.trim()?.lowercase()) {
            "beta" -> GitReleaseAppStoreReviewMode.BETA
            "appstore" -> GitReleaseAppStoreReviewMode.APP_STORE
            else -> {
                logBuffer.add("Error: app-store-review mode must be 'beta' or 'appstore'", "stderr")
                return StepResult(false, 1)
            }
        }
        val api = api
        val jobId = jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: app-store-review requires the server API", "stderr")
            return StepResult(false, 1)
        }
        val timeoutSeconds = positiveSeconds(with, "timeoutSeconds", 3600L, "app-store-review")
            ?: return StepResult(false, 1)
        val intervalSeconds = positiveSeconds(with, "intervalSeconds", 30L, "app-store-review")
            ?: return StepResult(false, 1)
        var lastState = "UNKNOWN"
        return try {
            kotlinx.coroutines.withTimeoutOrNull<StepResult>(timeoutSeconds.seconds) {
                while (true) {
                    if (cancelled()) return@withTimeoutOrNull StepResult(false, 130, cancelled = true)
                    val outcome = api.appStoreReviewFromJob(
                        jobId, environment, mode, with["repository"], with["target"],
                    )
                    lastState = outcome.state
                    if (outcome.complete) {
                        if (outcome.approved) {
                            logBuffer.add("App Store review approved ($lastState)", "stdout")
                            return@withTimeoutOrNull StepResult(true, 0)
                        }
                        logBuffer.add("Error: App Store review ended in $lastState", "stderr")
                        return@withTimeoutOrNull StepResult(false, 1)
                    }
                    logBuffer.add("App Store review is $lastState — waiting", "stdout")
                    kotlinx.coroutines.delay(intervalSeconds.seconds)
                }
                @Suppress("UNREACHABLE_CODE")
                StepResult(false, 1)
            } ?: run {
                logBuffer.add("Error: App Store review timed out after ${timeoutSeconds}s (last: $lastState)", "stderr")
                StepResult(false, 1)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: app-store-review failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    /** Executes one point-in-time Play-vitals gate. */
    private suspend fun executeVerifyStoreHealth(with: Map<String, String>): StepResult {
        val environment = with["environment"]?.takeIf { it.isNotBlank() } ?: run {
            logBuffer.add("Error: verify-store-health requires 'environment'", "stderr")
            return StepResult(false, 1)
        }
        val maxCrashRate = with["maxCrashRate"]?.toDoubleOrNull()
        if (maxCrashRate == null || !maxCrashRate.isFinite() || maxCrashRate !in 0.0..100.0) {
            logBuffer.add("Error: verify-store-health requires 'maxCrashRate' between 0 and 100", "stderr")
            return StepResult(false, 1)
        }
        val windowSeconds = parseWindowSeconds(with["window"] ?: "24h") ?: run {
            logBuffer.add("Error: verify-store-health window must be a positive duration such as 30m, 24h, or 7d", "stderr")
            return StepResult(false, 1)
        }
        val api = api
        val jobId = jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: verify-store-health requires the server API", "stderr")
            return StepResult(false, 1)
        }
        return try {
            val outcome = api.storeHealthFromJob(
                jobId, environment, maxCrashRate, windowSeconds, with["repository"], with["target"],
            )
            val message = "Play crash rate ${outcome.crashRate}% (maximum ${outcome.maxCrashRate}%)"
            if (outcome.healthy) {
                logBuffer.add("Store health passed: $message", "stdout")
                StepResult(true, 0)
            } else {
                logBuffer.add("Error: store health failed: $message", "stderr")
                StepResult(false, 1)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: verify-store-health failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    private fun parseWindowSeconds(value: String): Long? {
        val match = WINDOW.matchEntire(value.trim()) ?: return null
        val amount = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val multiplier = when (match.groupValues[2]) {
            "s" -> 1L
            "m" -> 60L
            "h" -> 3600L
            "d" -> 86400L
            else -> return null
        }
        return runCatching { Math.multiplyExact(amount, multiplier) }.getOrNull()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun isSafePathSegment(value: String): Boolean =
        value != "." && value != ".." && SAFE_PATH_SEGMENT.matches(value)

    /**
     * `uses: verify-deployment`: polls the server's health observation for the
     * environment until HEALTHY or `timeoutSeconds` (default 600) — each poll probes through the
     * same path the wait-healthy job uses, so gate and job observe identically. Cancellation stops
     * the wait between polls.
     */
    private suspend fun executeVerifyDeployment(with: Map<String, String>): StepResult {
        val environment = with["environment"]
        if (environment.isNullOrBlank()) {
            logBuffer.add("Error: verify-deployment requires 'environment' (the environment key)", "stderr")
            return StepResult(false, 1)
        }
        val api = this.api
        val jobId = this.jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: verify-deployment requires the server API — not available in this runner", "stderr")
            return StepResult(false, 1)
        }
        val timeoutSeconds = positiveSeconds(with, "timeoutSeconds", 600L, "verify-deployment")
            ?: return StepResult(false, 1)
        val intervalSeconds = positiveSeconds(with, "intervalSeconds", 15L, "verify-deployment")
            ?: return StepResult(false, 1)
        var lastStatus = "UNKNOWN"
        return try {
            val result = kotlinx.coroutines.withTimeoutOrNull<StepResult>(timeoutSeconds.seconds) {
                var outcome: StepResult? = null
                while (outcome == null) {
                    if (cancelled()) {
                        logBuffer.add("verify-deployment cancelled while waiting on '$environment'", "stderr")
                        outcome = StepResult(false, 130, cancelled = true)
                    } else {
                        lastStatus = api.jobDeploymentHealth(
                            jobId,
                            environment,
                            with["repository"]?.takeIf { it.isNotBlank() },
                        )
                        if (lastStatus == "HEALTHY") {
                            logBuffer.add("Deployment to '$environment' is healthy", "stdout")
                            outcome = StepResult(true, 0)
                        } else {
                            logBuffer.add("Deployment to '$environment' is $lastStatus — waiting", "stdout")
                            kotlinx.coroutines.delay(intervalSeconds.seconds)
                        }
                    }
                }
                outcome
            }
            result ?: run {
                logBuffer.add(
                    "Error: deployment to '$environment' was not healthy within ${timeoutSeconds}s (last: $lastStatus)",
                    "stderr",
                )
                StepResult(false, 1)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: verify-deployment failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    /** `uses: mark-released`: stamps the run's workops release released — idempotent. */
    private suspend fun executeMarkReleased(): StepResult {
        val api = this.api
        val jobId = this.jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: mark-released requires the server API — not available in this runner", "stderr")
            return StepResult(false, 1)
        }
        return try {
            check(api.markReleasedFromJob(jobId)) { "server did not mark the release released" }
            logBuffer.add("Release marked released", "stdout")
            StepResult(true, 0)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: mark-released failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    /** `uses: generate-release-notes`: generates localized Version drafts after tagging. */
    private suspend fun executeGenerateReleaseNotes(): StepResult {
        val api = this.api
        val jobId = this.jobId
        if (api == null || jobId == null) {
            logBuffer.add("Error: generate-release-notes requires the server API — not available in this runner", "stderr")
            return StepResult(false, 1)
        }
        return try {
            check(api.generateReleaseNotesFromJob(jobId)) { "server did not generate release notes" }
            logBuffer.add("Localized version release notes generated", "stdout")
            StepResult(true, 0)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logBuffer.add("Error: generate-release-notes failed: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    private suspend fun executeCheckout(with: Map<String, String>): StepResult {
        if (cloneUrl.isEmpty()) {
            logBuffer.add("Error: clone URL not available — could not resolve repository slug", "stderr")
            return StepResult(false, 1)
        }

        val depth = with["depth"]?.toIntOrNull()
        val depthArg = if (depth != null) "--depth $depth " else ""

        val submoduleCmd = when (with["submodules"]?.lowercase()?.trim()) {
            "recursive", "true", "yes", "1" -> {
                val recursive = with["submodules"]?.lowercase()?.trim() == "recursive"
                " && git submodule update --init${if (recursive) " --recursive" else ""}"
            }
            null, "", "false", "no", "0" -> ""
            else -> {
                logBuffer.add(
                    "Warning: unknown submodules value '${with["submodules"]}', expected one of: true|recursive|false — treating as false",
                    "stderr",
                )
                ""
            }
        }

        val commands = "git clone ${depthArg}$cloneUrl . && git checkout $commitSha$submoduleCmd"

        return executeShellCommand(commands, authHeaderConfigEnv())
    }

    /**
     * Pins release versions inside Helm values files — WORKING TREE ONLY. It stages nothing and never
     * touches git: a later `registry-upload` publishes the pinned files as values artifacts. (In the
     * release-relay flow the tag's tree is already pinned by the Tag node, making this an idempotent
     * no-op there.) A promised path missing from a file fails the step.
     *
     * with:
     *   files:   values file paths, repo-relative (list or comma-separated)   — required
     *   version: the version to pin                                            — required
     *   paths:   dot-paths to set (default `image.tag`)
     */
    /**
     * Publishes files to the Bosca artifacts registry as a RAW artifact — e.g. pinned Helm values
     * files as the release's values artifacts, so deploys consume values by registry coordinate like
     * every other artifact. Each file lands at `PUT /raw/{namespace}/api/{name}/{version}/{filename}`.
     *
     * with:
     *   namespace: registry namespace (e.g. `bosca-values`)                    — required
     *   name:      artifact name (e.g. the project key)                        — required
     *   version:   artifact version                                            — required
     *   files:     files to publish, repo-relative (list or comma-separated)   — required
     */
    /** The media type recorded on an uploaded registry blob, by file extension — curl would otherwise
     *  claim `application/x-www-form-urlencoded` for every `--data-binary` PUT. */
    private fun mediaTypeOf(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "yaml", "yml" -> "text/yaml"
        "json" -> "application/json"
        "txt", "md" -> "text/plain"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    private suspend fun executeRegistryUpload(with: Map<String, String>): StepResult {
        val registry = registryUrl.ifEmpty {
            logBuffer.add("Error: no registry URL configured in agent config", "stderr")
            return StepResult(false, 1)
        }
        val namespace = with["namespace"]?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            logBuffer.add("Error: registry-upload requires 'namespace'", "stderr")
            return StepResult(false, 1)
        }
        val name = with["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            logBuffer.add("Error: registry-upload requires 'name'", "stderr")
            return StepResult(false, 1)
        }
        val version = with["version"]?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            logBuffer.add("Error: registry-upload requires 'version'", "stderr")
            return StepResult(false, 1)
        }
        val files = parseListValue(with["files"]) ?: run {
            logBuffer.add("Error: registry-upload requires 'files'", "stderr")
            return StepResult(false, 1)
        }

        for (relative in files) {
            val file = File(workDir, relative)
            if (!file.exists()) {
                logBuffer.add("Error: file not found: $relative", "stderr")
                return StepResult(false, 1)
            }
            val url = "${registryBaseUrl(registry)}/raw/$namespace/api/$name/$version/${file.name}"
            logBuffer.add("Publishing $relative → $namespace/$name:$version/${file.name}", "stdout")
            // The token travels via env, not the command line, so it never appears in step logs.
            val cmd = "curl -sS -f -u \"api_token:\$BOSCA_REGISTRY_TOKEN\" -H 'Content-Type: ${mediaTypeOf(file.name)}' -X PUT --data-binary @'${file.absolutePath}' '$url'"
            val result = executeShellCommand(cmd, mapOf("BOSCA_REGISTRY_TOKEN" to agentToken))
            if (!result.success) {
                logBuffer.add("Error: upload of $relative failed", "stderr")
                return result
            }
        }
        return StepResult(true, 0)
    }

    private suspend fun executeGitPush(with: Map<String, String>): StepResult {
        val remote = with["remote"] ?: "origin"
        val branch = with["branch"] ?: "main"
        logBuffer.add("Pushing $branch to $remote", "stdout")
        return executeShellCommand("git push $remote $branch", authHeaderConfigEnv())
    }

    private suspend fun executeGitPushSubmodules(with: Map<String, String>): StepResult {
        val remote = with["remote"] ?: "origin"
        val branch = with["branch"] ?: "main"
        logBuffer.add("Pushing submodules to $remote/$branch", "stdout")
        val cmd = """
            git submodule foreach --quiet '
              echo "==> ${'$'}name: pushing"
              git push $remote $branch
            '
        """.trimIndent()
        return executeShellCommand(cmd, authHeaderConfigEnv())
    }

    /**
     * The authenticated counterpart to [executeGitPushSubmodules]: fast-forwards every submodule
     * onto the latest `$remote/$branch`. Run *after* the submodule commits are pushed and *before*
     * the workspace refs are committed, so the superproject anchors each submodule at the newest
     * commit on its branch — including any that landed from outside this release window. As a
     * `uses:` action it receives [authHeaderConfigEnv]; the equivalent raw `run:` script cannot,
     * which is why an unauthenticated `git pull` here fails against the private server.
     */
    private suspend fun executeGitPullSubmodules(with: Map<String, String>): StepResult {
        val remote = with["remote"] ?: "origin"
        val branch = with["branch"] ?: "main"
        logBuffer.add("Pulling submodules from $remote/$branch", "stdout")
        // `--ff-only`: fast-forward each submodule's `$branch` to the latest `$remote/$branch`, and
        // fail the release if that isn't possible. A non-fast-forward here means the submodule's
        // history diverged from what we just pushed — merging it would manufacture a *local* merge
        // commit that was never pushed to the submodule remote, so anchoring the workspace at it
        // would leave a ref no fresh `git submodule update` could fetch. Better to abort loudly than
        // pin an unresolvable commit; the normal release flow is always a clean fast-forward.
        val cmd = """
            git submodule foreach --quiet '
              echo "==> ${'$'}name: pulling"
              git checkout $branch
              git pull --ff-only $remote $branch
            '
        """.trimIndent()
        return executeShellCommand(cmd, authHeaderConfigEnv())
    }

    private suspend fun executeGitCommitSubmodules(with: Map<String, String>, context: ExpressionContext): StepResult {
        val message = with["message"] ?: "Improvements"
        val remote = with["remote"] ?: "origin"
        val branch = with["branch"] ?: "main"
        logBuffer.add("Committing submodule changes: $message", "stdout")
        val cmd = """
            git submodule foreach --quiet '
              git fetch $remote $branch
              git checkout $branch
              git merge $remote/$branch --ff-only
              if [ -n "${'$'}(git status --porcelain)" ]; then
                echo "==> ${'$'}name: committing"
                git add -A
                git commit -m "$message"
              else
                echo "==> ${'$'}name: clean, skipping"
              fi
            '
        """.trimIndent()
        return executeShellCommand(cmd, authHeaderConfigEnv())
    }

    private suspend fun executeGitUpdateWorkspaceRefs(with: Map<String, String>, context: ExpressionContext): StepResult {
        val remote = with["remote"] ?: "origin"
        val branch = with["branch"] ?: "main"
        val message = with["message"] ?: "Update submodule refs"
        logBuffer.add("Updating workspace refs on $remote/$branch", "stdout")
        val cmd = """
            git fetch $remote $branch
            git checkout $branch
            git merge $remote/$branch --ff-only
            git add -A
            if [ -n "${'$'}(git status --porcelain)" ]; then
              git commit -m "$message"
            else
              echo "No submodule ref changes to commit"
            fi
        """.trimIndent()
        return executeShellCommand(cmd, authHeaderConfigEnv())
    }

    /**
     * Builds env vars that scope the bearer auth header to the cloneUrl's host only,
     * so submodule fetches to external hosts (github.com, etc.) don't leak the token.
     */
    internal fun authHeaderConfigEnv(): Map<String, String> {
        val base = mapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_ASKPASS" to "echo",
        )
        val originBase = try {
            val uri = java.net.URI(cloneUrl)
            if (uri.scheme != null && uri.host != null) {
                // git matches http.<url>.* by exact port (omitted = scheme default), so a
                // non-default port must appear in the key or the header is never sent
                val port = if (uri.port > 0) ":${uri.port}" else ""
                "${uri.scheme}://${uri.host}$port/"
            } else null
        } catch (_: Exception) {
            null
        } ?: return base + mapOf(
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.extraHeader",
            "GIT_CONFIG_VALUE_0" to "Authorization: Bearer $agentToken",
        )
        return base + mapOf(
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.$originBase.extraHeader",
            "GIT_CONFIG_VALUE_0" to "Authorization: Bearer $agentToken",
        )
    }

    /**
     * The registry base URL for HTTP calls: an explicit `http://` / `https://` scheme in the agent
     * config's `registryUrl` is honored (a local dev registry is plain http); a bare host gets https.
     */
    private fun registryBaseUrl(registry: String): String =
        if (registry.startsWith("http://") || registry.startsWith("https://")) registry.trimEnd('/')
        else "https://${registry.trimEnd('/')}"

    /** The registry HOST (no scheme) — what docker login and .npmrc registry lines expect. */
    private fun registryHost(registry: String, docker: Boolean): String {
        val host = registry.removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (docker && host.startsWith("127.0.0.1")) {
            return "host.docker.internal${host.removePrefix("127.0.0.1")}"
        }
        return host
    }

    private suspend fun executeSetupRegistry(with: Map<String, String>): StepResult {
        val registry = registryUrl.ifEmpty {
            logBuffer.add("Error: no registry URL configured in agent config", "stderr")
            return StepResult(false, 1)
        }
        val repository = with["repository"] ?: ""

        logBuffer.add("Configuring registry authentication for $registry", "stdout")

        val mavenUrl = if (repository.isNotEmpty()) {
            "${registryBaseUrl(registry)}/maven/$repository"
        } else {
            "${registryBaseUrl(registry)}/maven"
        }

        val cmd = buildString {
            appendLine("# Docker registry auth")
            appendLine("echo '$agentToken' | docker login '${registryHost(registry, true)}' --username api_token --password-stdin")
            appendLine()
            appendLine("# npm registry auth")
            appendLine("cat > \"\$HOME/.npmrc\" << 'NPMRC'")
            appendLine("//${registryHost(registry, false)}/:_authToken=${agentToken}")
            appendLine("NPMRC")
            appendLine("chmod 600 \"\$HOME/.npmrc\"")
            appendLine()
            appendLine("# Gradle/Maven registry auth")
            appendLine("mkdir -p \"\$HOME/.gradle\"")
            appendLine("cat > \"\$HOME/.gradle/gradle.properties\" << 'GRADLE'")
            appendLine("boscaRegistryUrl=$mavenUrl")
            appendLine("boscaRegistryUsername=api_token")
            appendLine("boscaRegistryPassword=${agentToken}")
            appendLine("GRADLE")
            appendLine("chmod 600 \"\$HOME/.gradle/gradle.properties\"")
            appendLine()
            appendLine("# Export env vars for tools that prefer them")
            appendLine("echo \"BOSCA_REGISTRY_URL=$registry\" >> \"\$BOSCA_ENV\"")
            appendLine("echo \"BOSCA_REGISTRY_TOKEN=$agentToken\" >> \"\$BOSCA_ENV\"")
        }

        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeCache(with: Map<String, String>): StepResult {
        val key = with["key"] ?: return StepResult(false, 1)
        val paths = parseListValue(with["paths"]) ?: return StepResult(false, 1)
        val restoreKeys = with["restore-keys"]?.split(",")?.map { it.trim() } ?: emptyList()

        logBuffer.add("Cache key: $key", "stdout")
        logBuffer.add("Paths: ${paths.joinToString(", ")}", "stdout")

        val cacheDir = File(workDir.parentFile, "cache")
        cacheDir.mkdirs()

        val keyHash = hashString(key)
        val cacheFile = File(cacheDir, "$keyHash.tar.gz")
        if (cacheFile.exists()) {
            logBuffer.add("Cache hit for key: $key", "stdout")
            val extractCmd = "tar -xzf '${cacheFile.absolutePath}' -C /"
            return executeShellCommand(extractCmd, emptyMap())
        }

        for (restoreKey in restoreKeys) {
            val prefix = hashString(restoreKey).take(16)
            val matchingFiles = cacheDir.listFiles()?.filter { it.name.startsWith(prefix) }
            if (!matchingFiles.isNullOrEmpty()) {
                logBuffer.add("Partial cache hit for restore-key: $restoreKey", "stdout")
                val extractCmd = "tar -xzf '${matchingFiles.first().absolutePath}' -C /"
                return executeShellCommand(extractCmd, emptyMap())
            }
        }

        logBuffer.add("Cache miss, will save after job completes", "stdout")
        return StepResult(true, 0)
    }

    private suspend fun executeUploadArtifact(with: Map<String, String>): StepResult {
        val name = with["name"] ?: return StepResult(false, 1)
        val paths = parseListValue(with["paths"]) ?: return StepResult(false, 1)

        logBuffer.add("Uploading artifact: $name", "stdout")
        val artifactDir = File(workDir.parentFile, "artifacts")
        artifactDir.mkdirs()

        val tarFile = File(artifactDir, "$name.tar.gz")
        val pathArgs = paths.joinToString(" ") { "'$it'" }
        val cmd = "tar -czf '${tarFile.absolutePath}' -C '${workDir.absolutePath}' $pathArgs"
        val result = executeShellCommand(cmd, emptyMap())
        if (!result.success) return result

        val uploadResult = uploadToServer(tarFile, "ci/artifacts/$repositoryId/$pipelineRunId/$name")
        if (!uploadResult) {
            logBuffer.add("Warning: server upload failed, artifact stored locally only", "stderr")
        }
        return StepResult(true, 0)
    }

    private suspend fun executeDownloadArtifact(with: Map<String, String>): StepResult {
        val name = with["name"] ?: return StepResult(false, 1)
        val path = with["path"] ?: "./artifacts"

        logBuffer.add("Downloading artifact: $name to $path", "stdout")

        val destDir = File(workDir, path)
        destDir.mkdirs()
        val tarFile = File(destDir, "$name.tar.gz")

        val downloaded = downloadFromServer("ci/artifacts/$repositoryId/$pipelineRunId/$name", tarFile)
        if (!downloaded) {
            val localArtifact = File(workDir.parentFile, "artifacts/$name.tar.gz")
            if (localArtifact.exists()) {
                logBuffer.add("Using local artifact", "stdout")
                localArtifact.copyTo(tarFile, overwrite = true)
            } else {
                logBuffer.add("Artifact not found: $name", "stderr")
                return StepResult(false, 1)
            }
        }

        val cmd = "tar -xzf '${tarFile.absolutePath}' -C '${destDir.absolutePath}'"
        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeSetupJava(with: Map<String, String>): StepResult {
        val version = with["version"] ?: "25"
        val distribution = with["distribution"] ?: "zulu"

        // Oracle GraalVM is fetched straight from Oracle GDS rather than SDKMAN:
        // SDKMAN carries no 25.1 (Innovation) GraalVM candidate yet, and Oracle
        // GraalVM is not published to the graalvm-ce-builds repo. `stream` selects
        // the GDS feature line (e.g. 25i1 = "JDK 25 Innovation 1"); `version` pins
        // the JDK-base build within that line (e.g. 25.0.3). The extracted dir is
        // named for the graal compiler version (e.g. graalvm-25.1.3+9.1), which is
        // NOT the JDK base, so we locate it by glob and handle the macOS
        // Contents/Home layout instead of assuming a fixed name.
        if (distribution == "graal") {
            val stream = with["stream"]
            if (stream.isNullOrBlank()) {
                logBuffer.add("Error: setup-java distribution=graal requires 'stream' (e.g. 25i1)", "stderr")
                return StepResult(false, 1)
            }
            logBuffer.add("Setting up Oracle GraalVM $stream / $version (Oracle GDS)", "stdout")
            val cmd = """
                set -o pipefail
                case "${'$'}(uname -s)" in
                    Linux)  GDS_OS=linux ;;
                    Darwin) GDS_OS=macos ;;
                    *) echo "setup-java: unsupported OS ${'$'}(uname -s) for GraalVM" >&2; exit 1 ;;
                esac
                case "${'$'}(uname -m)" in
                    x86_64|amd64)  GDS_ARCH=x64 ;;
                    arm64|aarch64) GDS_ARCH=aarch64 ;;
                    *) echo "setup-java: unsupported arch ${'$'}(uname -m) for GraalVM" >&2; exit 1 ;;
                esac
                GRAALVM_HOME_DIR="${'$'}HOME/.bosca/graalvm/${stream}-${version}-${'$'}{GDS_OS}-${'$'}{GDS_ARCH}"
                if [ ! -x "${'$'}GRAALVM_HOME_DIR/bin/native-image" ]; then
                    rm -rf "${'$'}GRAALVM_HOME_DIR"
                    TMP="${'$'}(mktemp -d)"
                    URL="https://gds.oracle.com/download/graal/${stream}/archive/graalvm-jdk-${stream}-${version}_${'$'}{GDS_OS}-${'$'}{GDS_ARCH}_bin.tar.gz"
                    echo "Downloading Oracle GraalVM: ${'$'}URL"
                    curl -fsSL "${'$'}URL" | tar -xz -C "${'$'}TMP"
                    TOP="${'$'}(ls -d "${'$'}TMP"/graalvm-* 2>/dev/null | head -1)"
                    if [ -z "${'$'}TOP" ]; then echo "setup-java: no graalvm-* directory in downloaded archive" >&2; exit 1; fi
                    if [ -d "${'$'}TOP/Contents/Home" ]; then SRC="${'$'}TOP/Contents/Home"; else SRC="${'$'}TOP"; fi
                    mkdir -p "${'$'}(dirname "${'$'}GRAALVM_HOME_DIR")"
                    mv "${'$'}SRC" "${'$'}GRAALVM_HOME_DIR"
                    rm -rf "${'$'}TMP"
                fi
                echo "JAVA_HOME=${'$'}GRAALVM_HOME_DIR" >> "${'$'}BOSCA_ENV"
                echo "GRAALVM_HOME=${'$'}GRAALVM_HOME_DIR" >> "${'$'}BOSCA_ENV"
                echo "${'$'}GRAALVM_HOME_DIR/bin" >> "${'$'}BOSCA_PATH"
            """.trimIndent()
            return executeShellCommand(cmd, emptyMap())
        }

        logBuffer.add("Setting up Java $version ($distribution)", "stdout")

        val cmd = """
            if command -v sdk &> /dev/null; then
                sdk install java ${version}-${distribution} || true
                sdk use java ${version}-${distribution}
                JAVA_HOME_DIR=${'$'}(sdk home java ${version}-${distribution})
                echo "JAVA_HOME=${'$'}JAVA_HOME_DIR" >> "${'$'}BOSCA_ENV"
                echo "${'$'}JAVA_HOME_DIR/bin" >> "${'$'}BOSCA_PATH"
            elif command -v java &> /dev/null; then
                echo "Java already available: ${'$'}(java -version 2>&1 | head -1)"
            else
                echo "Error: no package manager found to install Java"
                exit 1
            fi
        """.trimIndent()
        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeSetupNode(with: Map<String, String>): StepResult {
        val version = with["version"] ?: "22"
        logBuffer.add("Setting up Node.js $version", "stdout")

        val cmd = """
            if [ -z "${'$'}{NVM_DIR:-}" ]; then
                if [ -n "${'$'}{XDG_CONFIG_HOME:-}" ]; then
                    NVM_DIR="${'$'}XDG_CONFIG_HOME/nvm"
                else
                    NVM_DIR="${'$'}HOME/.nvm"
                fi
            fi
            export NVM_DIR
            if [ -s "${'$'}NVM_DIR/nvm.sh" ]; then
                . "${'$'}NVM_DIR/nvm.sh"
            elif command -v nvm &> /dev/null; then
                true
            else
                curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
                . "${'$'}NVM_DIR/nvm.sh"
            fi
            if ! nvm version $version > /dev/null 2>&1; then
                nvm install $version
            fi
            nvm use $version
            echo "${'$'}(dirname ${'$'}(nvm which $version))" >> "${'$'}BOSCA_PATH"
        """.trimIndent()
        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeSetupPython(with: Map<String, String>): StepResult {
        val version = with["version"] ?: "3.12"
        logBuffer.add("Setting up Python $version", "stdout")

        val cmd = """
            if command -v pyenv &> /dev/null; then
                pyenv install -s $version
                pyenv local $version
                echo "${'$'}(pyenv prefix $version)/bin" >> "${'$'}BOSCA_PATH"
            elif command -v python3 &> /dev/null; then
                echo "Python already available: ${'$'}(python3 --version)"
            else
                echo "Error: no package manager found to install Python"
                exit 1
            fi
        """.trimIndent()
        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeSetupGo(with: Map<String, String>): StepResult {
        val version = with["version"] ?: "1.23"
        logBuffer.add("Setting up Go $version", "stdout")

        val os = System.getProperty("os.name").lowercase().let {
            when {
                it.contains("mac") -> "darwin"
                else -> "linux"
            }
        }
        val arch = System.getProperty("os.arch").let {
            when (it) { "aarch64", "arm64" -> "arm64"; else -> "amd64" }
        }

        val cmd = """
            if ! command -v go &> /dev/null || ! go version | grep -q "go$version"; then
                curl -fsSL "https://go.dev/dl/go${version}.${os}-${arch}.tar.gz" -o /tmp/go.tar.gz
                sudo rm -rf /usr/local/go
                sudo tar -C /usr/local -xzf /tmp/go.tar.gz
            fi
            echo "/usr/local/go/bin" >> "${'$'}BOSCA_PATH"
            echo "GOROOT=/usr/local/go" >> "${'$'}BOSCA_ENV"
            echo "Installed: ${'$'}(/usr/local/go/bin/go version)"
        """.trimIndent()
        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeSetupGradle(with: Map<String, String>): StepResult {
        val gradleVersion = with["gradle-version"]
        logBuffer.add("Setting up Gradle${gradleVersion?.let { " $it" } ?: " (wrapper)"}", "stdout")

        if (gradleVersion == null) {
            logBuffer.add("Using Gradle wrapper from project", "stdout")
            return StepResult(true, 0)
        }

        val cmd = """
            curl -fsSL "https://services.gradle.org/distributions/gradle-${gradleVersion}-bin.zip" -o /tmp/gradle.zip
            unzip -q -o /tmp/gradle.zip -d /opt
            echo "/opt/gradle-${gradleVersion}/bin" >> "${'$'}BOSCA_PATH"
            echo "Installed: ${'$'}(/opt/gradle-${gradleVersion}/bin/gradle --version | head -3)"
        """.trimIndent()
        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeSetupPnpm(with: Map<String, String>): StepResult {
        val version = with["version"] ?: "10"
        logBuffer.add("Setting up pnpm $version", "stdout")

        val cmd = """
            if command -v corepack &> /dev/null; then
                corepack enable
                corepack prepare pnpm@$version --activate
            else
                npm install -g pnpm@$version
            fi
            echo "Installed: ${'$'}(pnpm --version)"
        """.trimIndent()
        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeSetupRust(with: Map<String, String>): StepResult {
        val toolchain = with["toolchain"] ?: "stable"
        logBuffer.add("Setting up Rust ($toolchain)", "stdout")

        val cmd = """
            if ! command -v rustup &> /dev/null; then
                curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain $toolchain
            else
                rustup default $toolchain
                rustup update $toolchain
            fi
            echo "${'$'}HOME/.cargo/bin" >> "${'$'}BOSCA_PATH"
            echo "Installed: ${'$'}(${'$'}HOME/.cargo/bin/rustc --version)"
        """.trimIndent()
        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeSetupK6(with: Map<String, String>): StepResult {
        logBuffer.add("Setting up k6", "stdout")

        val os = System.getProperty("os.name").lowercase().let {
            when {
                it.contains("mac") -> "macos"
                else -> "linux"
            }
        }
        val arch = System.getProperty("os.arch").let {
            when (it) { "aarch64", "arm64" -> "arm64"; else -> "amd64" }
        }

        val cmd = """
            if ! command -v k6 &> /dev/null; then
                curl -fsSL "https://github.com/grafana/k6/releases/latest/download/k6-latest-$os-$arch.tar.gz" -o /tmp/k6.tar.gz
                tar -xzf /tmp/k6.tar.gz -C /tmp
                sudo mv /tmp/k6-*/k6 /usr/local/bin/k6 || mv /tmp/k6-*/k6 "${'$'}HOME/.local/bin/k6"
            fi
            echo "Installed: ${'$'}(k6 version)"
        """.trimIndent()
        return executeShellCommand(cmd, emptyMap())
    }

    private fun checkDockerAvailable(): StepResult? {
        val available = try {
            val p = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) { false }

        return if (!available) {
            kotlinx.coroutines.runBlocking {
                logBuffer.add("Error: ${DockerUnavailableException().message}", "stderr")
            }
            StepResult(false, 1)
        } else null
    }

    private suspend fun executeDockerBuild(with: Map<String, String>, env: Map<String, String>): StepResult {
        checkDockerAvailable()?.let { return it }
        val context = with["context"] ?: "."
        val file = with["file"] ?: "Dockerfile"
        val tags = with["tags"]?.split(",")?.map { it.trim() } ?: listOf("latest")
        val push = with["push"]?.toBoolean() ?: false
        val registry = (with["registry"] ?: registryUrl.ifEmpty { null })?.let { registryHost(it, true) }
        val buildArgs = with["build-args"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val invalidTags = tags.filter { it.endsWith(":") || it.isEmpty() }
        if (invalidTags.isNotEmpty()) {
            logBuffer.add("Error: invalid Docker tag(s): ${invalidTags.joinToString(", ") { "\"$it\"" }} — expression likely resolved to empty", "stderr")
            return StepResult(false, 1)
        }

        logBuffer.add("Building Docker image${registry?.let { " (registry: $it)" } ?: ""}", "stdout")

        val tagArgs = tags.joinToString(" ") { "-t ${registry?.let { r -> "$r/" } ?: ""}$it" }
        val buildArgArgs = buildArgs.joinToString(" ") { "--build-arg '$it'" }
        val buildCmd = "docker build -f $file $tagArgs${if (buildArgArgs.isNotEmpty()) " $buildArgArgs" else ""} $context"
        val buildResult = executeShellCommand(buildCmd, env)
        if (!buildResult.success) return buildResult

        if (push) {
            logBuffer.add("Pushing image to registry", "stdout")
            for (tag in tags) {
                val fullTag = "${registry?.let { "$it/" } ?: ""}$tag"
                val pushResult = executeShellCommand("docker push $fullTag", env)
                if (!pushResult.success) return pushResult
            }
        }

        return StepResult(true, 0)
    }

    private suspend fun executeNotify(with: Map<String, String>, env: Map<String, String>): StepResult {
        val channel = with["channel"] ?: "slack"
        val message = with["message"] ?: "Pipeline notification"

        logBuffer.add("Sending notification via $channel: $message", "stdout")

        val webhookUrl = env["WEBHOOK_URL"] ?: secrets["WEBHOOK_URL"]
        if (webhookUrl == null) {
            logBuffer.add("Warning: no WEBHOOK_URL configured, skipping notification", "stderr")
            return StepResult(true, 0)
        }

        val escapedMessage = message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")

        val cmd = "printf '%s' '{\"text\": \"$escapedMessage\"}' | curl -sf -X POST -H 'Content-Type: application/json' -d @- '$webhookUrl'"
        return executeShellCommand(cmd, env)
    }

    private suspend fun executeDockerRun(image: String, command: String, env: Map<String, String>): StepResult {
        checkDockerAvailable()?.let { return it }
        logBuffer.add("Running in container: $image", "stdout")
        val envArgs = env.entries.joinToString(" ") { "-e '${it.key}=${it.value}'" }
        val cmd = "docker run --rm -v '${workDir.absolutePath}:/workspace' -w /workspace $envArgs $image bash -c '$command'"
        return executeShellCommand(cmd, emptyMap())
    }

    private suspend fun executeShellCommand(command: String, extraEnv: Map<String, String>): StepResult {
        return try {
            val wrappedCommand = buildShellWrapper(command)

            val processBuilder = ProcessBuilder("bash", "-e", "-c", wrappedCommand)
                .directory(workDir)
                .redirectErrorStream(false)

            val processEnv = processBuilder.environment()
            processEnv.putAll(env)
            processEnv.putAll(extraEnv)
            // The runner's API token is a control-plane credential. Trusted built-in actions use
            // it directly, but arbitrary pipeline shell code must never inherit it.
            CONTROL_PLANE_ENVIRONMENT.forEach(processEnv::remove)
            processEnv["BOSCA_ENV"] = sharedEnvFile.absolutePath
            processEnv["BOSCA_PATH"] = sharedPathFile.absolutePath
            for ((k, v) in secrets) {
                processEnv[k] = v
            }

            val process = withContext(Dispatchers.IO) {
                processBuilder.start()
            }

            val stdoutReader = process.inputStream.bufferedReader()
            val stderrReader = process.errorStream.bufferedReader()

            val stdoutThread = Thread {
                kotlinx.coroutines.runBlocking {
                    try {
                        var line = stdoutReader.readLine()
                        while (line != null) {
                            logBuffer.add(line, "stdout")
                            line = stdoutReader.readLine()
                        }
                    } catch (_: java.io.IOException) {
                        // Stream closed by the forced-drain fallback below; there
                        // is nothing left to read, so exit the loop quietly.
                    }
                }
            }
            val stderrThread = Thread {
                kotlinx.coroutines.runBlocking {
                    try {
                        var line = stderrReader.readLine()
                        while (line != null) {
                            logBuffer.add(line, "stderr")
                            line = stderrReader.readLine()
                        }
                    } catch (_: java.io.IOException) {
                        // See stdout reader above.
                    }
                }
            }

            stdoutThread.start()
            stderrThread.start()

            // Wait in short slices so the cancellation signal interrupts a running process instead of
            // being noticed only after it exits — a cancelled job must stop its in-flight step. The
            // finally block below tears down the whole process tree for every non-completed outcome
            // (cancelled, timed out) exactly as it always has.
            var completed = false
            var wasCancelled = false
            try {
                val deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes.toLong())
                while (true) {
                    if (withContext(Dispatchers.IO) {
                            process.waitFor(cancelPollMs, TimeUnit.MILLISECONDS)
                        }) {
                        completed = true
                        break
                    }
                    if (cancelled()) {
                        wasCancelled = true
                        break
                    }
                    if (System.nanoTime() >= deadlineNanos) break
                }
            } finally {
                // Drain stdout/stderr before returning so the tail of the log —
                // usually the error output on a failed step — actually reaches
                // the buffer. The reader loops only exit on EOF, and EOF only
                // arrives once every write end of the pipe is closed.
                //
                // destroyForcibly() kills *only* the direct bash child. A step
                // that backgrounds a process (gradle daemon, docker, a dev
                // server, `something &`) leaves that descendant holding the pipe
                // open, so the readers never EOF and their final lines are
                // stranded. Snapshot the descendants while bash is still alive
                // — once it exits they are reparented to init and drop off our
                // tree — then reap the whole tree so the pipe closes.
                val descendants = process.descendants().toList()
                if (process.isAlive) process.destroyForcibly()
                descendants.forEach { it.destroyForcibly() }

                withContext(Dispatchers.IO) {
                    stdoutThread.join(drainTimeoutMs)
                }
                withContext(Dispatchers.IO) {
                    stderrThread.join(drainTimeoutMs)
                }

                if (stdoutThread.isAlive || stderrThread.isAlive) {
                    // A descendant we couldn't reach (e.g. one already reparented
                    // on a clean exit) is still holding the pipe. Force the
                    // readers to unblock instead of hanging, and record the
                    // truncation rather than dropping the tail silently.
                    logBuffer.add(
                        "[bosca-agent] step log reader did not reach EOF within ${drainTimeoutMs}ms; " +
                            "trailing output may be truncated",
                        "stderr",
                    )
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    withContext(Dispatchers.IO) {
                        stdoutThread.join(drainTimeoutMs)
                    }
                    withContext(Dispatchers.IO) {
                        stderrThread.join(drainTimeoutMs)
                    }
                }
            }

            if (wasCancelled) {
                logBuffer.add("Step cancelled — the pipeline job was cancelled", "stderr")
                return StepResult(false, 130, cancelled = true)
            }
            if (!completed) {
                logBuffer.add("Step timed out after $timeoutMinutes minutes", "stderr")
                return StepResult(false, 124)
            }

            val exitCode = process.exitValue()
            StepResult(exitCode == 0, exitCode)
        } catch (e: Exception) {
            logBuffer.add("Execution error: ${e.message}", "stderr")
            StepResult(false, 1)
        }
    }

    private fun buildShellWrapper(command: String): String {
        return buildString {
            appendLine("# Source shared environment from prior steps")
            appendLine("if [ -f \"${sharedEnvFile.absolutePath}\" ]; then")
            appendLine("  set -a")
            appendLine("  . \"${sharedEnvFile.absolutePath}\"")
            appendLine("  set +a")
            appendLine("fi")
            appendLine("# Prepend PATH entries from prior steps")
            appendLine("if [ -s \"${sharedPathFile.absolutePath}\" ]; then")
            appendLine("  while IFS= read -r _bosca_p; do")
            appendLine("    export PATH=\"\$_bosca_p:\$PATH\"")
            appendLine("  done < \"${sharedPathFile.absolutePath}\"")
            appendLine("fi")
            appendLine(command)
        }
    }

    private fun artifactsBaseUrl(): String {
        val source = cloneUrl.ifEmpty { serverUrl }
        if (source.isEmpty()) return ""
        val uri = java.net.URI(source)
        return "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    }

    private suspend fun uploadToServer(file: File, remotePath: String): Boolean {
        val baseUrl = artifactsBaseUrl()
        if (baseUrl.isEmpty()) {
            logBuffer.add("Upload skipped: no server URL configured", "stderr")
            return false
        }
        val uploadUrl = "$baseUrl/$remotePath"
        var lastFailure = ""
        repeat(3) { attempt ->
            try {
                val process = ProcessBuilder(
                    "curl", "-sS", "--retry", "0",
                    "-o", "/dev/null", "-w", "%{http_code}",
                    "-X", "PUT",
                    "-H", "Authorization: Bearer $agentToken",
                    "-H", "Content-Type: application/octet-stream",
                    "--data-binary", "@${file.absolutePath}",
                    uploadUrl
                ).redirectErrorStream(false).start()
                if (!process.waitFor(90, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    lastFailure = "curl timed out after 90s"
                } else {
                    val httpCode = process.inputStream.bufferedReader().readText().trim()
                    val stderr = process.errorStream.bufferedReader().readText().trim()
                    if (process.exitValue() == 0 && httpCode.startsWith("2")) {
                        return true
                    }
                    lastFailure = "HTTP $httpCode (curl exit ${process.exitValue()})${if (stderr.isNotEmpty()) ": $stderr" else ""}"
                }
            } catch (e: Exception) {
                lastFailure = "curl error: ${e.message}"
            }
            if (attempt < 2) Thread.sleep((1000L * (attempt + 1)))
        }
        logBuffer.add("Upload to $uploadUrl failed: $lastFailure", "stderr")
        return false
    }

    private suspend fun downloadFromServer(remotePath: String, destFile: File): Boolean {
        val baseUrl = artifactsBaseUrl()
        if (baseUrl.isEmpty()) {
            logBuffer.add("Download skipped: no server URL configured", "stderr")
            return false
        }
        val downloadUrl = "$baseUrl/$remotePath"
        var lastFailure = ""
        repeat(3) { attempt ->
            try {
                val process = ProcessBuilder(
                    "curl", "-sS", "--retry", "0",
                    "-w", "%{http_code}",
                    "-H", "Authorization: Bearer $agentToken",
                    "-o", destFile.absolutePath,
                    downloadUrl
                ).redirectErrorStream(false).start()
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    lastFailure = "curl timed out after 120s"
                    destFile.delete()
                } else {
                    val httpCode = process.inputStream.bufferedReader().readText().trim()
                    val stderr = process.errorStream.bufferedReader().readText().trim()
                    if (process.exitValue() == 0 && httpCode.startsWith("2")) {
                        return true
                    }
                    lastFailure = "HTTP $httpCode (curl exit ${process.exitValue()})${if (stderr.isNotEmpty()) ": $stderr" else ""}"
                    destFile.delete()
                }
            } catch (e: Exception) {
                lastFailure = "curl error: ${e.message}"
            }
            if (attempt < 2) Thread.sleep((1000L * (attempt + 1)))
        }
        logBuffer.add("Download from $downloadUrl failed: $lastFailure", "stderr")
        return false
    }

    private fun buildStepEnv(step: StepDefinition, context: ExpressionContext): Map<String, String> {
        return step.env.mapValues { (_, v) -> expressionParser.interpolate(v, context) }
    }

    private fun parseListValue(value: String?): List<String>? {
        if (value == null) return null
        val trimmed = value.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length - 1)
                .split(",")
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .filter { it.isNotEmpty() }
        }
        return trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private suspend fun positiveSeconds(
        with: Map<String, String>,
        key: String,
        default: Long,
        action: String,
    ): Long? {
        val raw = with[key] ?: return default
        val value = raw.toLongOrNull()
        if (value == null || value <= 0) {
            logBuffer.add("Error: $action '$key' must be a positive integer, got '$raw'", "stderr")
            return null
        }
        return value
    }

    companion object {
        /** `uses: deploy` with-keys the action itself consumes; everything else is a config override. */
        private val DEPLOY_RESERVED_KEYS = setOf("environment", "repository", "target")

        /** `uses: rollback` with-keys the action itself consumes. */
        private val ROLLBACK_RESERVED_KEYS = setOf("environment", "repository", "target", "toRevision")
        private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9._-]+")
        private val ASC_ISSUER_ID = Regex("[A-Za-z0-9-]+")
        private val ASC_KEY_ID = Regex("[A-Za-z0-9]+")
        private val WINDOW = Regex("([0-9]+)([smhd])")
        private val CONTROL_PLANE_ENVIRONMENT = setOf(
            "BOSCA_TOKEN",
            "BOSCA_CI_AGENT_TOKEN",
        )
    }
}
