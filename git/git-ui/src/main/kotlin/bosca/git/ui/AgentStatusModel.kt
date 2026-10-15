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

    /** The running step of the current job, as Studio's Current Command card shows it. */
    val currentStep: GitUiAgentsData.Git.PipelineAgents.CurrentJob.Steps?
        get() = agent.currentJob?.steps?.sortedBy { it.ordinal }?.firstOrNull { it.status.name == "RUNNING" }

    /** The running step's command and whether it is an action reference rather than a shell script. */
    val currentCommand: Pair<String, Boolean>?
        get() = currentStep?.let { step -> step.run?.let { it to false } ?: step.uses?.let { it to true } }

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
