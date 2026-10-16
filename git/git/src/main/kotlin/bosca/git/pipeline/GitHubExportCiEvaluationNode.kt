package bosca.git.pipeline

import bosca.di.provide
import bosca.git.model.PipelineEvaluationEvent
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
    category = NodeCategory.ACTION, label = "Export GitHub CI Evaluation",
    description = "Publish applicable CI results, including intentionally skipped pipelines.",
    group = "Git", subgroup = "GitHub",
    inputs = [InputSlot(name = "event", kind = SlotKind.OBJECT, type = PipelineEvaluationEvent::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING)],
)
@Serializable
@SerialName("githubExportCiEvaluation")
class GitHubExportCiEvaluationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val event = GitHubExportCiEvaluationNodeSerializer.deserialize(context, inputs).event
        return GitHubExportCiEvaluationNodeSerializer.serialize(provide<GitHubSyncService>().synchronizePipelineEvaluation(event).name)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val event = GitHubExportCiEvaluationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "githubExportCiEvaluation")
            put("repositoryId", event?.repositoryId?.toString())
            put("evaluationId", event?.evaluationId?.toString())
        })
        return GitHubExportCiEvaluationNodeSerializer.serialize("PREVIEW")
    }
}
