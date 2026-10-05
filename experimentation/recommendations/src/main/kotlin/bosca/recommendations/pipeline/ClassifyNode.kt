package bosca.recommendations.pipeline

import bosca.content.metadata.model.Metadata
import bosca.di.provide
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
import bosca.recommendations.service.ClassificationService
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ClassifyResult(
    val id: UUID,
    val categoryIds: List<UUID>,
)

/**
 * Action node contributed by `recommendations`: classifies an inbound content item, recording
 * normalized categories on it via [ClassificationService].
 *
 * The input is a [Metadata] object. A stored pipeline wires its metadata source `→ this`, so the
 * engine classifies items by id. The node passes its input through to chain; under
 * [PipelineContext.dryRun] it records the intended action and skips. On failure it throws (no silent
 * catch); the run executor records the failed run.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Classify",
    description = "Classifies an inbound metadata item, assigning normalized content categories.",
    group = "Recommendations",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Metadata",
            type = Metadata::class,
            description = "A Metadata (e.g. from Get Metadata).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = ClassifyResult::class,
            typeLabel = "Classify Result",
            description = "The classified metadata id & category ids, passed through.",
        ),
    ],
)
@Serializable
@SerialName("recommendations.classify")
class ClassifyNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // deserializePartial (not deserialize): a dry run traces whatever is wired so far, so a
        // missing required input must not fail it.
        val input = ClassifyNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "classify")
            put("metadataId", input.`in`?.id?.toString() ?: "")
        })
        return inputs.first
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = ClassifyNodeSerializer.deserialize(context, inputs)
        val categoryIds = provide<ClassificationService>().classify(input.`in`.id)
        return ClassifyNodeSerializer.serialize(ClassifyResult(input.`in`.id, categoryIds))
    }
}
