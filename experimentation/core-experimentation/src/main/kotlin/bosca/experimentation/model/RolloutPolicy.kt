package bosca.experimentation.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * How the rollout controller (the job chained from an experiment's
 * analysis) should react to [DeterministicAnalysis] verdicts and to the
 * passage of time.
 *
 * Four modes span the reasonable combinations of "time-driven" and
 * "data-driven" progressive delivery:
 *
 *  - [MANUAL]               — controller only acts on HALT. Operator
 *                             owns every promotion. Useful when the
 *                             team wants automation to cover the
 *                             guardrail side but make every step up a
 *                             deliberate decision.
 *  - [SCHEDULED_STEPS]      — time-driven step list. Advances to each
 *                             next step after `afterDuration` has
 *                             elapsed, regardless of the verdict,
 *                             UNLESS a guardrail HALT has been
 *                             recorded — in which case the wake-up
 *                             halts instead.
 *  - [ADAPTIVE_STEPS]       — data-driven step list. Advances by one
 *                             step when the latest analysis verdict is
 *                             SHIP; holds on KEEP_RUNNING /
 *                             INCONCLUSIVE / DO_NOT_SHIP; halts on
 *                             HALT.
 *  - [ADAPTIVE_CONTINUOUS]  — data-driven ramp. Each SHIP verdict adds
 *                             [RolloutPolicy.incrementPercent]
 *                             percentage points to the treatment
 *                             weight (capped at 100). Completes when
 *                             the weight reaches 100.
 */
@Serializable
enum class RolloutPolicyMode {
    MANUAL,
    SCHEDULED_STEPS,
    ADAPTIVE_STEPS,
    ADAPTIVE_CONTINUOUS,
}

/**
 * One stop on a scheduled or adaptive step list.
 *
 * @property weightPercent the treatment-variation weight to apply
 *           when the controller reaches this step (0..100).
 * @property afterDuration ISO-8601 duration string (e.g. `P1D`,
 *           `PT6H`). Only meaningful in [RolloutPolicyMode.SCHEDULED_STEPS];
 *           ignored in adaptive modes. Parsed with
 *           `java.time.Duration.parse` at job-scheduling time.
 */
@Serializable
data class RolloutStep(
    val weightPercent: Int,
    val afterDuration: String? = null,
) {
    init {
        require(weightPercent in 0..100) {
            "Rollout step weight must be 0..100, got $weightPercent"
        }
    }
}

/**
 * Controller configuration attached to an experiment.
 *
 * Stored as a JSONB blob on `experiments.rollout_policy` so the policy
 * can evolve (new modes, new knobs) without a migration per change
 * — see requirements.md D2. Round-tripping through the DB is handled
 * at the service boundary by (de)serializing this data class to/from
 * the [kotlinx.serialization.json.JsonElement] the repository binds.
 *
 * All thresholds are configurable per experiment so operators can
 * run guardrail-heavy releases with a tighter halt bar while keeping
 * the promotion bar at the standard 95%, or vice versa.
 *
 * @property mode how the controller chooses its next action.
 * @property treatmentVariationKey the variation the policy ramps
 *           toward. The controller reads the attached rule's current
 *           weights, identifies this key, and grows it. Required
 *           because a multi-arm rollout needs an explicit "which one
 *           are we promoting" signal — picking "the non-control arm"
 *           heuristically is not correct for 3+ way experiments.
 * @property steps step list for [RolloutPolicyMode.SCHEDULED_STEPS]
 *           and [RolloutPolicyMode.ADAPTIVE_STEPS]. Ignored by other
 *           modes. Weights must be monotonically non-decreasing;
 *           the final step is typically `100`.
 * @property incrementPercent percentage-point increase applied on
 *           each successful analysis cycle in
 *           [RolloutPolicyMode.ADAPTIVE_CONTINUOUS]. Must be > 0.
 * @property minConfidence minimum `(1 - effectiveAlpha)` (frequentist)
 *           or `probabilityBeatsControl` (Bayesian, Phase 4) required
 *           to treat a primary goal as a winner. Default 0.95.
 * @property guardrailThreshold the confidence / posterior probability
 *           bar for calling a guardrail regressed. Kept separate from
 *           [minConfidence] because operators commonly want a looser
 *           bar for halting than for promoting. Default 0.95.
 * @property guardrailMinRegressionPercent practical-significance
 *           floor for guardrail regressions (minimum magnitude of
 *           negative lift required to consider a guardrail a LOSER
 *           and trip HALT). Default 0.5%.
 * @property haltOnGuardrail when true (the default), a HALT verdict
 *           immediately sets the treatment weight to 0 and moves the
 *           experiment to PAUSED. When false, the controller records a
 *           held event naming the guardrail but leaves the rollout
 *           untouched — useful for dry-run policies.
 */
@Serializable
data class RolloutPolicy(
    val mode: RolloutPolicyMode,
    val treatmentVariationKey: String,
    val steps: List<RolloutStep>? = null,
    val incrementPercent: Double? = null,
    val minConfidence: Double = 0.95,
    val guardrailThreshold: Double = 0.95,
    val guardrailMinRegressionPercent: Double = 0.5,
    val haltOnGuardrail: Boolean = true,
) {
    init {
        require(treatmentVariationKey.isNotBlank()) {
            "treatmentVariationKey must not be blank"
        }
        require(minConfidence in 0.0..1.0) {
            "minConfidence must be in [0, 1], got $minConfidence"
        }
        require(guardrailThreshold in 0.0..1.0) {
            "guardrailThreshold must be in [0, 1], got $guardrailThreshold"
        }
        require(guardrailMinRegressionPercent >= 0.0) {
            "guardrailMinRegressionPercent must be non-negative, got $guardrailMinRegressionPercent"
        }
        when (mode) {
            RolloutPolicyMode.SCHEDULED_STEPS,
            RolloutPolicyMode.ADAPTIVE_STEPS -> {
                require(!steps.isNullOrEmpty()) {
                    "$mode requires a non-empty steps list"
                }
                // Steps must be monotonically non-decreasing — a
                // rollout that goes 10% → 25% → 5% is almost certainly
                // a misconfiguration (the intended shape is always a
                // staircase going up, possibly plateauing). Catching
                // this at validate time keeps the controller's
                // "advance to the next step" logic simple.
                val ws = steps.map { it.weightPercent }
                require(ws.zipWithNext().all { (a, b) -> a <= b }) {
                    "Rollout steps must be monotonically non-decreasing, got $ws"
                }
                if (mode == RolloutPolicyMode.SCHEDULED_STEPS) {
                    require(steps.all { it.afterDuration != null }) {
                        "SCHEDULED_STEPS requires afterDuration on every step"
                    }
                }
            }
            RolloutPolicyMode.ADAPTIVE_CONTINUOUS -> {
                require(incrementPercent != null && incrementPercent > 0.0) {
                    "ADAPTIVE_CONTINUOUS requires a positive incrementPercent"
                }
                require(incrementPercent <= 100.0) {
                    "ADAPTIVE_CONTINUOUS incrementPercent must be <= 100, got $incrementPercent"
                }
            }
            RolloutPolicyMode.MANUAL -> Unit
        }
    }
}

/**
 * Controller action recorded on `experimentation.rollout_policy_events`.
 *
 * Declared as its own Postgres enum (see the V2 migration) so the
 * default [EnumMapper] can round-trip the Kotlin enum constant via
 * `name.lowercase()`, matching the `GoalMetricType` / `ConversionGoalRole`
 * conventions. Add a new variant here AND a corresponding
 * `ALTER TYPE experimentation.rollout_policy_action ADD VALUE '...'`
 * migration — the enum and the Postgres type must stay in lock-step.
 */
@DbMapper(RolloutPolicyActionMapper::class)
@Serializable
enum class RolloutPolicyAction {
    /** Controller wrote new weights to the attached rule. */
    ADVANCED,

    /** Controller read the state, decided not to act, and recorded the reason. */
    HELD,

    /** Controller set treatment weight to 0 and paused the experiment. */
    HALTED,

    /** Controller applied the final ramp step and moved the experiment to COMPLETED. */
    COMPLETED,
}

object RolloutPolicyActionMapper :
    EnumMapper<RolloutPolicyAction>({ RolloutPolicyAction.valueOf(it.uppercase()) })
