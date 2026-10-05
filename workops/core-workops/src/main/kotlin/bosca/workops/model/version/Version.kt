package bosca.workops.model.version

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A project release / version (R9). Tasks reference versions via
 * `affectsVersionIds` and `fixVersionIds` (already columns on
 * `workops.task` from V2). Phase 5 ships the `Version` row and the
 * service-layer rule: a version may not be deleted while any task
 * still references it — admins archive instead.
 *
 * @property sequenceNumber per-project sort order. The service
 *                          assigns the next available value on
 *                          create.
 */
@BatchKey("id")
@Serializable
data class Version(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    val name: String,
    val description: String? = null,
    @ColumnName("start_date")
    @Contextual
    val startDate: OffsetDateTime? = null,
    @ColumnName("release_date")
    @Contextual
    val releaseDate: OffsetDateTime? = null,
    val released: Boolean = false,
    val archived: Boolean = false,
    @ColumnName("sequence_number")
    val sequenceNumber: Int,
    val version: Long = 0,
)

/** Input for creating a [Version]. */
@Serializable
data class CreateVersionInput(
    @Contextual
    val projectId: UUID,
    val name: String,
    val description: String? = null,
    @Contextual
    val startDate: OffsetDateTime? = null,
    @Contextual
    val releaseDate: OffsetDateTime? = null,
)
