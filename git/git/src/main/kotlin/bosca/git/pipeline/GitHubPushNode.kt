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
    category = NodeCategory.ACTION, label = "Import GitHub Refs",
    description = "Import verified GitHub branch and tag changes, preserving their originating principal.",
    group = "Git", subgroup = "GitHub",
    inputs = [InputSlot(name = "delivery", kind = SlotKind.OBJECT, type = GitHubDelivery::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING)],
)
@Serializable
@SerialName("githubPush")
class GitHubPushNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val delivery = GitHubPushNodeSerializer.deserialize(context, inputs).delivery
        return GitHubPushNodeSerializer.serialize(provide<GitHubSyncService>().synchronizePush(delivery).name)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val delivery = GitHubPushNodeSerializer.deserializePartial(context, inputs).delivery
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "githubPush")
            put("repositoryId", delivery?.repositoryId?.toString())
            put("deliveryId", delivery?.deliveryId)
        })
        return GitHubPushNodeSerializer.serialize("PREVIEW")
    }
}
