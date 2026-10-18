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
    category = NodeCategory.ACTION, label = "Reconcile GitHub CI Statuses",
    description = "Recover missed CI status updates across enabled repository pairs.",
    group = "Git", subgroup = "GitHub",
    inputs = [InputSlot(name = "request", kind = SlotKind.ANY, type = JsonElement::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING)],
)
@Serializable
@SerialName("githubReconcileCiStatuses")
class GitHubReconcileCiStatusesNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        GitHubReconcileCiStatusesNodeSerializer.deserialize(context, inputs)
        return GitHubReconcileCiStatusesNodeSerializer.serialize(provide<GitHubSyncService>().reconcileCiStatuses().toString())
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        context.trace?.actions?.set(id, buildJsonObject { put("action", "githubReconcileCiStatuses") })
        return GitHubReconcileCiStatusesNodeSerializer.serialize("PREVIEW")
    }
}
