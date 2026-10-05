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
    category = NodeCategory.ACTION, label = "Reconcile GitHub Refs",
    description = "Recover missed branch and tag updates across enabled repository pairs.",
    group = "Git", subgroup = "GitHub",
    inputs = [InputSlot(name = "request", kind = SlotKind.ANY, type = JsonElement::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING)],
)
@Serializable
@SerialName("githubReconcileRefs")
class GitHubReconcileRefsNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        GitHubReconcileRefsNodeSerializer.deserialize(context, inputs)
        return GitHubReconcileRefsNodeSerializer.serialize(provide<GitHubSyncService>().reconcileRefs().size.toString())
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        context.trace?.actions?.set(id, buildJsonObject { put("action", "githubReconcileRefs") })
        return GitHubReconcileRefsNodeSerializer.serialize("PREVIEW")
    }
}
