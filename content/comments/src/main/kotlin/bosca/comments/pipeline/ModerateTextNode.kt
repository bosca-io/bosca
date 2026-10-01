package bosca.comments.pipeline

import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Screens arbitrary text through OpenAI's omni-moderation model (via Koog) and emits the full
 * [TextModerationResult]. Domain-agnostic — usable in any workflow that needs to assess text. The
 * decision (approve / flag / block) is deliberately NOT made here: pair this with an Evaluate Text
 * Moderation node so the same assessment can be evaluated against different thresholds, stored, or
 * branched on independently.
 *
 * When no OpenAI key is configured, or the call fails, it emits a not-harmful result so it never
 * blocks a pipeline.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Moderate Text",
    description = "Screens text with OpenAI moderation and emits the full moderation result.",
    group = "Content",
    subgroup = "Comments",
    inputs = [InputSlot(name = "text", kind = SlotKind.STRING, typeLabel = "Text")],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = ModerationResult::class,
            typeLabel = "Moderation result",
        ),
    ],
)
@Serializable
@SerialName("moderateText")
class ModerateTextNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        context.trace?.recordAction(id, buildJsonObject { put("action", "moderateText") })
        return ModerateTextNodeSerializer.serialize(ModerationResult(false, emptyMap()))
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val text = ModerateTextNodeSerializer.deserialize(context, inputs).text

        val executor = provide<PromptExecutor>()
        val moderation = executor.moderate(prompt("moderate-text") { user(text) }, OpenAIModels.Moderation.Omni)

        return ModerateTextNodeSerializer.serialize(moderation)
    }

    companion object {
    }
}
