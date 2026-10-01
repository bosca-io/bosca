package bosca.recommendations.model

import kotlinx.serialization.Serializable

/**
 * Lifecycle status of a recommendation strategy, controlling
 * whether it actively contributes to recommendation generation.
 *
 * The constant names are the serialized form (matching the uppercase GraphQL enum values);
 * the SQL [bosca.db.mapper.EnumMapper] lowercases on bind and uppercases on read for the
 * `recommendations.strategy_status` Postgres enum, so no `@SerialName` is used here.
 */
@Serializable
enum class RecommendationStrategyStatus {
    /**
     * The strategy is being configured and does not yet
     * produce recommendations.
     */
    DRAFT,

    /**
     * The strategy is live and actively generating
     * recommendations for matching profiles.
     */
    ACTIVE,

    /**
     * The strategy was previously active but has been
     * temporarily suspended.
     */
    PAUSED,

    /**
     * The strategy is retired and no longer produces
     * recommendations.
     */
    ARCHIVED,
}
