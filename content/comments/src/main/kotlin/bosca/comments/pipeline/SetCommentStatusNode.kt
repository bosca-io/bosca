package bosca.comments.pipeline

import bosca.comments.model.Comment
import bosca.comments.model.CommentStatus
import bosca.comments.service.CommentService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action: sets a comment's moderation status. Takes a [Comment] (e.g. from a Get Comment node) and a
 * [CommentStatus] enum value. To drive it from a Moderate Text → Evaluate Text Moderation chain, map
 * the verdict to a CommentStatus first (approved→APPROVED, flagged→PENDING_APPROVAL, blocked→BLOCKED).
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Comment Status",
    description = "Sets a comment's moderation status to the given CommentStatus.",
    group = "Content",
    subgroup = "Comments",
    inputs = [
        InputSlot(name = "comment", kind = SlotKind.OBJECT, type = Comment::class, typeLabel = "Comment"),
        InputSlot(
            name = "status",
            kind = SlotKind.STRING,
            type = CommentStatus::class,
            typeLabel = "Comment status",
        ),
    ],
)
@Serializable
@SerialName("setCommentStatus")
class SetCommentStatusNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject { put("action", "setCommentStatus") })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = SetCommentStatusNodeSerializer.deserialize(context, inputs)
        val comment = input.comment
        // The generated codec models the STRING status slot as a plain String; bridge it to the enum
        // through its serializer so the mapping matches the wire format exactly.
        val status = context.json.decodeFromJsonElement(CommentStatus.serializer(), JsonPrimitive(input.status))
        val metadataId = comment.metadataId ?: error("Comment ${comment.id} has no metadata id")
        val version = comment.version ?: error("Comment ${comment.id} has no version")
        provide<CommentService>().setMetadataCommentStatus(metadataId, version, comment.id, status)
        return null
    }
}
