package bosca.recommendations.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbConstructor
import bosca.db.annotation.Ignore
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A content recommendation candidate — a single content item (metadata or collection) produced by
 * a strategy and ranked by score so the highest-relevance items appear first. Candidates are
 * **not** tied to a profile: the materialized ones ([bosca.recommendations.model.RecommendationStrategyType.TRENDING]/
 * `SEGMENT_BASED`/`CURATED`) form a global pool keyed by strategy, and the read-time surfaces
 * (for-you feed, similar, co-engaged, recommended) return candidates that are inherently for the
 * caller. [sources] records every candidate-generation or model-ranking signal that contributed to the
 * result; it is carried through read-time processing and caches but is not persisted in the recommendation
 * table. Recommendations produced directly by the independently served content model use [UUID.NIL] for
 * [strategyId], because that model is not a persisted recommendation strategy. Each points to either a
 * metadata item or a collection, but not both. It optionally
 * includes a human-readable reason, contextual data about the scoring factors, and an expiration
 * timestamp after which it should no longer be served.
 */
@BatchKey(type = RecommendationBatchKey::class)
@Serializable
data class Recommendation(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID? = null,
    @ColumnName("collection_id")
    @Contextual
    val collectionId: UUID? = null,
    /**
     * The concrete base or variant language selected when a collection recommendation was read.
     * This preserves the representation whose recommendability was evaluated so API resolution does
     * not silently fall back to a different collection representation.
     */
    @ColumnName("collection_language_tag")
    val collectionLanguageTag: String? = null,
    @ColumnName("strategy_id")
    @Contextual
    val strategyId: UUID,
    val score: Double = 0.0,
    val reason: String? = null,
    @Contextual
    val context: JsonElement? = null,
    @ColumnName("expires_at")
    @Contextual
    val expiresAt: OffsetDateTime? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @property:Ignore
    val sources: Set<RecommendationSource> = emptySet(),
    /** Captured model inputs for demand-driven attribution; not a persisted recommendation-table field. */
    @property:Ignore
    val inference: RecommendationInference? = null,
) {
    /** Database row constructor; source attribution is attached by the serving path that reads the row. */
    @DbConstructor
    constructor(
        id: UUID,
        metadataId: UUID?,
        collectionId: UUID?,
        collectionLanguageTag: String?,
        strategyId: UUID,
        score: Double,
        reason: String?,
        context: JsonElement?,
        expiresAt: OffsetDateTime?,
        created: OffsetDateTime,
    ) : this(
        id = id,
        metadataId = metadataId,
        collectionId = collectionId,
        collectionLanguageTag = collectionLanguageTag,
        strategyId = strategyId,
        score = score,
        reason = reason,
        context = context,
        expiresAt = expiresAt,
        created = created,
        sources = emptySet(),
    )
}
