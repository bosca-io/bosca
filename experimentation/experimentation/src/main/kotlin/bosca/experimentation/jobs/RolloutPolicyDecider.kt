package bosca.experimentation.jobs

import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyAction
import bosca.experimentation.model.RolloutPolicyMode

/**
 * Pure decision logic for the rollout controller.
 *
 * The controller job (`RolloutPolicyJobExecutor`) is responsible for
 * loading the current state (experiment, policy, attached rule, latest
 * analysis), handing it to [decide], and applying whichever [Action]
 * comes back. Splitting the decision out of the executor keeps the
 * thing that matters — "what do we do given this state?" — testable
 * without a database or a job queue.
 *
 * Every call site of [decide] must pass the latest analysis verdict
 * available for the experiment; the decision depends on it for every
 * mode except [RolloutPolicyMode.SCHEDULED_STEPS], which is primarily
 * time-driven but still consults the verdict to honor a pending
 * guardrail HALT (the one time schedule mode is allowed to refuse to
 * advance).
 */

/**
 * Input to [decide]. Bundled into a single struct because the
 * executor assembles this from several repository calls and passing
 * the fields individually would make the decider signature
 * unreadable.
 *
 * @property policy the experiment's attached [RolloutPolicy]. Never
 *           null at the decider call site — the executor bails
 *           earlier when a non-policy experiment finishes analysis.
 * @property verdict the latest analysis verdict. Used by all adaptive
 *           modes and by [RolloutPolicyMode.SCHEDULED_STEPS] only to
 *           honor a HALT.
 * @property primaryConfidence the confidence attached to the primary
 *           goal's winner (frequentist `1 - p`, Bayesian
 *           `probabilityBeatsControl`). Used by adaptive modes to
 *           gate advancement on the policy's [RolloutPolicy.minConfidence].
 *           May be `null` when the verdict is not SHIP (the gate is
 *           only consulted for SHIP verdicts, so null is safe there).
 * @property currentWeights snapshot of the attached rule's current
 *           variation weights as the controller sees them.
 * @property controlVariationKey the experiment's persisted rollback target.
 * @property scheduledStepIndex for [RolloutPolicyMode.SCHEDULED_STEPS]
 *           wake-ups, the step index this wake-up was scheduled
 *           against. Null for adaptive-triggered wake-ups.
 */
data class RolloutDecisionInput(
    val policy: RolloutPolicy,
    val verdict: Verdict,
    val primaryConfidence: Double?,
    val currentWeights: Map<String, Int>,
    val controlVariationKey: String,
    val scheduledStepIndex: Int? = null,
)

/**
 * One of four outcomes from the decider.
 *
 * [Action.Advance] and [Action.Complete] both carry new weights and
 * produce a flag edit; they are distinct because [Action.Complete]
 * also moves the experiment to COMPLETED. [Action.Halt] carries new
 * weights (treatment → 0) and additionally pauses the experiment.
 * [Action.Hold] records a held event with the decider's reason but
 * does not mutate the flag.
 *
 * Every variant carries a [reason] string that ends up in the audit
 * event so operators can see why the controller did what it did
 * without replaying the whole analysis.
 */
sealed class Action {
    abstract val reason: String
    abstract val type: RolloutPolicyAction

    /** Write new weights, leave experiment status alone. */
    data class Advance(
        val newWeights: Map<String, Int>,
        override val reason: String,
    ) : Action() {
        override val type: RolloutPolicyAction = RolloutPolicyAction.ADVANCED
    }

    /** Write new weights AND move experiment to COMPLETED. */
    data class Complete(
        val newWeights: Map<String, Int>,
        override val reason: String,
    ) : Action() {
        override val type: RolloutPolicyAction = RolloutPolicyAction.COMPLETED
    }

    /** Set treatment weight to 0, bring control to 100, pause experiment. */
    data class Halt(
        val newWeights: Map<String, Int>,
        override val reason: String,
    ) : Action() {
        override val type: RolloutPolicyAction = RolloutPolicyAction.HALTED
    }

    /** No flag edit. Record the event for the audit trail. */
    data class Hold(
        override val reason: String,
    ) : Action() {
        override val type: RolloutPolicyAction = RolloutPolicyAction.HELD
    }
}

/**
 * Decide what the controller should do in response to the supplied
 * [RolloutDecisionInput].
 *
 * Decision table (see specs/experiments-v2/requirements.md R2):
 *
 * | Mode                  | SHIP             | HALT                      | KEEP_RUNNING | DO_NOT_SHIP | Other        |
 * |-----------------------|------------------|---------------------------|--------------|-------------|--------------|
 * | `MANUAL`              | hold             | halt (if `haltOnGuardrail`)| hold        | hold        | hold         |
 * | `SCHEDULED_STEPS`     | (time drives)    | halt (if `haltOnGuardrail`)| (time)      | (time)      | (time)       |
 * | `ADAPTIVE_STEPS`      | advance 1 step   | halt (if `haltOnGuardrail`)| hold        | hold        | hold         |
 * | `ADAPTIVE_CONTINUOUS` | +incrementPercent| halt (if `haltOnGuardrail`)| hold        | hold        | hold         |
 *
 * "(time drives)" means the scheduled mode is acting on a wake-up
 * whose step index is fixed — the verdict does not influence which
 * weight to write, only whether to halt.
 *
 * [Verdict.SRM_FAILED] always produces a [Action.Hold] with a
 * diagnostic reason. SRM is a "the numbers are bad" signal, not a
 * "halt the treatment" signal — the operator must investigate
 * bucketing before any rollout change is meaningful.
 */
fun decide(input: RolloutDecisionInput): Action {
    val policy = input.policy
    val verdict = input.verdict
    val currentWeights = input.currentWeights
    val treatmentKey = policy.treatmentVariationKey

    // SRM always holds. Any other action on broken bucketing would
    // be guessing, and the controller's audit trail must make clear
    // that the controller saw the problem and chose not to act.
    if (verdict == Verdict.SRM_FAILED) {
        return Action.Hold(reason = "verdict=SRM_FAILED: bucketing looks broken; controller will not act")
    }

    // HALT is handled uniformly across all modes. haltOnGuardrail
    // off turns this into a HELD event (dry-run policies).
    if (verdict == Verdict.HALT) {
        return if (policy.haltOnGuardrail) {
            val halted = haltWeights(currentWeights, treatmentKey, input.controlVariationKey)
            Action.Halt(newWeights = halted, reason = "verdict=HALT: guardrail tripped; treatment set to 0, experiment pausing")
        } else {
            Action.Hold(reason = "verdict=HALT but haltOnGuardrail=false; controller holding (dry-run policy)")
        }
    }

    return when (policy.mode) {
        RolloutPolicyMode.MANUAL -> Action.Hold(
            reason = "mode=MANUAL verdict=$verdict: awaiting operator promotion"
        )

        RolloutPolicyMode.SCHEDULED_STEPS -> decideScheduled(input, policy, treatmentKey, currentWeights)

        RolloutPolicyMode.ADAPTIVE_STEPS -> decideAdaptiveSteps(
            verdict = verdict,
            policy = policy,
            treatmentKey = treatmentKey,
            currentWeights = currentWeights,
            primaryConfidence = input.primaryConfidence,
        )

        RolloutPolicyMode.ADAPTIVE_CONTINUOUS -> decideAdaptiveContinuous(
            verdict = verdict,
            policy = policy,
            treatmentKey = treatmentKey,
            currentWeights = currentWeights,
            primaryConfidence = input.primaryConfidence,
        )
    }
}

private fun decideScheduled(
    input: RolloutDecisionInput,
    policy: RolloutPolicy,
    treatmentKey: String,
    currentWeights: Map<String, Int>,
): Action {
    // Scheduled mode only executes when the executor wakes it at a
    // specific scheduledStepIndex. An adaptive-style wake (analysis
    // chained) on a SCHEDULED_STEPS policy is a held no-op — the
    // time loop owns advancement.
    val idx = input.scheduledStepIndex ?: return Action.Hold(
        reason = "mode=SCHEDULED_STEPS: analysis-chained wake ignored; time loop owns advancement"
    )
    // The RolloutPolicy init block enforces non-empty `steps` for
    // SCHEDULED_STEPS mode, so a null here would mean the validator
    // was bypassed. Treat it as a held no-op rather than throwing
    // from inside the controller's hot path.
    val steps = policy.steps ?: return Action.Hold(
        reason = "mode=SCHEDULED_STEPS: policy has no steps list; ignoring wake"
    )
    if (idx !in steps.indices) {
        return Action.Hold(reason = "mode=SCHEDULED_STEPS: step index $idx out of range ${steps.indices}; ignoring stale wake")
    }
    val target = steps[idx].weightPercent
    val newWeights = rampToTarget(currentWeights, treatmentKey, target)
    val reason = "mode=SCHEDULED_STEPS step ${idx + 1}/${steps.size}: treatment → $target%"
    return if (target >= 100 || idx == steps.lastIndex) {
        Action.Complete(newWeights = newWeights, reason = "$reason (final step; experiment completing)")
    } else {
        Action.Advance(newWeights = newWeights, reason = reason)
    }
}

private fun decideAdaptiveSteps(
    verdict: Verdict,
    policy: RolloutPolicy,
    treatmentKey: String,
    currentWeights: Map<String, Int>,
    primaryConfidence: Double?,
): Action {
    if (verdict != Verdict.SHIP) {
        return Action.Hold(reason = "mode=ADAPTIVE_STEPS verdict=$verdict: holding at current weights")
    }
    // Winner must clear the per-experiment confidence bar. The
    // per-goal verdict engine already used its own `confidenceThreshold`
    // derived from alpha; this second check lets the policy enforce a
    // *different* bar than the analyzer — e.g. a team that wants to
    // require 0.99 before auto-advancing but still look at 0.95
    // reports visually.
    if (primaryConfidence == null || primaryConfidence < policy.minConfidence) {
        return Action.Hold(
            reason = "mode=ADAPTIVE_STEPS verdict=SHIP but confidence " +
                "${formatConfidence(primaryConfidence)} < policy minConfidence ${policy.minConfidence}"
        )
    }
    // Same null guard as the scheduled branch — the init block
    // enforces non-null for ADAPTIVE_STEPS but defending against
    // bypass keeps the controller's hot path crash-free.
    val steps = policy.steps ?: return Action.Hold(
        reason = "mode=ADAPTIVE_STEPS: policy has no steps list; holding"
    )
    val currentTreatmentPct = treatmentPercent(currentWeights, treatmentKey)
    // Find the first step strictly greater than the current treatment
    // weight. The step list is validated monotonically non-decreasing
    // at policy-construction time, so the sequence is correct.
    val nextIndex = steps.indexOfFirst { it.weightPercent > currentTreatmentPct }
    if (nextIndex == -1) {
        // Already at or past the last step. This is the "adaptive
        // advanced past the list" completion case — treat as Complete
        // even if the weights are already correct, so the executor
        // can flip the experiment to COMPLETED.
        val finalPct = steps.last().weightPercent
        val newWeights = rampToTarget(currentWeights, treatmentKey, finalPct)
        return Action.Complete(
            newWeights = newWeights,
            reason = "mode=ADAPTIVE_STEPS verdict=SHIP: already at or past final step $finalPct%; marking complete",
        )
    }
    val target = steps[nextIndex].weightPercent
    val newWeights = rampToTarget(currentWeights, treatmentKey, target)
    val reason = "mode=ADAPTIVE_STEPS verdict=SHIP confidence=${formatConfidence(primaryConfidence)}: " +
        "treatment $currentTreatmentPct% → $target% (step ${nextIndex + 1}/${steps.size})"
    return if (nextIndex == steps.lastIndex || target >= 100) {
        Action.Complete(newWeights = newWeights, reason = "$reason (final step)")
    } else {
        Action.Advance(newWeights = newWeights, reason = reason)
    }
}

private fun decideAdaptiveContinuous(
    verdict: Verdict,
    policy: RolloutPolicy,
    treatmentKey: String,
    currentWeights: Map<String, Int>,
    primaryConfidence: Double?,
): Action {
    if (verdict != Verdict.SHIP) {
        return Action.Hold(reason = "mode=ADAPTIVE_CONTINUOUS verdict=$verdict: holding at current weights")
    }
    if (primaryConfidence == null || primaryConfidence < policy.minConfidence) {
        return Action.Hold(
            reason = "mode=ADAPTIVE_CONTINUOUS verdict=SHIP but confidence " +
                "${formatConfidence(primaryConfidence)} < policy minConfidence ${policy.minConfidence}"
        )
    }
    val increment = policy.incrementPercent ?: return Action.Hold(
        reason = "mode=ADAPTIVE_CONTINUOUS: policy has no incrementPercent; holding"
    )
    val currentPct = treatmentPercent(currentWeights, treatmentKey).toDouble()
    val nextPct = (currentPct + increment).coerceAtMost(100.0)
    // Continuous ramp must make progress. If we are already at 100
    // there is nothing to do besides complete.
    if (currentPct >= 100.0) {
        return Action.Complete(
            newWeights = currentWeights,
            reason = "mode=ADAPTIVE_CONTINUOUS: already at 100%; marking complete",
        )
    }
    // Round to the nearest percent so the controller never writes
    // fractional weights (the rule schema is integer percents).
    val nextInt = nextPct.toInt().coerceAtMost(100)
    val newWeights = rampToTarget(currentWeights, treatmentKey, nextInt)
    val reason = "mode=ADAPTIVE_CONTINUOUS verdict=SHIP confidence=${formatConfidence(primaryConfidence)}: " +
        "treatment ${currentPct.toInt()}% → $nextInt% (+$increment%)"
    return if (nextInt >= 100) {
        Action.Complete(newWeights = newWeights, reason = "$reason (reached 100%)")
    } else {
        Action.Advance(newWeights = newWeights, reason = reason)
    }
}

// -----------------------------------------------------------------
// Weight helpers.
// -----------------------------------------------------------------

/**
 * Returns the treatment variation's weight as an integer percent of
 * the rollout's total. "Percent" here is a derived view of the
 * underlying unitless weights: with weights `{control=3, treatment=1}`
 * the treatment is at 25%. This matches how the admin UI renders a
 * rollout split and how the step list is expressed.
 */
internal fun treatmentPercent(weights: Map<String, Int>, treatmentKey: String): Int {
    val total = weights.values.sum()
    if (total <= 0) return 0
    val treatment = weights[treatmentKey] ?: 0
    return (treatment * 100) / total
}

/**
 * Build a new weight map that puts [treatmentKey] at
 * [targetPercent]% of the total and distributes the remaining
 * `(100 - targetPercent)%` across the non-treatment variations
 * proportionally to their current shares (falling back to an even
 * split when the non-treatment variations currently have zero
 * weight).
 *
 * Using relative shares (instead of just rescaling everything by
 * the same constant) preserves any hand-configured split between
 * multiple control-side variations — a policy that ramps one
 * treatment against two controls does not accidentally collapse
 * those two controls into one bucket.
 */
internal fun rampToTarget(weights: Map<String, Int>, treatmentKey: String, targetPercent: Int): Map<String, Int> {
    require(targetPercent in 0..100)
    val keys = weights.keys
    if (treatmentKey !in keys) {
        // Defensive — the caller already guarantees the treatment key
        // exists, but if the rule changed out from under us we'd
        // rather fall back to "treatment absent" than throw inside
        // the decider.
        return weights
    }
    if (keys.size == 1) return mapOf(treatmentKey to targetPercent)
    val others = keys.filter { it != treatmentKey }
    val otherTotal = others.sumOf { key ->
        weights[key] ?: error("Rollout weight disappeared for variation '$key'")
    }
    val remaining = 100 - targetPercent
    val newWeights = mutableMapOf<String, Int>()
    newWeights[treatmentKey] = targetPercent
    if (remaining == 0) {
        for (k in others) newWeights[k] = 0
        return newWeights
    }
    if (otherTotal == 0) {
        // Split remaining evenly across the non-treatment keys, put
        // the rounding remainder on the first of them so the sum is
        // exactly 100.
        val base = remaining / others.size
        val leftover = remaining - base * others.size
        others.forEachIndexed { i, k ->
            newWeights[k] = base + if (i == 0) leftover else 0
        }
        return newWeights
    }
    // Proportional split. Track cumulative rounded values so the
    // last key absorbs the rounding remainder — guarantees the
    // non-treatment weights sum to exactly `remaining`.
    var assigned = 0
    others.forEachIndexed { i, k ->
        val share = weights[k] ?: error("Rollout weight disappeared for variation '$k'")
        val w = if (i == others.lastIndex) {
            remaining - assigned
        } else {
            (remaining * share) / otherTotal
        }
        newWeights[k] = w
        assigned += w
    }
    return newWeights
}

/**
 * Returns the halt weight map: the explicit control receives all traffic and every
 * other arm receives zero. Keeping zero-weight entries preserves the experiment's
 * immutable analysis topology while ensuring no treatment continues to serve.
 */
internal fun haltWeights(
    weights: Map<String, Int>,
    treatmentKey: String,
    controlVariationKey: String,
): Map<String, Int> {
    require(treatmentKey != controlVariationKey) { "Treatment and control variations must differ" }
    require(controlVariationKey in weights) { "Control variation '$controlVariationKey' is absent from rollout" }
    require(treatmentKey in weights) { "Treatment variation '$treatmentKey' is absent from rollout" }
    return weights.keys.associateWith { if (it == controlVariationKey) 100 else 0 }
}

private fun formatConfidence(c: Double?): String =
    if (c == null) "null" else "%.3f".format(c)
