package bosca.git.pipeline

import bosca.di.provide
import bosca.git.service.GitHubSyncService
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.*
import bosca.pipelines.node.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@PipelineNodeType(
    category = NodeCategory.ACTION, label = "Reconcile GitHub Pull Requests",
    description = "Recover missed pull request lifecycle changes across enabled repository pairs.",
    group = "Git", subgroup = "GitHub",
    inputs = [InputSlot(name = "request", kind = SlotKind.ANY, type = JsonElement::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING)],
)
@Serializable
@SerialName("githubReconcilePullRequests")
class GitHubReconcilePullRequestsNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        GitHubReconcilePullRequestsNodeSerializer.deserialize(context, inputs)
        return GitHubReconcilePullRequestsNodeSerializer.serialize(provide<GitHubSyncService>().reconcilePullRequests().size.toString())
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        context.trace?.actions?.set(id, buildJsonObject { put("action", "githubReconcilePullRequests") })
        return GitHubReconcilePullRequestsNodeSerializer.serialize("PREVIEW")
    }
}
