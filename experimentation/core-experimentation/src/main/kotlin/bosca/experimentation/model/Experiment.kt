package bosca.experimentation.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Lifecycle status of an experiment, governing whether the targeting rule it
 * is attached to records exposure events for analysis.
 */
@Serializable
enum class ExperimentStatus {
    DRAFT,
    RUNNING,
    PAUSED,
    COMPLETED,
    ARCHIVED
}

/**
 * An experiment is observation and analysis attached to a single targeting rule on a
 * feature flag. It does not control which value is served — that is the rule's
 * rollout. Instead, the experiment records which variation each user was bucketed
 * into and aggregates conversion metrics across the variations the rule splits.
 *
 * If [targetingRuleId] is null, the experiment is attached to the flag's default
 * variation path — meaning it observes users who don't match any rule and bucket
 * them across the rule rollout that the experiment defines on its parent flag.
 *
 * The "variants" of an experiment are simply the variations referenced by its
 * attached rule's rollout — there is no separate variant table.
 *
 * @property featureFlagId the flag this experiment observes
 * @property targetingRuleId the stable rule id (see [TargetingRule.id]) this experiment
 *           is attached to; null means the default variation path
 * @property controlVariationKey the explicitly selected baseline variation; every
 *           analysis and rollout comparison is anchored to this key
 * @property excludedPrincipalIds accounts whose assignments and analytics activity are
 *           omitted from experiment calculations while still receiving their variation
 * @property analysisRevision monotonic revision of analysis-relevant definition
 *           fields and conversion goals; lifecycle-only status changes do not advance it
 * @property exclusionLayerId optional mutual-exclusion layer for preventing conflicting tests
 */
@BatchKey("id")
@Serializable
data class Experiment(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("feature_flag_id")
    @Contextual
    val featureFlagId: UUID,
    val name: String,
    val description: String = "",
    val hypothesis: String = "",
    val status: ExperimentStatus = ExperimentStatus.DRAFT,
    @ColumnName("targeting_rule_id")
    val targetingRuleId: String? = null,
    @ColumnName("control_variation_key")
    val controlVariationKey: String,
    @ColumnName("excluded_principal_ids")
    val excludedPrincipalIds: List<@Contextual UUID> = emptyList(),
    /** Event filter that gates outcome eligibility; null keeps assignment-time eligibility. */
    @ColumnName("activation_filter")
    @property:DbMapper(JsonbMapper::class)
    val activationFilter: ExperimentActivationFilter? = null,
    @ColumnName("exclusion_layer_id")
    @Contextual
    val exclusionLayerId: UUID? = null,
    @ColumnName("start_date")
    @Contextual
    val startDate: OffsetDateTime? = null,
    @ColumnName("end_date")
    @Contextual
    val endDate: OffsetDateTime? = null,
    @ColumnName("target_sample_size")
    val targetSampleSize: Long? = null,
    /**
     * Serialized [RolloutPolicy] as stored in the `rollout_policy` JSONB
     * column. Null means "no controller action" — the experiment
     * behaves as it always has, with humans editing rollout weights by
     * hand. The service layer is responsible for (de)serializing this
     * JSON blob into the typed [RolloutPolicy] shape; the model carries
     * the raw element so the repository's jsonb binding stays generic,
     * matching the [AnalysisReport.aiInsights] convention.
     */
    @ColumnName("rollout_policy")
    @Contextual
    val rolloutPolicy: JsonElement? = null,
    /**
     * Statistical method used by [bosca.experimentation.jobs.runDeterministicAnalysis]
     * for this experiment. Frequentist is the default and keeps every
     * pre-Phase-4 experiment behaving exactly as before; Bayesian
     * switches the per-variation analysis to posterior-based
     * `probabilityBeatsControl` and `expectedLoss`.
     */
    @ColumnName("analysis_method")
    val analysisMethod: AnalysisMethod = AnalysisMethod.FREQUENTIST,
    /**
     * Serialized [BayesianPrior] persisted on the experiment. Null
     * means "use defaults" (uniform Beta(1,1), flat Normal); the
     * analyzer reads this field only when [analysisMethod] is
     * `BAYESIAN`.
     */
    @ColumnName("bayesian_prior")
    @Contextual
    val bayesianPrior: JsonElement? = null,
    @ColumnName("analysis_revision")
    val analysisRevision: Long = 0L,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now()
) {
    init {
        if (startDate != null && endDate != null) {
            require(!startDate.isAfter(endDate)) {
                "Experiment startDate must not be after endDate"
            }
        }
        require(targetSampleSize == null || targetSampleSize > 0) {
            "Target sample size must be positive, got $targetSampleSize"
        }
    }
}

/**
 * Input for creating or updating an experiment definition.
 *
 * Segment targeting is no longer carried on the experiment — it is inherited
 * from the targeting rule the experiment is attached to.
 */
@Serializable
data class ExperimentInput(
    @Contextual
    val featureFlagId: UUID,
    val name: String,
    val description: String? = null,
    val hypothesis: String? = null,
    val targetingRuleId: String? = null,
    /** Required baseline variation selected from the experiment's involved variations. */
    val controlVariationKey: String,
    /** Accounts omitted from every result calculation, or null to preserve the list on edit. */
    val excludedPrincipalIds: List<@Contextual UUID>? = null,
    /** Event filter that gates outcome eligibility. Null disables activation gating. */
    val activationFilter: ExperimentActivationFilter? = null,
    @Contextual
    val exclusionLayerId: UUID? = null,
    @Contextual
    val startDate: OffsetDateTime? = null,
    @Contextual
    val endDate: OffsetDateTime? = null,
    val targetSampleSize: Long? = null,
    /**
     * Typed policy supplied at create/edit time. The service layer
     * serializes this to JSON for the repository. Null clears any
     * existing policy.
     */
    val rolloutPolicy: RolloutPolicy? = null,
    /** Defaults to FREQUENTIST. */
    val analysisMethod: AnalysisMethod = AnalysisMethod.FREQUENTIST,
    /** Null = use sane defaults (uniform Beta, flat Normal). */
    val bayesianPrior: BayesianPrior? = null,
)
