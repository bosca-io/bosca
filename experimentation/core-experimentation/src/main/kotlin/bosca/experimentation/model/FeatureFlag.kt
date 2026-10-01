package bosca.experimentation.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Categorizes feature flags by the type of value their variations resolve to,
 * which determines what JSON shapes are accepted by [FlagValueValidator].
 */
@Serializable
enum class FlagType {
    BOOLEAN,
    PERCENTAGE,
    STRING,
    JSON
}

/**
 * Lifecycle status of a feature flag, controlling whether it is evaluated
 * for client requests.
 *
 *  - [DRAFT]    — being built; not yet evaluated.
 *  - [ENABLED]  — evaluating normally per its targeting rules.
 *  - [DISABLED] — turned off; everyone gets the default variation.
 *  - [ARCHIVED] — retired; hidden from the main UI.
 */
@Serializable
enum class FlagStatus {
    DRAFT,
    ENABLED,
    DISABLED,
    ARCHIVED
}

/**
 * A feature flag that controls the availability of a feature or experience across
 * server and client applications.
 *
 * Flags own a palette of [variations] (the candidate values they can return) and a
 * list of [targetingRules] that map user contexts to variation rollouts. Evaluation
 * picks a variation by walking the rules and bucketing the user; if no rule matches,
 * the [defaultVariationKey] determines the fallback.
 *
 * Variants and segment lists are no longer carried on flags or experiments — the
 * variation palette is the single source of truth for "what values can this flag return"
 * and rule conditions are the single source of truth for "who gets which variation."
 *
 * @property variations palette of candidate values; rule rollouts reference these by key
 * @property defaultVariationKey the variation served when no targeting rule matches
 * @property targetingRules JSON-stored list of [TargetingRule] objects evaluated top-to-bottom
 * @property salt random per-flag string mixed into bucket-assignment hashes; regenerate to reshuffle
 */
@BatchKey("id")
@Serializable
data class FeatureFlag(
    @Contextual
    val id: UUID = UUID.NIL,
    val key: String,
    val name: String,
    val description: String = "",
    val type: FlagType = FlagType.BOOLEAN,
    val status: FlagStatus = FlagStatus.DRAFT,
    @ColumnName("variations")
    @Contextual
    val variations: JsonElement,
    @ColumnName("default_variation_key")
    val defaultVariationKey: String,
    @ColumnName("targeting_rules")
    @Contextual
    val targetingRules: JsonElement? = null,
    val salt: String = "",
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now()
)

/**
 * Input for creating or updating a feature flag definition.
 *
 * Variations are passed as a JSON array of [Variation] objects; the service validates
 * that each variation's value matches the flag [type] before persisting.
 */
@Serializable
data class FeatureFlagInput(
    val key: String,
    val name: String,
    val description: String? = null,
    val type: FlagType = FlagType.BOOLEAN,
    val variations: JsonElement,
    val defaultVariationKey: String,
    val targetingRules: JsonElement? = null,
    val status: FlagStatus? = null
)
