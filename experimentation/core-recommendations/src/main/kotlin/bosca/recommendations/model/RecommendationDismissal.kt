package bosca.recommendations.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Records that a user has explicitly dismissed a recommended content item,
 * preventing it from appearing in future recommendations. Dismissals
 * reference either a metadata item or a collection, but not both.
 * This is a user preference, distinct from analytics interaction events
 * which track engagement behavior.
 */
@Serializable
data class RecommendationDismissal(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID? = null,
    @ColumnName("collection_id")
    @Contextual
    val collectionId: UUID? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
)
