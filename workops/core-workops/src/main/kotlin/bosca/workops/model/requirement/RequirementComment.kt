package bosca.workops.model.requirement

import bosca.comments.model.CommentStatus
import bosca.db.annotation.ColumnName
import bosca.profile.model.ProfileVisibility
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RequirementComment(
    val id: Long,
    @ColumnName("parent_id")
    val parentId: Long? = null,
    @ColumnName("requirement_id")
    @Contextual
    val requirementId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    @ColumnName("impersonator_id")
    @Contextual
    val impersonatorId: UUID? = null,
    val visibility: ProfileVisibility = ProfileVisibility.USER,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
    val status: CommentStatus,
    val content: String,
    val attributes: JsonElement? = null,
    @ColumnName("system_attributes")
    val systemAttributes: JsonElement? = null,
    @ColumnName("has_replies")
    val hasReplies: Boolean = false,
    val deleted: Boolean = false,
    val likes: Int = 0,
)

@Serializable
data class RequirementCommentInput(
    val parentId: Long? = null,
    val visibility: ProfileVisibility = ProfileVisibility.USER,
    val content: String,
    val attributes: JsonElement? = null,
    val systemAttributes: JsonElement? = null,
)
