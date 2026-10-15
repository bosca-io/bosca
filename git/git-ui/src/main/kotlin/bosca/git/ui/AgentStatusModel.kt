package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class AgentStatusModel(var agent: GitUiAgentsData.Git.PipelineAgents, var message: String = "") {
    val active: Boolean
        get() = agent.status == GitAgentStatus.ONLINE || agent.status == GitAgentStatus.BUSY

    suspend fun refresh(ctx: RenderContext) {
        message = ""
        try {
            agent =
                ctx.gql
                    .execute(GitUiAgents, GitUiAgents.Variables(null, true, agent.id))
                    .git
                    .pipelineAgents
                    .firstOrNull { it.id == agent.id } ?: error("Agent no longer exists.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Could not refresh agent."
        }
    }
}
