package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A Bosca-hosted git repository whose access is governed by [PermissibleEntity]
 * permission evaluation.
 *
 * Repositories are scoped to an owner profile or organization via [ownerId].
 * The [slug] is immutable after creation because it
 * forms part of the clone URL (`https://{host}/{owner}/{slug}.git`).
 */
@BatchKey("id")
@Serializable
data class Repository(
    @Contextual
    override val id: UUID = UUID.NIL,
    /** URL-safe, immutable-after-creation identifier used in clone URLs. */
    val slug: String,
    val name: String,
    val description: String? = null,
    @Contextual
    @ColumnName("owner_id")
    val ownerId: UUID,
    val visibility: Visibility = Visibility.PRIVATE,
    @ColumnName("default_branch")
    val defaultBranch: String = "main",
    val archived: Boolean = false,
    val deleted: Boolean = false,
    @Contextual
    @ColumnName("deleted_at")
    val deletedAt: OffsetDateTime? = null,
    @Contextual
    @ColumnName("forked_from_id")
    val forkedFromId: UUID? = null,
    @ColumnName("content_type")
    val contentType: RepositoryContentType? = null,
    @ColumnName("disk_size_bytes")
    val diskSizeBytes: Long = 0L,
    val configuration: RepositoryConfiguration = RepositoryConfiguration(),
    @ColumnName("next_pr_number")
    val nextPrNumber: Int = 1,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val updated: OffsetDateTime = OffsetDateTime.now()
) : PermissibleEntity<UUID> {

    @Transient
    override val public: Boolean = visibility == Visibility.PUBLIC

    @Transient
    override val publicContent: Boolean = visibility == Visibility.PUBLIC

    @Transient
    override val publicList: Boolean = visibility != Visibility.PRIVATE

    @Transient
    override val publicSupplementary: Boolean = false

    @Transient
    override val isPublished: Boolean = !archived && !deleted

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = deleted
}
