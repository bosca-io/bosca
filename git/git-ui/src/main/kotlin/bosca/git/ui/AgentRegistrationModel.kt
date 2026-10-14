package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
class AgentRegistrationModel(
    val page: AgentPage,
    var name: String = "",
    var labels: String = "",
    var mode: String = "RUNNER",
    var registering: Boolean = true,
    @Transient var token: String = "",
    var agentId: String = "",
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun register(name: String, labels: String, mode: String, ctx: RenderContext) {
        registering = true
        this.name = name
        this.labels = labels
        this.mode = mode
        perform {
            require(name.isNotBlank()) { "Enter an agent name." }
            val result =
                ctx.gql
                    .execute(
                        GitUiRegisterAgent,
                        GitUiRegisterAgent.Variables(
                            name.trim(),
                            commaValues(labels),
                            GitAgentMode.valueOf(mode),
                        ),
                    )
                    .git
                    .registerAgent
            token = result.token
            agentId = result.agent.id
            message = "Agent registered. Copy the token now; it will not be shown again."
        }
    }

    suspend fun deregister(id: String, confirmed: Boolean, ctx: RenderContext) = perform {
        registering = false
        require(confirmed) { "Confirm agent deregistration." }
        require(
            ctx.gql
                .execute(GitUiDeregisterAgent, GitUiDeregisterAgent.Variables(id))
                .git
                .deregisterAgent
        ) {
            "Agent was not deregistered."
        }
        nextHref = "/ci-agents"
    }

    suspend fun hideToken() {
        token = ""
        message = ""
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
            message = e.message ?: "Agent action failed."
        }
    }
}
