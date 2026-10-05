package bosca.recommendations.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A training snapshot and its matching content/personalized exports for one context. */
@Serializable
data class RecommendationContextModel(
    val version: Long = 0,
    @ColumnName("context_id") @Contextual val contextId: UUID,
    val revision: Long,
    @ColumnName("selection_revision") val selectionRevision: Long,
    @property:DbMapper(JsonbMapper::class) val context: RecommendationContext,
    val status: RecommendationTrainingStatus = RecommendationTrainingStatus.QUEUED,
    val exported: Boolean = false,
    val personalized: Boolean = false,
    val pinned: Boolean = false,
    val failure: String? = null,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val started: OffsetDateTime? = null,
    @Contextual val completed: OffsetDateTime? = null,
) {
    /** Stable artifact name for the context's content export. */
    val contentModelName: String get() = "recommender-$contextId-content"
    /** Stable artifact name for the context's learned export. */
    val personalizedModelName: String get() = "recommender-$contextId-personalized"
}

/** Training includes export and load validation before completion. */
@Serializable
enum class RecommendationTrainingStatus { QUEUED, RUNNING, COMPLETED, FAILED }
