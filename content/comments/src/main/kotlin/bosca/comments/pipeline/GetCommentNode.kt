package bosca.comments.pipeline

import bosca.comments.model.Comment
import bosca.comments.service.CommentService
import bosca.di.provide
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

/**
 * Fetch: reads a comment by its id and outputs the full [Comment]. Downstream nodes pull what they
 * need — the text (for Moderate Text, via a JSONata `content` step) or the comment itself (for Set
 * Comment Status). Feed it a comment id, e.g. the `commentId` of a triggering CommentCreatedEvent.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Comment",
    description = "Reads a comment by its id and outputs the full comment.",
    group = "Content",
    subgroup = "Comments",
    inputs = [InputSlot(name = "commentId", kind = SlotKind.INTEGER, typeLabel = "Comment id")],
    outputs = [OutputSlot(name = "out", kind = SlotKind.OBJECT, type = Comment::class, typeLabel = "Comment")],
)
@Serializable
@SerialName("getComment")
class GetCommentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val commentId = GetCommentNodeSerializer.deserialize(context, inputs).commentId
        val comment = provide<CommentService>().getMetadataCommentById(commentId) ?: error("Comment $commentId not found")
        return GetCommentNodeSerializer.serialize(comment)
    }
}
