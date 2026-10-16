package bosca.git.ui

import bosca.git.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
data class AgentPage(
    val allowed: Boolean,
    val status: String,
    val agents: List<GitUiAgentsData.Git.PipelineAgents>,
    val selected: GitUiAgentsData.Git.PipelineAgents?,
    val config: GitUiOrchestratorData.Git.GetOrchestratorConfig?,
)
