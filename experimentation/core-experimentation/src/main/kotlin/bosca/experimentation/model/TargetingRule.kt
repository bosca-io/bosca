package bosca.experimentation.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Operators available for attribute-based conditions (profile and device attributes).
 *
 * The semantics of each operator depend on the JSON type of the condition's expected value:
 * - String operators ([CONTAINS], [STARTS_WITH], [ENDS_WITH]) coerce both sides to string.
 * - Numeric operators ([GREATER_THAN], [LESS_THAN], [GTE], [LTE]) require both sides to be
 *   parseable as doubles; otherwise the condition evaluates to false.
 * - Semver operators ([SEMVER_GT], [SEMVER_LT], [SEMVER_GTE], [SEMVER_LTE]) parse both sides
 *   as dotted version strings; missing segments are treated as zero.
 * - [IN] and [NOT_IN] require the condition's expected value to be a JSON array and match
 *   against string equality.
 */
@Serializable
enum class AttrOperator {
    EQUALS,
    NOT_EQUALS,
    IN,
    NOT_IN,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    GREATER_THAN,
    LESS_THAN,
    GTE,
    LTE,
    SEMVER_GT,
    SEMVER_LT,
    SEMVER_GTE,
    SEMVER_LTE
}

/**
 * A single condition evaluated as part of a [TargetingRule]. All conditions within a rule
 * must match for the rule to apply ("AND" semantics). Conditions form a sealed hierarchy
 * so new types can be added without breaking existing serialized rules.
 *
 * All conditions support [negate] to invert the match result, which is simpler than
 * providing a separate NOT_X operator for every condition type.
 */
@Serializable
sealed class Condition {
    abstract val negate: Boolean

    /**
     * Matches when the authenticated principal is a member of the specified segment.
     * Evaluates to false for anonymous users.
     */
    @Serializable
    @SerialName("Segment")
    data class Segment(
        @Contextual
        val segmentId: UUID,
        override val negate: Boolean = false
    ) : Condition()

    /**
     * Matches when the authenticated principal's ID is present in [principalIds].
     * Evaluates to false for anonymous users.
     */
    @Serializable
    @SerialName("Principal")
    data class Principal(
        val principalIds: List<@Contextual UUID>,
        override val negate: Boolean = false
    ) : Condition()

    /**
     * Matches a value inside a profile attribute's JSON object. Profile attributes are
     * fetched server-side via ProfileService using the principal ID; this condition
     * evaluates to false for anonymous users.
     */
    @Serializable
    @SerialName("ProfileAttribute")
    data class ProfileAttribute(
        val typeId: String,
        val key: String,
        val operator: AttrOperator,
        @Contextual
        val value: JsonElement,
        override val negate: Boolean = false
    ) : Condition()

    /**
     * Matches a value in the device attribute map sent by the client during evaluation.
     * Device attributes are not stored server-side; the client is the source of truth for
     * platform, OS version, app version, locale, and any custom attributes.
     */
    @Serializable
    @SerialName("DeviceAttribute")
    data class DeviceAttribute(
        val key: String,
        val operator: AttrOperator,
        @Contextual
        val value: JsonElement,
        override val negate: Boolean = false
    ) : Condition()

    /**
     * Matches when another feature flag, resolved recursively within the
     * same evaluation context, returns a specific variation.
     *
     * This is the "prerequisite" / "dependent flag" condition — it lets
     * teams stage releases of dependent features without duplicating the
     * target conditions. For example, a checkout-redesign flag can depend
     * on a `new-cart` flag being `on` without copying the segment and
     * device conditions of the cart flag's own targeting rules.
     *
     * **Semantics**:
     *  - The referenced flag is evaluated via the same recursive code path
     *    used for the top-level evaluation, sharing the evaluation context
     *    (profile attributes, segment membership, device snapshot).
     *  - The condition matches when the referenced flag's resolved
     *    variation key equals [requiredVariationKey].
     *  - If the referenced flag does not exist, is `DISABLED`, or is
     *    `ARCHIVED`, the condition evaluates to `false` (the required
     *    variation is not "being served"). [negate] inverts this as
     *    usual.
     *  - Cycles (A → B → A) are detected at runtime via a visited-set
     *    threaded through the evaluation context. A cycle returns
     *    `false` with a logged error — flag evaluation is a hot path
     *    that must never throw on a misconfiguration.
     */
    @Serializable
    @SerialName("FlagDependency")
    data class FlagDependency(
        val flagKey: String,
        val requiredVariationKey: String,
        override val negate: Boolean = false,
    ) : Condition()
}

/**
 * One slice of a rule's rollout: a reference to a flag variation and a relative weight.
 *
 * Weights are unitless and combined across all entries in a rule's rollout to compute
 * proportions. A rollout of `[("on", 1), ("off", 1)]` is a 50/50 split; `[("on", 9), ("off", 1)]`
 * is 90/10. There is no constraint that weights must sum to 100 — they are normalized
 * by total weight at evaluation time.
 *
 * @property variationKey the [Variation.key] this slice serves to bucketed users
 * @property weight relative traffic share within the rollout (must be non-negative). A zero
 *           weight retains an experiment arm's topology while serving it no new traffic.
 */
@Serializable
data class VariationWeight(
    val variationKey: String,
    val weight: Int = 1
) {
    init {
        require(weight >= 0) { "Variation weight must be non-negative, got $weight" }
        require(variationKey.isNotBlank()) { "Variation key must not be blank" }
    }
}

/**
 * Rollout configuration applied after a rule's conditions match.
 *
 * The [variationWeights] list defines a weighted split across one or more flag variations.
 * Users matching the rule are deterministically bucketed into one of these variations using
 * stable hashing of (flagKey + salt + ruleId + userId). With a single entry, the rollout
 * is effectively "everyone matching the rule gets this variation" — the simplest case.
 *
 * Bucketing uses contiguous bucket ranges sorted by [VariationWeight.variationKey] alphabetically,
 * which is the property that lets gradual rollouts widen smoothly: increasing a variation's weight
 * absorbs users from adjacent ranges rather than reshuffling.
 */
@Serializable
data class Rollout(
    val variationWeights: List<VariationWeight>
) {
    init {
        require(variationWeights.isNotEmpty()) { "Rollout must have at least one variation weight" }
        require(variationWeights.sumOf { it.weight.toLong() } > 0L) {
            "Rollout must have a positive total weight"
        }
    }
}

/**
 * A single targeting rule within a feature flag's ordered rule set.
 *
 * Rules are evaluated top-to-bottom. For a rule to match, all of its [conditions] must
 * evaluate to true (AND semantics). The first matching rule wins; if no rule matches,
 * the flag's default variation is returned.
 *
 * The [id] is a stable identifier used by experiments that attach to this rule and by
 * the bucket-assignment hash. Reordering rules in the UI does not change their ids,
 * so existing experiment attachments and bucket assignments stay consistent across
 * rule list edits.
 *
 * The [name] and [description] are optional human-readable labels shown in the admin
 * UI to help operators distinguish rules at a glance ("EU rollout", "Beta cohort",
 * etc.). They are stored as additional keys in the same `feature_flags.targeting_rules`
 * JSONB column, so adding them does not require a migration; rules persisted before
 * these fields existed simply read back as null.
 *
 * @property id stable rule identifier (UUID string), set on creation
 * @property name optional human-readable label for the rule
 * @property description optional longer explanation of the rule's intent
 * @property conditions all must match for the rule to apply
 * @property rollout how matched users are split across variations
 */
@Serializable
data class TargetingRule(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val conditions: List<Condition> = emptyList(),
    val rollout: Rollout
)
