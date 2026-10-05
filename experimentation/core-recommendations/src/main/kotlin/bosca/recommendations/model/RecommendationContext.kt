package bosca.recommendations.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A named recommendation-use context and its saved content classifier.
 *
 * [type] identifies the context and is supplied by sites, applications, and other recommendation consumers.
 * The classifier assigns cached context tags to metadata and collections. Candidate generation and trained
 * model indexes use those tags before ranking, so specialized contexts can include raw assets without
 * allowing them into ordinary user-experience feeds.
 */
@Serializable
data class RecommendationContext(
    @Contextual
    val id: UUID = UUID.NIL,
    val type: String,
    val name: String,
    val description: String = "",
    @ColumnName("content_filter")
    @property:DbMapper(JsonbMapper::class)
    val contentFilter: RecommendationContentFilter = RecommendationContentFilter.DEFAULT,
    @property:DbMapper(JsonbMapper::class)
    val weights: RecommendationWeights = RecommendationWeights(),
    val revision: Long = 1,
    @ColumnName("selection_revision")
    val selectionRevision: Long = 0,
    @ColumnName("active_model_version")
    val activeModelVersion: Long? = null,
    @ColumnName("requested_model_version")
    val requestedModelVersion: Long? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
) {
    companion object {
        /** Context used whenever a recommendation request does not specify another type. */
        const val DEFAULT_TYPE = "default"
    }
}
