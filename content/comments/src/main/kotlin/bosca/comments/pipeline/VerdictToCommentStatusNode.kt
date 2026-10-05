package bosca.comments.pipeline

import bosca.comments.model.CommentStatus
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps a moderation [EvaluationVerdict] to a [CommentStatus] — APPROVED → APPROVED,
 * FLAGGED → PENDING_APPROVAL, BLOCKED → BLOCKED. The bridge from the generic Evaluate Text
 * Moderation node to the comment-specific Set Comment Status node.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Verdict to Comment Status",
    description = "Converts a moderation verdict (APPROVED/FLAGGED/BLOCKED) to a comment status.",
    group = "Content",
    subgroup = "Comments",
    inputs = [InputSlot(name = "verdict", kind = SlotKind.STRING, type = EvaluationVerdict::class, typeLabel = "Verdict")],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING, type = CommentStatus::class, typeLabel = "Comment status")],
)
@Serializable
@SerialName("verdictToCommentStatus")
class VerdictToCommentStatusNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        // The generated codec models the STRING verdict slot as a plain String; bridge it to the enum
        // through its serializer so the mapping matches the wire format exactly.
        val input = VerdictToCommentStatusNodeSerializer.deserialize(context, inputs)
        val verdict = context.json.decodeFromJsonElement(EvaluationVerdict.serializer(), JsonPrimitive(input.verdict))
        // Output wrapping stays hand-written: the value deliberately carries CommentStatus.serializer(),
        // while the generated serialize models the STRING out slot as a plain String.
        return PipelineValue.of(verdictToCommentStatus(verdict), CommentStatus.serializer())
    }
}

/** APPROVED → APPROVED, FLAGGED → PENDING_APPROVAL, BLOCKED → BLOCKED. */
internal fun verdictToCommentStatus(verdict: EvaluationVerdict): CommentStatus = when (verdict) {
    EvaluationVerdict.APPROVED -> CommentStatus.APPROVED
    EvaluationVerdict.FLAGGED -> CommentStatus.PENDING_APPROVAL
    EvaluationVerdict.BLOCKED -> CommentStatus.BLOCKED
}
