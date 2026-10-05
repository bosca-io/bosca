package bosca.communications.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A registered BML message project. Registration is the platform-side
 * control surface over the BML Message Server: the server always renders a project's
 * active (latest published) artifact version, and [pinnedVersion] — when set — overrides that
 * per send (rollback without a redeploy; changing it is a registry update).
 */
@Serializable
data class BmlMessageProject(
    val key: String,
    val description: String? = null,
    /** Provenance: the git repository (git domain, by id) containing the project's message units. */
    @Contextual
    @ColumnName("repository_id")
    val repositoryId: UUID? = null,
    /** When set, sends render THIS published version instead of the active one. */
    @ColumnName("pinned_version")
    val pinnedVersion: String? = null,
    val created: OffsetDateTime = OffsetDateTime.now(),
    val modified: OffsetDateTime = OffsetDateTime.now(),
)
