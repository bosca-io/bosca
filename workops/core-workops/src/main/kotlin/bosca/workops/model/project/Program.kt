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
 * Middle tier of the delivery hierarchy (R1). A program belongs to
 * exactly one [Portfolio] and groups related projects under one
 * delivery owner. Cross-project work — milestones (R9), shared
 * components (R30), program-scope boards (R8), program-scope releases
 * (R30) — attaches to programs rather than to individual projects, so
 * a program is the natural rollup boundary for a delivery train.
 *
 * Per R1 every program carries a `key` that is unique within its
 * portfolio; the same key value may exist across portfolios. Project
 * keys (handled in [Project]) carry the global uniqueness instead
 * because they form the prefix of every task key.
 *
 * @property key portfolio-scoped uppercase code (2–10 chars).
 * @property startDate optional planned program start; surfaces on the
 *                     roadmap (R18) and on the program calendar (R31).
 * @property targetDate optional planned program end. Re-dating either
 *                      bound updates the program's calendar entry in
 *                      the same transaction (R31).
 */
@BatchKey("id")
@Serializable
data class Program(
    @Contextual
    override val id: UUID = UUID.NIL,
    @ColumnName("portfolio_id")
    @Contextual
    val portfolioId: UUID,
    val key: String,
    val name: String,
    val description: String? = null,
    @ColumnName("owner_profile_id")
    @Contextual
    val ownerProfileId: UUID,
    @ColumnName("start_date")
    @Contextual
    val startDate: OffsetDateTime? = null,
    @ColumnName("target_date")
    @Contextual
    val targetDate: OffsetDateTime? = null,
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
    val version: Long = 0,
) : PermissibleEntity<UUID> {

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = archivedAt != null
}

/** Input for [Program] create / update mutations. */
@Serializable
data class ProgramInput(
    @Contextual
    val portfolioId: UUID,
    val key: String,
    val name: String,
    val description: String? = null,
    @Contextual
    val ownerProfileId: UUID,
    @Contextual
    val startDate: OffsetDateTime? = null,
    @Contextual
    val targetDate: OffsetDateTime? = null,
)
