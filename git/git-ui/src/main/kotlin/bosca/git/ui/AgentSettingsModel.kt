package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
class AgentSettingsModel(
    val page: AgentPage,
    var name: String = page.selected?.name.orEmpty(),
    var labels: String = page.selected?.labels?.joinToString(", ").orEmpty(),
    var provider: String = page.config?.provider.orEmpty(),
    var scope: String = page.config?.credentials?.selfDestructTokenScope.orEmpty(),
    var region: String = page.config?.defaults?.region.orEmpty(),
    var size: String = page.config?.defaults?.size.orEmpty(),
    var image: String = page.config?.defaults?.image.orEmpty(),
    var profiles: String = page.config?.runnerProfiles?.toString() ?: "{}",
    var sinks: String = Json.encodeToString(page.config?.alertSinks.orEmpty()),
    var concurrent: Int = page.config?.maxConcurrentVms ?: 5,
    var jobTimeout: Int = page.config?.maxJobTimeoutMinutes ?: 60,
    var lifetime: Int = page.config?.maxVmLifetimeMinutes ?: 120,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun save(name: String, labels: String, ctx: RenderContext) {
        this.name = name
        this.labels = labels
        perform {
            val id = requireNotNull(page.selected).id
            require(name.isNotBlank()) { "Enter an agent name." }
            ctx.gql.execute(
                GitUiUpdateAgent,
                GitUiUpdateAgent.Variables(id, name.trim(), commaValues(labels)),
            )
            nextHref = "/ci-agents/${encode(id)}"
        }
    }

    suspend fun configure(
        provider: String,
        scope: String,
        region: String,
        size: String,
        image: String,
        profiles: String,
        sinks: String,
        concurrent: Int,
        jobTimeout: Int,
        lifetime: Int,
        ctx: RenderContext,
    ) {
        this.provider = provider
        this.scope = scope
        this.region = region
        this.size = size
        this.image = image
        this.profiles = profiles
        this.sinks = sinks
        this.concurrent = concurrent
        this.jobTimeout = jobTimeout
        this.lifetime = lifetime
        perform {
            val agent = requireNotNull(page.selected)
            require(agent.mode == GitAgentMode.ORCHESTRATOR) { "Choose an orchestrator agent." }
            require(
                provider.isNotBlank() &&
                    region.isNotBlank() &&
                    size.isNotBlank() &&
                    image.isNotBlank()
            ) {
                "Provider and VM defaults are required."
            }
            require(concurrent >= 1 && jobTimeout >= 1 && lifetime >= 1) {
                "Limits must be positive."
            }
            val runners = Json.parseToJsonElement(profiles)
            require(runners is JsonObject) { "Runner profiles must be an object." }
            val alerts = Json.decodeFromString<List<GitAlertSinkInput>>(sinks)
            val config =
                GitOrchestratorConfigInput(
                    provider.trim(),
                    GitProviderCredentialsInput(scope.ifBlank { null }),
                    GitVmDefaultsInput(region.trim(), size.trim(), image.trim()),
                    runners,
                    concurrent,
                    jobTimeout,
                    lifetime,
                    alerts,
                )
            ctx.gql.execute(
                GitUiConfigureOrchestrator,
                GitUiConfigureOrchestrator.Variables(agent.id, config),
            )
            nextHref = "/ci-agents/${encode(agent.id)}"
        }
    }

    private suspend fun perform(block: suspend () -> Unit) {
        message = ""
        failed = false
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Agent settings failed."
        }
    }
}
