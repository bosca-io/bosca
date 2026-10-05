package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** The last common branch or tag value; a null SHA records a synchronized deletion. */
@Serializable
data class GitHubRefState(
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val ref: String,
    val sha: String? = null,
    /** Distinguishes a common deleted ref (sha = null) from an initial unresolved conflict. */
    val synchronized: Boolean = false,
    @ColumnName("bosca_sha") val boscaSha: String? = null,
    @ColumnName("github_sha") val githubSha: String? = null,
    val conflict: Boolean = false,
    /** An anonymous import awaiting its verified push; zero means the ref was created. */
    @ColumnName("unattributed_before_sha") val unattributedBeforeSha: String? = null,
    /** Identifies the imported ref write so a later native write cannot inherit its attribution. */
    @Contextual @ColumnName("unattributed_ref_modified") val unattributedRefModified: OffsetDateTime? = null,
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
)

@Serializable
enum class GitHubSyncResult { APPLIED, UNCHANGED, STALE, CONFLICT, IGNORED }

/** Signed push fields used by the inbound ref node. Object IDs retain annotated tag objects. */
@Serializable
data class GitHubPush(
    val ref: String,
    val before: String,
    val after: String,
)
