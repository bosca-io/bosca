package bosca.cli.ci

import bosca.graphql.gen.GitAgentMode
import bosca.graphql.gen.GitAgentStatus
import bosca.graphql.gen.GitAlertSinkInput
import bosca.graphql.gen.GitOrchestratorConfigInput
import bosca.graphql.gen.GitProviderCredentialsInput
import bosca.graphql.gen.GitVmDefaultsInput
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

internal data class EphemeralAgentIdentity(
    val agentId: String,
    val labels: List<String>,
)

internal fun resolveEphemeralAgentIdentity(
    agentId: String?,
    agentToken: String?,
    labels: String?,
    loadConfig: () -> AgentConfig?,
): EphemeralAgentIdentity {
    val providedLabels = labels?.split(",")?.map(String::trim) ?: listOf("default")
    if (agentId != null) {
        return EphemeralAgentIdentity(agentId, providedLabels)
    }
    if (agentToken != null) {
        val decoded = decodeAgentIdFromToken(agentToken)
            ?: throw IllegalArgumentException(
                "could not determine agent ID from token. Ensure token is valid."
            )
        return EphemeralAgentIdentity(decoded, providedLabels)
    }
    val config = loadConfig()
        ?: throw IllegalArgumentException(
            "no agent identity found. Pass --agent-id/--agent-token or run 'bosca ci agent register'."
        )
    return EphemeralAgentIdentity(config.agentId, config.labels)
}

internal fun decodeAgentIdFromToken(token: String): String? = try {
    val parts = token.split(".")
    if (parts.size < 2) {
        null
    } else {
        val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(payload)
        json.jsonObject["sub"]?.jsonPrimitive?.content
    }
} catch (_: Exception) {
    null
}

class AgentRegisterCommand : CiSubcommand("register") {
    override fun help(context: Context) = "Register a new build agent"

    private val name by option("--name", help = "Agent name").required()
    private val labels by option("--labels", help = "Comma-separated labels").required()
    private val mode by option("--mode", help = "Agent mode: runner or orchestrator").default("runner")

    override suspend fun execute(api: CiApi) {
        val agentMode = when (mode.lowercase()) {
            "runner" -> GitAgentMode.RUNNER
            "orchestrator" -> GitAgentMode.ORCHESTRATOR
            else -> {
                echo("Error: --mode must be 'runner' or 'orchestrator'", err = true)
                return
            }
        }
        val labelList = labels.split(",").map { it.trim() }
        val registration = api.registerAgent(name, labelList, agentMode)

        val config = AgentConfig(
            agentId = registration.agent.id.toString(),
            token = registration.token,
            serverUrl = api.serverUrl,
            name = registration.agent.name,
            labels = registration.agent.labels,
            mode = mode.lowercase(),
        )
        AgentConfig.save(config)

        echo("Agent registered successfully.")
        echo("  ID:     ${registration.agent.id}")
        echo("  Name:   ${registration.agent.name}")
        echo("  Labels: ${registration.agent.labels.joinToString(", ")}")
        echo("  Mode:   ${registration.agent.mode}")
        echo("  Config: ~/.bosca/agent.json")
    }
}

class AgentListCommand : CiSubcommand("list") {
    override fun help(context: Context) = "List registered build agents"

    private val status by option("--status", help = "Filter by status: online, offline, busy, draining")

    override suspend fun execute(api: CiApi) {
        val agentStatus = status?.let {
            when (it.lowercase()) {
                "online" -> GitAgentStatus.ONLINE
                "offline" -> GitAgentStatus.OFFLINE
                "busy" -> GitAgentStatus.BUSY
                "draining" -> GitAgentStatus.DRAINING
                else -> {
                    echo("Error: --status must be one of: online, offline, busy, draining", err = true)
                    return
                }
            }
        }
        val agents = api.listAgents(agentStatus)
        if (agents.isEmpty()) {
            echo("No agents found.")
            return
        }
        echo("%-36s  %-20s  %-12s  %-8s  %s".format("ID", "NAME", "MODE", "STATUS", "LABELS"))
        for (a in agents) {
            echo("%-36s  %-20s  %-12s  %-8s  %s".format(
                a.id, a.name, a.mode, a.status, a.labels.joinToString(",")
            ))
        }
    }
}

class AgentDeregisterCommand : CiSubcommand("deregister") {
    override fun help(context: Context) = "Deregister a build agent"

    private val id by option("--id", help = "Agent ID").required()

    override suspend fun execute(api: CiApi) {
        api.deregisterAgent(Uuid.parse(id))
        echo("Agent deregistered.")
    }
}

class AgentStartCommand : CiSubcommand("start") {
    override fun help(context: Context) = "Start the build agent"

    private val name by option("--name", help = "Agent name")
    private val labels by option("--labels", help = "Comma-separated labels")
    private val orchestrator by option("--orchestrator", help = "Start in orchestrator mode").flag()
    private val ephemeral by option("--ephemeral", help = "Start in ephemeral mode (single job)").flag()
    private val agentId by option(
        "--agent-id",
        envvar = "BOSCA_CI_AGENT_ID",
        help = "Pre-registered agent ID for ephemeral mode",
    )
    private val agentToken by option("--agent-token", help = "Agent token for ephemeral mode")
    private val server by option("--server", help = "Server URL for ephemeral mode")
    private val jobId by option(
        "--job-id",
        envvar = "BOSCA_CI_JOB_ID",
        help = "Job ID for ephemeral mode",
    )
    private val pollInterval by option("--poll-interval", help = "Poll interval in seconds").int().default(5)

    override suspend fun execute(api: CiApi) {
        when {
            ephemeral -> startEphemeral(api)
            orchestrator -> startOrchestrator(api)
            else -> startRunner(api)
        }
    }

    private suspend fun startEphemeral(api: CiApi) {
        val targetJobId = jobId
        if (targetJobId == null) {
            echo("Error: --job-id required for ephemeral mode", err = true)
            throw ProgramResult(1)
        }

        val identity = try {
            resolveEphemeralAgentIdentity(agentId, agentToken, labels, AgentConfig::load)
        } catch (e: IllegalArgumentException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(1)
        }

        echo("Starting ephemeral agent for job $targetJobId...")
        val runner = AgentRunner(
            api,
            Uuid.parse(identity.agentId),
            identity.labels,
            pollInterval,
            ephemeral = true,
        )
        runner.executeSingleJob(Uuid.parse(targetJobId))
    }

    private suspend fun startOrchestrator(api: CiApi) {
        val config = AgentConfig.load() ?: run {
            echo("Error: no agent configuration found. Run 'bosca ci agent register' first.", err = true)
            throw ProgramResult(1)
        }
        echo("Starting orchestrator agent '${config.name}'...")
        val orchestratorRunner = OrchestratorRunner(api, Uuid.parse(config.agentId), config.labels, pollInterval)
        orchestratorRunner.run()
    }

    private suspend fun startRunner(api: CiApi) {
        val config = AgentConfig.load() ?: run {
            echo("Error: no agent configuration found. Run 'bosca ci agent register' first.", err = true)
            throw ProgramResult(1)
        }
        echo("Starting runner agent '${config.name}' with labels [${config.labels.joinToString(", ")}]...")
        echo("Poll interval: ${pollInterval}s")
        val runner = AgentRunner(api, Uuid.parse(config.agentId), config.labels, pollInterval)
        runner.run()
    }
}

class ConfigureOrchestratorCommand : CiSubcommand("configure-orchestrator") {
    override fun help(context: Context) = "Configure an orchestrator's provider settings"

    private val id by option("--id", help = "Agent ID").required()
    private val provider by option("--provider", help = "Cloud provider (e.g. digitalocean)").required()
    private val region by option("--region", help = "Default VM region").required()
    private val size by option("--size", help = "Default VM size").required()
    private val image by option("--image", help = "Default VM image").required()
    private val maxVms by option("--max-vms", help = "Max concurrent VMs").int().default(5)
    private val maxJobTimeout by option("--max-job-timeout", help = "Max job timeout in minutes").int().default(60)
    private val maxVmLifetime by option("--max-vm-lifetime", help = "Max VM lifetime in minutes").int().default(90)

    override suspend fun execute(api: CiApi) {
        val config = GitOrchestratorConfigInput(
            provider = provider,
            credentials = GitProviderCredentialsInput(
                selfDestructTokenScope = "droplet:delete",
            ),
            defaults = GitVmDefaultsInput(
                region = region,
                size = size,
                image = image,
            ),
            runnerProfiles = JsonObject(emptyMap()),
            maxConcurrentVms = maxVms,
            maxJobTimeoutMinutes = maxJobTimeout,
            maxVmLifetimeMinutes = maxVmLifetime,
            alertSinks = emptyList(),
        )

        api.configureOrchestrator(Uuid.parse(id), config)
        echo("Orchestrator configured successfully.")
        echo("  Provider:     $provider")
        echo("  Region:       $region")
        echo("  Size:         $size")
        echo("  Image:        $image")
        echo("  Max VMs:      $maxVms")
        echo("  Job timeout:  ${maxJobTimeout}m")
        echo("  VM lifetime:  ${maxVmLifetime}m")
    }
}
