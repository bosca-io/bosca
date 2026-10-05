package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A git ref (branch, tag, or symbolic ref like HEAD) stored in PostgreSQL rather
 * than on the filesystem. Each row maps a ref name to its current object ID within
 * a specific repository, enabling stateless git-server pods to resolve refs without
 * local disk state.
 */
@Serializable
data class DfsRef(
    @Contextual
    @ColumnName("repository_id")
    val repositoryId: UUID,
    val name: String,
    @ColumnName("object_id")
    val objectId: String,
    @ColumnName("peeled_id")
    val peeledId: String? = null,
    @ColumnName("symbolic_target")
    val symbolicTarget: String? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val updated: OffsetDateTime = OffsetDateTime.now()
)
