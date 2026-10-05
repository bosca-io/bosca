package bosca.experimentation.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Role a [ConversionGoal] plays in an experiment's verdict logic.
 *
 * The analyzer treats goals asymmetrically based on their role so operators
 * can distinguish "things I am trying to improve" from "things I must not
 * break". Without this distinction, any regression on any goal would halt
 * a rollout — including regressions on secondary goals the operator was
 * only monitoring, not targeting, which is noisy and punishes breadth of
 * instrumentation.
 *
 * The role also drives [bosca.experimentation.jobs.runDeterministicAnalysis]'s
 * new [bosca.experimentation.jobs.Verdict.HALT] branch, which is how the
 * Phase 2 rollout controller knows it is safe to push an adaptive rollout
 * forward or must immediately revert it.
 */
@DbMapper(ConversionGoalRoleMapper::class)
@Serializable
enum class ConversionGoalRole {
    /**
     * The metric the experiment is trying to move. A statistically
     * significant regression on a primary goal produces
     * [bosca.experimentation.jobs.Verdict.DO_NOT_SHIP]; a significant
     * positive lift on any primary is the basis for SHIP recommendations.
     */
    PRIMARY,

    /**
     * A metric the operator is monitoring for context but not targeting.
     * Movement on a secondary goal does not by itself drive SHIP or
     * DO_NOT_SHIP — a significant regression maps to
     * [bosca.experimentation.jobs.Verdict.INCONCLUSIVE] so the operator
     * is aware of the tradeoff without the rollout being halted.
     */
    SECONDARY,

    /**
     * A "do not break" metric (error rate, page load, refund rate, etc.).
     * A statistically significant regression on a guardrail immediately
     * produces [bosca.experimentation.jobs.Verdict.HALT], which the
     * rollout controller interprets as "set treatment weight to 0 and
     * pause the experiment". Promoting a winner on another goal will
     * not override a guardrail regression.
     */
    GUARDRAIL,
}

/**
 * Persists [ConversionGoalRole] as the Postgres
 * `experimentation.conversion_goal_role` enum declared by the Phase 1
 * migration. The default [EnumMapper] round-trips the Kotlin enum via
 * `lowercase()` / `uppercase()`, which matches the Postgres enum's
 * all-lowercase labels.
 */
object ConversionGoalRoleMapper : EnumMapper<ConversionGoalRole>({ ConversionGoalRole.valueOf(it.uppercase()) })
