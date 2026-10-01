package bosca.workops.model.project

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * The top of Work Ops's three-level delivery hierarchy
 * (`Portfolio` → `Program` → `Project`, R1).
 *
 * A portfolio bundles related programs so executives can roll up status,
 * OKRs (R19), and capacity (R20) across many delivery teams without
 * forcing the whole organization into one flat project list. Portfolios
 * own no tasks directly — every task hangs off a project, and every
 * project lives inside a program inside a portfolio.
 *
 * Archival is reversible by design: setting [archivedAt] makes the row
 * read-only at the service layer (every mutation API returns
 * `ARCHIVED`), and clearing it re-enables writes. R1 explicitly
 * disallows cascading un-archival of children — programs and projects
 * inherit read-only status while their parent is archived but must be
 * un-archived individually to come back online, so reactivating a
 * portfolio never silently flips a program live again.
 *
 * @property key short uppercase code (2–10 chars), unique across all
 *               portfolios. Used as a stable human handle in URLs and
 *               admin tooling.
 * @property ownerProfileId the profile that owns this portfolio at the
 *                          delivery-leadership level. References
 *                          `core-profile`; resolved to a `Profile` in
 *                          GraphQL through a DataLoader.
 */
@BatchKey("id")
@Serializable
data class Portfolio(
    @Contextual
    override val id: UUID = UUID.NIL,
    val key: String,
    val name: String,
    val description: String? = null,
    @ColumnName("owner_profile_id")
    @Contextual
    val ownerProfileId: UUID,
    override val public: Boolean = false,
    @ColumnName("public_content")
    override val publicContent: Boolean = false,
    @ColumnName("public_list")
    override val publicList: Boolean = false,
    @ColumnName("public_supplementary")
    override val publicSupplementary: Boolean = false,
    @ColumnName("archived_at")
    @Contextual
    val archivedAt: OffsetDateTime? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    /**
     * Optimistic-locking version. Every mutation includes the
     * client-observed value in its `WHERE` clause and bumps it on
     * success; mismatch surfaces as `OPTIMISTIC_LOCK_FAILED` per the
     * Excellence Bar non-negotiable in the spec.
     */
    val version: Long = 0,
) : PermissibleEntity<UUID> {

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = archivedAt != null
}

/**
 * Input shape for [Portfolio] create / update mutations. The
 * server-controlled fields ([Portfolio.id], audit timestamps, version)
 * are not exposed; archival uses a dedicated mutation rather than
 * threading through the create / edit path so the audit log captures
 * the action verb cleanly.
 */
@Serializable
data class PortfolioInput(
    val key: String,
    val name: String,
    val description: String? = null,
    @Contextual
    val ownerProfileId: UUID,
)
