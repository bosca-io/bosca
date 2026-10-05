package bosca.experimentation.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Records the deterministic assignment of a user or device to a variation
 * within an experiment's attached targeting rule rollout.
 *
 * Assignments are persisted so users receive the same variation across sessions.
 * The [variationKey] is the [Variation.key] from the parent flag's variation palette,
 * not a foreign key to a separate variants table — variations belong to the flag,
 * not to the experiment.
 *
 * New assignments are always written with [installationId]. [principalId] is
 * attached when the installation is authenticated. The nullable installation
 * property remains so assignment rows written by older releases can still be
 * read and attributed while they age out of active experiments.
 */
@BatchKey("id")
@Serializable
data class Assignment(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("experiment_id")
    @Contextual
    val experimentId: UUID,
    @ColumnName("variation_key")
    val variationKey: String,
    @ColumnName("principal_id")
    @Contextual
    val principalId: UUID? = null,
    @ColumnName("installation_id")
    val installationId: String? = null,
    @ColumnName("assigned_at")
    @Contextual
    val assignedAt: OffsetDateTime = OffsetDateTime.now()
) {
    init {
        require(principalId != null || installationId != null) {
            "Assignment must have either a principalId or an installationId"
        }
        require(variationKey.isNotBlank()) { "Assignment variationKey must not be blank" }
    }
}
