package bosca.recommendations.service

import bosca.category.model.Category
import bosca.content.metadata.service.MetadataService
import bosca.profile.rating.model.ProfileRating
import bosca.recommendations.model.Recommendation
import bosca.serialization.UUID
import java.time.Duration

/**
 * Assembles a final ranked list of recommendations from multiple strategy outputs.
 * Performs deduplication, freshness boosting, **rating-aware personalization**, category
 * diversity enforcement, dismissal filtering, an optional **care-gate score floor**, and final
 * ranking to produce a high-quality set of recommendations for a request.
 */
class RecommendationAssembler(
    private val metadataService: MetadataService,
) {

    /**
     * Processes raw recommendations from one or more strategies into a final ranked list. Stages, in
     * order: dismissal filter → dedup (highest score per item) → freshness boost → rating-aware re-rank
     * (content-based: tilt scores by the profile's affinity for each candidate's categories) →
     * category diversity cap → sort → care-gate floor (drop sub-threshold items) → take(limit).
     *
     * @param ratings the profile's ratings; drives the content-based re-rank (empty = no personalization).
     * @param careFloor minimum final score to serve; candidates below it are dropped (0 = gate off).
     */
    suspend fun assemble(
        recommendations: List<Recommendation>,
        dismissedMetadataIds: Set<UUID>,
        dismissedCollectionIds: Set<UUID>,
        limit: Int,
        ratings: List<ProfileRating> = emptyList(),
        careFloor: Double = 0.0,
        maxPerCategory: Int = 3,
        freshnessDecayRate: Double = 0.05,
    ): List<Recommendation> {
        if (recommendations.isEmpty()) return emptyList()

        val filtered = filterDismissed(recommendations, dismissedMetadataIds, dismissedCollectionIds)
        val deduplicated = deduplicate(filtered)
        // Candidate categories are fetched once and reused by both the re-rank and the diversity cap.
        val categoriesByMetadata = fetchCategories(deduplicated.mapNotNull { it.metadataId }.distinct())
        val boosted = applyFreshnessBoost(deduplicated, freshnessDecayRate)
        val personalized = applyRatingReRank(boosted, ratings, categoriesByMetadata)
        val diversified = applyCategoryDiversityCap(personalized, maxPerCategory, categoriesByMetadata)

        return diversified
            .sortedByDescending { it.score }
            .filter { it.score >= careFloor }
            .take(limit)
    }

    /** Removes recommendations whose content has been dismissed by the user. */
    private fun filterDismissed(
        recommendations: List<Recommendation>,
        dismissedMetadataIds: Set<UUID>,
        dismissedCollectionIds: Set<UUID>,
    ): List<Recommendation> {
        return recommendations.filter { rec ->
            val metadataDismissed = rec.metadataId != null && rec.metadataId in dismissedMetadataIds
            val collectionDismissed = rec.collectionId != null && rec.collectionId in dismissedCollectionIds
            !metadataDismissed && !collectionDismissed
        }
    }

    /**
     * Deduplicates recommendations by content identity (metadataId or collectionId), retaining only the
     * highest-scoring entry for each content item.
     */
    private fun deduplicate(recommendations: List<Recommendation>): List<Recommendation> {
        val bestByKey = LinkedHashMap<Any, Recommendation>()
        for (rec in recommendations) {
            val metaId = rec.metadataId
            val collId = rec.collectionId
            val key: Any = when {
                metaId != null -> MetadataKey(metaId)
                collId != null -> CollectionKey(collId)
                else -> continue
            }
            val existing = bestByKey[key]
            if (existing == null) {
                bestByKey[key] = rec
                continue
            }
            val winner = if (rec.score > existing.score) rec else existing
            bestByKey[key] = winner.copy(sources = existing.sources + rec.sources)
        }
        return bestByKey.values.toList()
    }

    /**
     * Adjusts each recommendation's score with a time-decay freshness boost so recently created
     * recommendations surface above stale entries with similar base scores.
     */
    private fun applyFreshnessBoost(
        recommendations: List<Recommendation>,
        decayRate: Double,
    ): List<Recommendation> {
        val now = java.time.OffsetDateTime.now()
        return recommendations.map { rec ->
            val daysSinceCreated = Duration.between(rec.created, now).toHours() / 24.0
            val boost = 1.0 / (1.0 + daysSinceCreated.coerceAtLeast(0.0) * decayRate)
            rec.copy(score = rec.score * boost)
        }
    }

    /**
     * Content-based, rating-aware re-rank. The profile's ratings define a per-category affinity
     * (mean of `rating − neutral` over the categories of the items they rated); each candidate's score
     * is then tilted by the average affinity of its own categories — liked categories boost, disliked
     * categories demote. The tilt is bounded by [RATING_WEIGHT] so personalization shapes, but never
     * fully overrides, the base relevance score. No ratings (or no category overlap) = no change.
     */
    private suspend fun applyRatingReRank(
        recommendations: List<Recommendation>,
        ratings: List<ProfileRating>,
        categoriesByMetadata: Map<UUID, List<Category>>,
    ): List<Recommendation> {
        if (ratings.isEmpty()) return recommendations
        val affinity = computeCategoryAffinity(ratings)
        if (affinity.isEmpty()) return recommendations

        return recommendations.map { rec ->
            // Model scores already include the captured rating influence.
            if (rec.inference != null) return@map rec
            val metadataId = rec.metadataId ?: return@map rec
            val preferences = categoriesByMetadata[metadataId].orEmpty().mapNotNull { affinity[it.id] }
            if (preferences.isEmpty()) return@map rec
            val normalized = (preferences.average() / MAX_DEVIATION).coerceIn(-1.0, 1.0)
            rec.copy(score = rec.score * (1.0 + RATING_WEIGHT * normalized))
        }
    }

    /** Per-category affinity from the profile's ratings: mean of `rating − neutral` over rated items' categories. */
    private suspend fun computeCategoryAffinity(ratings: List<ProfileRating>): Map<UUID, Double> {
        val sums = HashMap<UUID, Double>()
        val counts = HashMap<UUID, Int>()
        for (rating in ratings) {
            val metadataId = rating.metadataId ?: continue
            val delta = (rating.rating - NEUTRAL_RATING).toDouble()
            for (category in metadataService.getCategories(metadataId)) {
                sums[category.id] = (sums[category.id] ?: 0.0) + delta
                counts[category.id] = (counts[category.id] ?: 0) + 1
            }
        }
        return sums.mapValues { (id, sum) -> sum / (counts[id] ?: 1) }
    }

    /**
     * Enforces a per-category diversity cap so no single category dominates. Items are processed in
     * score order; once a category reaches its cap, later items in that category are excluded. Items
     * without categories (incl. collection-only recommendations) are always included.
     */
    private fun applyCategoryDiversityCap(
        recommendations: List<Recommendation>,
        maxPerCategory: Int,
        categoriesByMetadata: Map<UUID, List<Category>>,
    ): List<Recommendation> {
        val sorted = recommendations.sortedByDescending { it.score }
        val categoryCount = HashMap<UUID, Int>()
        val result = mutableListOf<Recommendation>()

        for (rec in sorted) {
            val metadataId = rec.metadataId
            if (metadataId == null) {
                result.add(rec)
                continue
            }
            val categories = categoriesByMetadata[metadataId].orEmpty()
            if (categories.isEmpty()) {
                result.add(rec)
                continue
            }
            val allUnderCap = categories.all { (categoryCount[it.id] ?: 0) < maxPerCategory }
            if (allUnderCap) {
                result.add(rec)
                for (category in categories) {
                    categoryCount[category.id] = (categoryCount[category.id] ?: 0) + 1
                }
            }
        }
        return result
    }

    private suspend fun fetchCategories(metadataIds: List<UUID>): Map<UUID, List<Category>> {
        val map = LinkedHashMap<UUID, List<Category>>()
        for (id in metadataIds) map[id] = metadataService.getCategories(id)
        return map
    }

    private data class MetadataKey(val metadataId: UUID)
    private data class CollectionKey(val collectionId: UUID)

    private companion object {
        /** Midpoint of the 1–5 rating scale — ratings above it are positive affinity, below it negative. */
        const val NEUTRAL_RATING = 3

        /** Max |rating − neutral|, used to normalize affinity into [-1, 1]. */
        const val MAX_DEVIATION = 2.0

        /** Personalization tilt: at full (+/-1) affinity a score is scaled by 1 ± this. Tunable. */
        const val RATING_WEIGHT = 0.5
    }
}
