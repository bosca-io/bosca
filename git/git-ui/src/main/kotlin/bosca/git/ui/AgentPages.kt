package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.*

/** Resolves administrator membership through the same canonical group used by Studio. */
internal suspend fun isGitAdministrator(ctx: RenderContext): Boolean =
    ctx.gql.execute(GitUiAccess, Unit).security.principals.current.groups.any {
        it.name == "administrators"
    }

suspend fun agentPage(): AgentPage {
    val ctx = currentRenderContext()
    val status =
        ctx.query["status"]
            ?.takeIf { value -> GitAgentStatus.entries.any { it.name == value } }
            .orEmpty()
    if (!isGitAdministrator(ctx)) return AgentPage(false, status, emptyList(), null, null)
    val agents =
        ctx.gql
            .execute(
                GitUiAgents,
                GitUiAgents.Variables(
                    status.takeIf(String::isNotBlank)?.let(GitAgentStatus::valueOf)
                ),
            )
            .git
            .pipelineAgents
    val id = ctx.params["id"]
    val selected = agents.firstOrNull { it.id == id }
    if (id != null && selected == null) ctx.notFound = true
    val config =
        if (selected?.mode == GitAgentMode.ORCHESTRATOR)
            ctx.gql
                .execute(GitUiOrchestrator, GitUiOrchestrator.Variables(selected.id))
                .git
                .getOrchestratorConfig
        else null
    return AgentPage(true, status, agents, selected, config)
}
