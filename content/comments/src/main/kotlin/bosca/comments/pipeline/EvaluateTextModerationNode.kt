package bosca.comments.pipeline

import ai.koog.prompt.dsl.ModerationResult
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EvaluationVerdict {
    APPROVED,
    FLAGGED,
    BLOCKED
}

/**
 * Turns a [ModerationResult] into a verdict — "approved", "flagged" or "blocked" — using two
 * configurable confidence thresholds. Pure and provider-agnostic: separating it from Moderate Text
 * lets the same assessment be evaluated against different thresholds, or fed from any source.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Evaluate Text Moderation",
    description = "Maps a moderation result to a verdict: APPROVED, FLAGGED or BLOCKED.",
    group = "Content",
    subgroup = "Comments",
    inputs = [
        InputSlot(
            name = "moderation",
            kind = SlotKind.OBJECT,
            type = ModerationResult::class,
            typeLabel = "Moderation result",
        ),
    ],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING, type = EvaluationVerdict::class, typeLabel = "Verdict (APPROVED | FLAGGED | BLOCKED)")],
    settings = [
        SettingSlot(
            name = "flagThreshold",
            control = SettingControl.NUMBER,
            label = "Flag threshold",
            default = "0.1",
            description = "Category confidence at or above which text is flagged for review.",
        ),
        SettingSlot(
            name = "autoRejectThreshold",
            control = SettingControl.NUMBER,
            label = "Block threshold",
            default = "0.5",
            description = "Category confidence at or above which flagged text is blocked.",
        ),
    ],
)
@Serializable
@SerialName("evaluateTextModeration")
class EvaluateTextModerationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val flagThreshold: Double = 0.1,
    val autoRejectThreshold: Double = 0.5,
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val result = EvaluateTextModerationNodeSerializer.deserialize(context, inputs).moderation
        // Output wrapping stays hand-written: the value deliberately carries EvaluationVerdict.serializer(),
        // while the generated serialize models the STRING out slot as a plain String.
        return PipelineValue.of(moderationVerdict(result, flagThreshold, autoRejectThreshold), EvaluationVerdict.serializer())
    }
}

internal fun moderationVerdict(
    result: ModerationResult,
    flagThreshold: Double,
    autoRejectThreshold: Double,
): EvaluationVerdict {
    val maxScore = result.categories.values.mapNotNull { it.confidenceScore }.maxOrNull() ?: 0.0
    return when {
        result.isHarmful && maxScore >= autoRejectThreshold -> EvaluationVerdict.BLOCKED
        result.isHarmful || maxScore >= flagThreshold -> EvaluationVerdict.FLAGGED
        else -> EvaluationVerdict.APPROVED
    }
}
