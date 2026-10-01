package bosca.comments.model

import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CommentInput(
    val parentId: Long?,
    // Optional on input. When null the `addComment` resolver falls back to the
    // author's profile visibility (a regular user's comment inherits their
    // privacy). A moderator (MANAGE) may set it explicitly — e.g. PUBLIC so an
    // approved comment is shown to everyone. Null is "not specified", which is
    // why this is nullable rather than defaulting to a concrete value.
    val visibility: ProfileVisibility? = null,
    val content: String,
    @Contextual
    val attributes: JsonElement?,
    @Contextual
    val systemAttributes: JsonElement?,
    @Contextual
    val impersonateId: UUID?
)