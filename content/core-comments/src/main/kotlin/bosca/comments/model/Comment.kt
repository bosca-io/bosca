package bosca.comments.model

import bosca.db.annotation.ColumnName
import bosca.profile.model.ProfileVisibility
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One comment row in `metadata_comments`. Bosca's comment subsystem is
 * metadata-keyed: every comment hangs off a specific
 * `(metadata_id, version)` pair. Replies are modelled by a separate
 * `parent_id` column that references a peer in the same thread —
 * the parent-child relationship is not on the model here because the
 * Rust counterpart doesn't carry it on the read shape; replies are
 * fetched with a dedicated `getMetadataCommentsByParentId` call.
 *
 * The shape mirrors the Rust counterpart in
 * `workspace/core/server/src/models/content/comment.rs` — same
 * columns, same nullability, same likes-denormalization. The
 * `collection_comments` / `prayer_comments` tables V55 / V78 create
 * in the public schema are scaffolding for follow-on work; this
 * Kotlin port focuses on the metadata surface the Rust
 * implementation actually populates.
 */
@Serializable
data class Comment(
    val id: Long,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID? = null,
    val version: Int? = null,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID? = null,
    @Contextual
    val created: OffsetDateTime,
    @Contextual
    val modified: OffsetDateTime,
    val status: CommentStatus,
    val content: String,
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    @ColumnName("system_attributes")
    val systemAttributes: JsonElement? = null,
    val likes: Int = 0,
    /** When true the comment is pinned to the top of its thread (moderator action). */
    val pinned: Boolean = false,
    /**
     * Visibility scope for the comment. Only `PUBLIC` comments that are also
     * `APPROVED` are shown to non-authors — the read queries gate on
     * `visibility = 'public' and status = 'approved'`. A moderator sets this
     * (on add, or via `setCommentVisibility`) to control who can see a comment.
     * The default here is only for in-memory construction; the DB value always
     * wins on read.
     */
    val visibility: ProfileVisibility = ProfileVisibility.PUBLIC,
)
