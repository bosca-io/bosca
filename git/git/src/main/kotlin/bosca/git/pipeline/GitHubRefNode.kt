package bosca.git.pipeline

import bosca.di.provide
import bosca.git.model.RefUpdateEvent
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
    category = NodeCategory.ACTION, label = "Export GitHub Refs",
    description = "Mirror Bosca branch and tag changes to the paired GitHub repository.",
    group = "Git", subgroup = "GitHub",
    inputs = [InputSlot(name = "event", kind = SlotKind.OBJECT, type = RefUpdateEvent::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING)],
)
@Serializable
@SerialName("githubRef")
class GitHubRefNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val event = GitHubRefNodeSerializer.deserialize(context, inputs).event
        return GitHubRefNodeSerializer.serialize(provide<GitHubSyncService>().synchronizeRef(event).name)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val event = GitHubRefNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "githubRef")
            put("repositoryId", event?.repositoryId?.toString())
            put("ref", event?.ref)
            put("beforeSha", event?.beforeSha)
            put("afterSha", event?.afterSha)
        })
        return GitHubRefNodeSerializer.serialize("PREVIEW")
    }
}
