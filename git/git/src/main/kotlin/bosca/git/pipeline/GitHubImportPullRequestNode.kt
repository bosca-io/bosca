package bosca.git.pipeline

import bosca.di.provide
import bosca.git.model.GitHubDelivery
import bosca.git.service.GitHubSyncService
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@PipelineNodeType(
    category = NodeCategory.ACTION, label = "Import GitHub Pull Requests",
    description = "Import current GitHub pull request lifecycle changes.",
    group = "Git", subgroup = "GitHub",
    inputs = [InputSlot(name = "delivery", kind = SlotKind.OBJECT, type = GitHubDelivery::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING)],
)
@Serializable
@SerialName("githubImportPullRequest")
class GitHubImportPullRequestNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val delivery = GitHubImportPullRequestNodeSerializer.deserialize(context, inputs).delivery
        return GitHubImportPullRequestNodeSerializer.serialize(provide<GitHubSyncService>().synchronizePullRequest(delivery).name)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val delivery = GitHubImportPullRequestNodeSerializer.deserializePartial(context, inputs).delivery
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "githubImportPullRequest")
            put("repositoryId", delivery?.repositoryId?.toString())
            put("deliveryId", delivery?.deliveryId)
        })
        return GitHubImportPullRequestNodeSerializer.serialize("PREVIEW")
    }
}
