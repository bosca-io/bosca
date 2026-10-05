@file:OptIn(ExperimentalCoroutinesApi::class)

package bosca.cli.ci

import bosca.cli.update.UpdateChecker
import bosca.graphql.gen.ClaimJobData
import bosca.graphql.gen.GitAgentStatus
import bosca.graphql.gen.GitPipelineRunStatus
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

@Serializable
data class VmInstance(
    val dropletId: String,
    val ephemeralAgentId: String,
    val jobId: String,
)

class OrchestratorRunner(
    private val api: CiApi,
    private val agentId: Uuid,
    private val labels: List<String>,
    private val pollIntervalSeconds: Int = 5,
    internal val journal: VmJournal = VmJournal.default(),
    private val heartbeatIntervalMs: Long = 10_000,
) {
    private val running = AtomicBoolean(true)
    private val activeVms = ConcurrentHashMap<String, VmInstance>()
    internal var config: OrchestratorConfig? = null
    internal var provider: CloudProvider? = null
    private var alertSinks: List<AlertSink> = emptyList()
    private val heartbeatDispatcher = newSingleThreadContext("orchestrator-heartbeat")
    private val log = CiLogger(agentId, "orchestrator")

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            running.set(false)
        })
    }

    suspend fun run() = coroutineScope {
        val orchestratorConfig = api.getOrchestratorConfig(agentId)
        if (orchestratorConfig == null) {
            System.err.println("Error: no orchestrator configuration found. Configure via 'bosca ci agent configure-orchestrator'.")
            return@coroutineScope
        }

        config = OrchestratorConfig(
            provider = orchestratorConfig.provider,
            maxConcurrentVms = orchestratorConfig.maxConcurrentVms,
            maxJobTimeoutMinutes = orchestratorConfig.maxJobTimeoutMinutes,
            maxVmLifetimeMinutes = orchestratorConfig.maxVmLifetimeMinutes,
            defaults = VmProfile(
                region = orchestratorConfig.defaults.region,
                size = orchestratorConfig.defaults.size,
                image = orchestratorConfig.defaults.image,
            ),
            runnerProfiles = parseRunnerProfiles(orchestratorConfig.runnerProfiles),
        )

        alertSinks = orchestratorConfig.alertSinks.map { AlertSink(it.type, it.url) }

        val agentConfig = AgentConfig.load()
        try {
            provider = createCloudProvider(orchestratorConfig.provider, agentConfig)
        } catch (e: CloudProviderException) {
            System.err.println("Error: ${e.message}")
            return@coroutineScope
        }

        println("Orchestrator started. Provider: ${config!!.provider}, Max VMs: ${config!!.maxConcurrentVms}")

        reconcileOrphans()

        val heartbeatJob = launch(heartbeatDispatcher + SupervisorJob()) {
            var consecutiveFailures = 0
            while (running.get()) {
                try {
                    val start = System.currentTimeMillis()
                    api.agentHeartbeat(agentId)
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed > 15_000) {
                        log.warn("Heartbeat slow", "roundTripMs" to elapsed)
                    }
                    consecutiveFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveFailures++
                    val backoff = (heartbeatIntervalMs shl consecutiveFailures.coerceAtMost(5))
                        .coerceAtMost(300_000L)
                    log.warn("Heartbeat failed, retrying in ${backoff}ms", "error" to e.message, "consecutiveFailures" to consecutiveFailures)
                    delay(backoff)
                    continue
                }
                delay(heartbeatIntervalMs)
            }
        }

        try {
            while (running.get()) {
                if (activeVms.size >= (config?.maxConcurrentVms ?: 5)) {
                    delay(pollIntervalSeconds * 1000L)
                    continue
                }

                try {
                    val job = api.claimJob(agentId, labels)
                    if (job != null) {
                        launch { provisionAndRun(job) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Orchestrator poll error", e)
                }
                delay(pollIntervalSeconds * 1000L)
            }
        } finally {
            heartbeatJob.cancel()
            heartbeatDispatcher.close()
            cleanupAllVms()
        }
    }

    private suspend fun provisionAndRun(job: ClaimJobData.Git.ClaimJob) {
        val jobId = job.id
        val runnerLabel = job.runnerLabel
        val profile = resolveProfile(runnerLabel)
        val agentConfig = AgentConfig.load()
        val serverUrl = agentConfig?.serverUrl ?: ""

        try {
            val registration = api.registerEphemeralAgent(
                jobId = jobId,
                name = "ephemeral-${jobId.toString().take(8)}",
                labels = listOf(runnerLabel),
                parentAgentId = agentId,
                timeoutMinutes = config?.maxJobTimeoutMinutes ?: 60,
            )

            val timeoutSeconds = (config?.maxJobTimeoutMinutes ?: 60) * 60
            val vmName = "bosca-ci-${jobId.toString().take(8)}"

            val userData = when (val p = provider) {
                is DigitalOceanProvider -> p.buildUserData(
                    serverUrl = serverUrl,
                    ephemeralToken = registration.token,
                    ephemeralAgentId = registration.agent.id,
                    jobId = jobId,
                    timeoutSeconds = timeoutSeconds,
                )
                else -> buildGenericUserData(
                    serverUrl,
                    registration.token,
                    registration.agent.id,
                    jobId,
                    timeoutSeconds,
                )
            }

            val vmId = provider!!.createVm(profile, vmName, userData)
            println("VM provisioned: id=$vmId, label=$runnerLabel, size=${profile.size}")

            val vm = VmInstance(
                dropletId = vmId,
                ephemeralAgentId = registration.agent.id.toString(),
                jobId = jobId.toString(),
            )
            activeVms[vmId] = vm
            journal.appendProvisioned(vm)

            try {
                api.setAgentInstanceId(registration.agent.id, vmId)
            } catch (e: Exception) {
                System.err.println("Warning: failed to record instanceId for agent ${registration.agent.id}: ${e.message}")
            }

            monitorJob(jobId, vm)
        } catch (e: Exception) {
            System.err.println("Failed to provision VM for job $jobId: ${e.message}")
            try {
                api.updateJobStatus(jobId, GitPipelineRunStatus.FAILURE, "Failed to provision a VM for this job: ${e.message}")
            } catch (reportError: Exception) {
                System.err.println(
                    "Also failed to report job $jobId failure to the server: ${reportError.message} — " +
                        "the job will stay RUNNING until the server-side reaper fails it"
                )
            }
        }
    }

    private suspend fun monitorJob(jobId: Uuid, vm: VmInstance) {
        val timeout = (config?.maxVmLifetimeMinutes ?: 90) * 60_000L
        val bootGraceMs = 120_000L
        val startTime = System.currentTimeMillis()
        var firstHeartbeatSeen = false

        while (running.get()) {
            val elapsed = System.currentTimeMillis() - startTime

            if (elapsed > timeout) {
                System.err.println("VM ${vm.dropletId} exceeded max lifetime (${config?.maxVmLifetimeMinutes}m), destroying")
                break
            }

            try {
                val agents = api.listAgents()
                val agent = agents.find { it.id.toString() == vm.ephemeralAgentId }

                if (agent != null && agent.status != GitAgentStatus.OFFLINE) {
                    firstHeartbeatSeen = true
                }

                if (firstHeartbeatSeen && (agent == null || agent.status == GitAgentStatus.OFFLINE)) {
                    break
                }

                if (!firstHeartbeatSeen && elapsed > bootGraceMs) {
                    System.err.println("VM ${vm.dropletId} never sent a heartbeat within ${bootGraceMs / 1000}s, destroying")
                    break
                }
            } catch (_: Exception) {}
            delay(10_000)
        }

        destroyVm(vm)
    }

    internal suspend fun reconcileOrphans() {
        val journalOrphans = try {
            journal.replay()
        } catch (e: Exception) {
            System.err.println("Failed to replay VM journal: ${e.message}")
            emptyList()
        }

        val serverOrphans = try {
            api.listAgents()
                .filter { it.ephemeral && it.parentAgentId == agentId && it.instanceId != null }
                .map {
                    VmInstance(
                        dropletId = it.instanceId!!,
                        ephemeralAgentId = it.id.toString(),
                        jobId = it.jobId?.toString() ?: "",
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Failed to query server for orphan agents: ${e.message}")
            emptyList()
        }

        val merged = LinkedHashMap<String, VmInstance>()
        for (vm in journalOrphans) merged[vm.dropletId] = vm
        for (vm in serverOrphans) merged[vm.dropletId] = vm

        if (merged.isEmpty()) {
            if (journal.replay().isEmpty()) journal.compact()
            return
        }

        println("Reconciling ${merged.size} orphan VM(s) from prior run (journal=${journalOrphans.size}, server=${serverOrphans.size})...")
        for (vm in merged.values) {
            try {
                destroyVm(vm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Reconciliation failed for ${vm.dropletId}: ${e.message}")
            }
        }
        if (journal.replay().isEmpty()) journal.compact()
    }

    private suspend fun destroyVm(vm: VmInstance) {
        println("Destroying VM ${vm.dropletId}")

        var deleted = false
        for (attempt in 1..3) {
            try {
                deleted = provider!!.deleteVm(vm.dropletId)
                if (deleted) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Delete attempt $attempt failed for VM ${vm.dropletId}: ${e.message}")
                if (attempt < 3) delay(2000L * attempt)
            }
        }

        if (!deleted) {
            val ex = VmDeletionException(vm.dropletId, 3)
            System.err.println(ex.message)
            sendAlert(ex.message!!)
        } else {
            journal.appendDestroyed(vm.dropletId)
        }

        activeVms.remove(vm.dropletId)
        try {
            api.deregisterAgent(Uuid.parse(vm.ephemeralAgentId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "Failed to deregister destroyed VM agent",
                "agentId" to vm.ephemeralAgentId,
                "error" to e.message,
            )
        }
    }

    private suspend fun cleanupAllVms() {
        println("Shutting down orchestrator, cleaning up ${activeVms.size} VMs...")
        for (vm in activeVms.values.toList()) {
            destroyVm(vm)
        }
    }

    private fun sendAlert(message: String) {
        for (sink in alertSinks) {
            try {
                val payload = when (sink.type) {
                    "slack" -> """{"text": "$message"}"""
                    "teams" -> """{"text": "$message"}"""
                    else -> """{"message": "$message"}"""
                }
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(sink.url)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                System.err.println("Failed to send alert to ${sink.type}: ${e.message}")
            }
        }
    }

    internal fun resolveProfile(runnerLabel: String): VmProfile {
        val profiles = config?.runnerProfiles ?: emptyMap()
        val profile = profiles[runnerLabel]
        val defaults = config?.defaults ?: VmProfile("nyc3", "s-2vcpu-4gb", "ubuntu-24-04-x64")
        return VmProfile(
            region = profile?.region?.ifEmpty { null } ?: defaults.region,
            size = profile?.size?.ifEmpty { null } ?: defaults.size,
            image = profile?.image?.ifEmpty { null } ?: defaults.image,
        )
    }

    internal fun parseRunnerProfiles(json: JsonElement?): Map<String, VmProfile> {
        if (json == null || json is JsonNull) return emptyMap()
        val obj = json as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, v) ->
            val profileObj = v as? JsonObject ?: return@mapValues VmProfile("", "", "")
            VmProfile(
                region = profileObj["region"]?.jsonPrimitive?.contentOrNull ?: "",
                size = profileObj["size"]?.jsonPrimitive?.contentOrNull ?: "",
                image = profileObj["image"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    internal fun buildGenericUserData(
        serverUrl: String,
        token: String,
        ephemeralAgentId: Uuid,
        jobId: Uuid,
        timeoutSeconds: Int,
    ): String {
        val watchdogSeconds = timeoutSeconds + 600
        return """
            #!/bin/bash
            set -e
            (sleep $watchdogSeconds && shutdown -h now) &
            curl -fsSL ${UpdateChecker.DEFAULT_INSTALL_SCRIPT_URL} | bash
            bosca ci agent start --ephemeral --token "$token" --url "$serverUrl" \
              --agent-id "$ephemeralAgentId" --job-id "$jobId"
            shutdown -h now
        """.trimIndent()
    }
}

data class OrchestratorConfig(
    val provider: String,
    val maxConcurrentVms: Int,
    val maxJobTimeoutMinutes: Int,
    val maxVmLifetimeMinutes: Int,
    val defaults: VmProfile,
    val runnerProfiles: Map<String, VmProfile>,
)

data class VmProfile(
    val region: String,
    val size: String,
    val image: String,
)

data class AlertSink(
    val type: String,
    val url: String,
)
