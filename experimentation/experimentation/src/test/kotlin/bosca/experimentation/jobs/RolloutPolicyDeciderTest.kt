package bosca.experimentation.jobs

import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyAction
import bosca.experimentation.model.RolloutPolicyMode
import bosca.experimentation.model.RolloutStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins every cell of the rollout-controller decision table from
 * specs/experiments-v2/requirements.md R2 against the pure [decide]
 * function.
 *
 * The decider is the policy brain — the executor wrapping it is
 * mostly I/O glue (load state, apply action, write audit row). A bug
 * in the decider silently ships bad rollout decisions; a bug in the
 * executor shows up as a stack trace in logs. Tests here are
 * therefore exhaustive: every (mode × verdict) cell, plus the
 * minConfidence gate, the incrementPercent cap, the final-step
 * Complete transition, the halt dry-run switch, the SRM hold, and
 * the rampToTarget weight math that the executor ends up writing.
 */
class RolloutPolicyDeciderTest {

    // -----------------------------------------------------------------
    // Policy fixtures. Every test builds a fresh policy so config
    // overrides can be tweaked without cross-contamination.
    // -----------------------------------------------------------------

    private fun adaptiveStepsPolicy(
        treatment: String = "treatment",
        steps: List<Int> = listOf(10, 25, 50, 100),
        minConfidence: Double = 0.95,
        haltOnGuardrail: Boolean = true,
    ) = RolloutPolicy(
        mode = RolloutPolicyMode.ADAPTIVE_STEPS,
        treatmentVariationKey = treatment,
        steps = steps.map { RolloutStep(weightPercent = it) },
        minConfidence = minConfidence,
        haltOnGuardrail = haltOnGuardrail,
    )

    private fun adaptiveContinuousPolicy(
        treatment: String = "treatment",
        incrementPercent: Double = 5.0,
        minConfidence: Double = 0.95,
        haltOnGuardrail: Boolean = true,
    ) = RolloutPolicy(
        mode = RolloutPolicyMode.ADAPTIVE_CONTINUOUS,
        treatmentVariationKey = treatment,
        incrementPercent = incrementPercent,
        minConfidence = minConfidence,
        haltOnGuardrail = haltOnGuardrail,
    )

    private fun scheduledPolicy(
        treatment: String = "treatment",
        steps: List<Pair<Int, String>> = listOf(10 to "P1D", 25 to "P1D", 100 to "P1D"),
        haltOnGuardrail: Boolean = true,
    ) = RolloutPolicy(
        mode = RolloutPolicyMode.SCHEDULED_STEPS,
        treatmentVariationKey = treatment,
        steps = steps.map { (w, d) -> RolloutStep(weightPercent = w, afterDuration = d) },
        haltOnGuardrail = haltOnGuardrail,
    )

    private fun manualPolicy(haltOnGuardrail: Boolean = true) = RolloutPolicy(
        mode = RolloutPolicyMode.MANUAL,
        treatmentVariationKey = "treatment",
        haltOnGuardrail = haltOnGuardrail,
    )

    private fun input(
        policy: RolloutPolicy,
        verdict: Verdict,
        weights: Map<String, Int> = mapOf("control" to 90, "treatment" to 10),
        confidence: Double? = null,
        scheduledStepIndex: Int? = null,
    ) = RolloutDecisionInput(
        policy = policy,
        verdict = verdict,
        primaryConfidence = confidence,
        currentWeights = weights,
        controlVariationKey = "control",
        scheduledStepIndex = scheduledStepIndex,
    )

    // -----------------------------------------------------------------
    // ADAPTIVE_STEPS mode
    // -----------------------------------------------------------------

    @Test
    fun `adaptive steps SHIP with sufficient confidence advances to next step`() {
        val action = decide(
            input(
                adaptiveStepsPolicy(steps = listOf(10, 25, 50, 100)),
                verdict = Verdict.SHIP,
                weights = mapOf("control" to 90, "treatment" to 10),
                confidence = 0.97,
            )
        )
        val advance = assertIs<Action.Advance>(action, "expected Advance, got $action")
        assertEquals(25, advance.newWeights["treatment"])
        assertEquals(75, advance.newWeights["control"])
        assertTrue(advance.reason.contains("10%"))
        assertTrue(advance.reason.contains("25%"))
    }

    @Test
    fun `adaptive steps SHIP below minConfidence holds`() {
        // 0.92 clears the analyzer's 0.95 bar only via Bonferroni
        // softening, or this could be a Bayesian run where the
        // posterior is 0.92. Either way the policy says "require
        // 0.99 before auto-advancing" — test the gate independently
        // from the analyzer's internal threshold.
        val action = decide(
            input(
                adaptiveStepsPolicy(minConfidence = 0.99),
                verdict = Verdict.SHIP,
                weights = mapOf("control" to 90, "treatment" to 10),
                confidence = 0.92,
            )
        )
        val hold = assertIs<Action.Hold>(action)
        assertTrue(hold.reason.contains("0.920"), "reason should name the failing confidence, got: ${hold.reason}")
        assertTrue(hold.reason.contains("minConfidence 0.99"), "reason should name the policy bar")
    }

    @Test
    fun `adaptive modes hold when SHIP confidence is absent`() {
        assertIs<Action.Hold>(decide(input(adaptiveStepsPolicy(), Verdict.SHIP, confidence = null)))
        assertIs<Action.Hold>(decide(input(adaptiveContinuousPolicy(), Verdict.SHIP, confidence = null)))
    }

    @Test
    fun `adaptive steps SHIP on final step marks Complete`() {
        val action = decide(
            input(
                adaptiveStepsPolicy(steps = listOf(10, 25, 50, 100)),
                verdict = Verdict.SHIP,
                weights = mapOf("control" to 50, "treatment" to 50),
                confidence = 0.99,
            )
        )
        val complete = assertIs<Action.Complete>(action, "expected Complete, got $action")
        assertEquals(100, complete.newWeights["treatment"])
        assertEquals(0, complete.newWeights["control"])
    }

    @Test
    fun `adaptive steps completes for a one hundred target before a duplicate final step`() {
        val action = decide(
            input(
                adaptiveStepsPolicy(steps = listOf(10, 100, 100)),
                verdict = Verdict.SHIP,
                weights = mapOf("control" to 90, "treatment" to 10),
                confidence = 0.99,
            ),
        )
        assertIs<Action.Complete>(action)
    }

    @Test
    fun `adaptive steps already at final step still marks Complete`() {
        // Edge case: operator manually ramped to 100% before the
        // controller's next wake. The controller shouldn't treat that
        // as "advance past the list and explode" — it should flip
        // the experiment to COMPLETED so follow-up analyses stop.
        val action = decide(
            input(
                adaptiveStepsPolicy(steps = listOf(10, 50, 100)),
                verdict = Verdict.SHIP,
                weights = mapOf("control" to 0, "treatment" to 100),
                confidence = 0.99,
            )
        )
        assertIs<Action.Complete>(action, "already-at-100 SHIP must mark Complete, got $action")
    }

    @Test
    fun `adaptive steps non-SHIP verdicts hold`() {
        val holds = listOf(Verdict.KEEP_RUNNING, Verdict.INCONCLUSIVE, Verdict.DO_NOT_SHIP, Verdict.NO_DATA)
        for (v in holds) {
            val action = decide(input(adaptiveStepsPolicy(), verdict = v, confidence = 0.99))
            assertIs<Action.Hold>(action, "verdict=$v must hold in ADAPTIVE_STEPS, got $action")
        }
    }

    // -----------------------------------------------------------------
    // ADAPTIVE_CONTINUOUS mode
    // -----------------------------------------------------------------

    @Test
    fun `adaptive continuous SHIP adds incrementPercent`() {
        val action = decide(
            input(
                adaptiveContinuousPolicy(incrementPercent = 5.0),
                verdict = Verdict.SHIP,
                weights = mapOf("control" to 90, "treatment" to 10),
                confidence = 0.97,
            )
        )
        val advance = assertIs<Action.Advance>(action)
        assertEquals(15, advance.newWeights["treatment"])
        assertEquals(85, advance.newWeights["control"])
    }

    @Test
    fun `adaptive continuous caps at 100 and marks Complete`() {
        val action = decide(
            input(
                adaptiveContinuousPolicy(incrementPercent = 20.0),
                verdict = Verdict.SHIP,
                weights = mapOf("control" to 10, "treatment" to 90),
                confidence = 0.99,
            )
        )
        val complete = assertIs<Action.Complete>(action)
        assertEquals(100, complete.newWeights["treatment"])
        assertEquals(0, complete.newWeights["control"])
    }

    @Test
    fun `adaptive continuous already at 100 produces Complete`() {
        val action = decide(
            input(
                adaptiveContinuousPolicy(),
                verdict = Verdict.SHIP,
                weights = mapOf("control" to 0, "treatment" to 100),
                confidence = 0.99,
            )
        )
        assertIs<Action.Complete>(action)
    }

    @Test
    fun `adaptive continuous below minConfidence holds`() {
        val action = decide(
            input(
                adaptiveContinuousPolicy(minConfidence = 0.99),
                verdict = Verdict.SHIP,
                confidence = 0.96,
            )
        )
        assertIs<Action.Hold>(action)
    }

    @Test
    fun `adaptive continuous non-SHIP verdicts hold`() {
        for (v in listOf(Verdict.KEEP_RUNNING, Verdict.INCONCLUSIVE, Verdict.DO_NOT_SHIP, Verdict.NO_DATA)) {
            val action = decide(input(adaptiveContinuousPolicy(), verdict = v, confidence = 0.99))
            assertIs<Action.Hold>(action, "verdict=$v must hold, got $action")
        }
    }

    // -----------------------------------------------------------------
    // MANUAL mode
    // -----------------------------------------------------------------

    @Test
    fun `manual mode holds on SHIP`() {
        val action = decide(
            input(manualPolicy(), verdict = Verdict.SHIP, confidence = 0.99)
        )
        val hold = assertIs<Action.Hold>(action)
        assertTrue(hold.reason.contains("MANUAL"), "reason should name the mode")
        assertTrue(hold.reason.contains("operator"), "reason should mention operator promotion")
    }

    @Test
    fun `manual mode halts on guardrail when haltOnGuardrail is true`() {
        val action = decide(
            input(
                manualPolicy(haltOnGuardrail = true),
                verdict = Verdict.HALT,
                weights = mapOf("control" to 50, "treatment" to 50),
            )
        )
        val halt = assertIs<Action.Halt>(action)
        assertEquals(0, halt.newWeights["treatment"])
        assertEquals(100, halt.newWeights["control"])
    }

    // -----------------------------------------------------------------
    // HALT handling across modes
    // -----------------------------------------------------------------

    @Test
    fun `HALT halts every mode when haltOnGuardrail is true`() {
        val weights = mapOf("control" to 50, "treatment" to 50)
        val policies = listOf(
            adaptiveStepsPolicy(),
            adaptiveContinuousPolicy(),
            scheduledPolicy(),
            manualPolicy(),
        )
        for (p in policies) {
            val action = decide(input(p, verdict = Verdict.HALT, weights = weights, scheduledStepIndex = 0))
            val halt = assertIs<Action.Halt>(action, "mode=${p.mode} HALT must halt, got $action")
            assertEquals(0, halt.newWeights["treatment"], "mode=${p.mode}")
            assertEquals(100, halt.newWeights["control"], "mode=${p.mode}")
        }
    }

    @Test
    fun `HALT with haltOnGuardrail false holds as dry-run`() {
        val action = decide(
            input(
                adaptiveStepsPolicy(haltOnGuardrail = false),
                verdict = Verdict.HALT,
                weights = mapOf("control" to 50, "treatment" to 50),
            )
        )
        val hold = assertIs<Action.Hold>(action)
        assertTrue(hold.reason.contains("dry-run"))
    }

    // -----------------------------------------------------------------
    // SCHEDULED_STEPS mode
    // -----------------------------------------------------------------

    @Test
    fun `scheduled mode applies step at scheduled index regardless of verdict`() {
        // Scheduled mode is time-driven — the KEEP_RUNNING verdict
        // does NOT hold the ramp back. Only HALT can stop it.
        val action = decide(
            input(
                scheduledPolicy(steps = listOf(10 to "P1D", 25 to "P1D", 50 to "P1D", 100 to "P1D")),
                verdict = Verdict.KEEP_RUNNING,
                weights = mapOf("control" to 90, "treatment" to 10),
                scheduledStepIndex = 1,
            )
        )
        val advance = assertIs<Action.Advance>(action, "scheduled mode must advance on time, got $action")
        assertEquals(25, advance.newWeights["treatment"])
    }

    @Test
    fun `scheduled mode final step marks Complete`() {
        val action = decide(
            input(
                scheduledPolicy(steps = listOf(10 to "P1D", 50 to "P1D", 100 to "P1D")),
                verdict = Verdict.KEEP_RUNNING,
                weights = mapOf("control" to 50, "treatment" to 50),
                scheduledStepIndex = 2,
            )
        )
        val complete = assertIs<Action.Complete>(action)
        assertEquals(100, complete.newWeights["treatment"])
    }

    @Test
    fun `scheduled mode ignores analysis-chained wake without step index`() {
        // When the analysis executor chains a controller job on a
        // SCHEDULED_STEPS policy (via the generic post-analysis path),
        // the controller must not double-advance the ramp. Verify the
        // no-op hold.
        val action = decide(
            input(
                scheduledPolicy(),
                verdict = Verdict.SHIP,
                confidence = 0.99,
                scheduledStepIndex = null,
            )
        )
        val hold = assertIs<Action.Hold>(action)
        assertTrue(hold.reason.contains("time loop owns"))
    }

    @Test
    fun `scheduled mode stale step index holds`() {
        val action = decide(
            input(
                scheduledPolicy(steps = listOf(10 to "P1D", 50 to "P1D")),
                verdict = Verdict.KEEP_RUNNING,
                scheduledStepIndex = 5,
            )
        )
        val hold = assertIs<Action.Hold>(action)
        assertTrue(hold.reason.contains("out of range"))
    }

    @Test
    fun `scheduled mode rejects a negative step index`() {
        assertIs<Action.Hold>(
            decide(input(scheduledPolicy(), Verdict.KEEP_RUNNING, scheduledStepIndex = -1)),
        )
    }

    @Test
    fun `scheduled mode completes at a final step below one hundred`() {
        val action = decide(
            input(
                scheduledPolicy(steps = listOf(10 to "P1D", 50 to "P1D")),
                Verdict.KEEP_RUNNING,
                scheduledStepIndex = 1,
            ),
        )
        assertIs<Action.Complete>(action)
    }

    @Test
    fun `scheduled mode completes when an early step reaches one hundred`() {
        val action = decide(
            input(
                scheduledPolicy(steps = listOf(100 to "P1D", 100 to "P1D")),
                verdict = Verdict.KEEP_RUNNING,
                scheduledStepIndex = 0,
            ),
        )
        assertIs<Action.Complete>(action)
    }

    // -----------------------------------------------------------------
    // SRM_FAILED handling
    // -----------------------------------------------------------------

    @Test
    fun `SRM_FAILED holds in every mode`() {
        val policies = listOf(
            adaptiveStepsPolicy(),
            adaptiveContinuousPolicy(),
            scheduledPolicy(),
            manualPolicy(),
        )
        for (p in policies) {
            val action = decide(
                input(p, verdict = Verdict.SRM_FAILED, scheduledStepIndex = 0)
            )
            val hold = assertIs<Action.Hold>(action, "mode=${p.mode} SRM_FAILED must hold, got $action")
            assertTrue(hold.reason.contains("SRM_FAILED"), "mode=${p.mode} reason should name SRM")
        }
    }

    // -----------------------------------------------------------------
    // Weight math (rampToTarget / haltWeights)
    // -----------------------------------------------------------------

    @Test
    fun `rampToTarget preserves split across multiple control variations`() {
        // Three-arm rollout: 60/30/10, treatment is the 10% arm. Ramp
        // treatment to 40%. The two non-treatment arms should keep
        // their 2:1 ratio of the remaining 60%.
        val result = rampToTarget(
            weights = mapOf("a" to 60, "b" to 30, "treatment" to 10),
            treatmentKey = "treatment",
            targetPercent = 40,
        )
        assertEquals(40, result["treatment"])
        // 60% remaining, distributed 2:1 → 40 and 20.
        assertEquals(40, result["a"])
        assertEquals(20, result["b"])
        // Invariant: always sums to 100.
        assertEquals(100, result.values.sum())
    }

    @Test
    fun `rampToTarget with zero current non-treatment weight splits evenly`() {
        // Two-arm rollout where control currently has 0 (e.g. a
        // previous HALT wrote 0/100, then operator flipped to
        // resume). Ramp to 40% — must give control 60%, not crash.
        val result = rampToTarget(
            weights = mapOf("control" to 0, "treatment" to 100),
            treatmentKey = "treatment",
            targetPercent = 40,
        )
        assertEquals(40, result["treatment"])
        assertEquals(60, result["control"])
    }

    @Test
    fun `rampToTarget splits zero-weight remainder across multiple controls`() {
        val result = rampToTarget(
            weights = linkedMapOf("a" to 0, "b" to 0, "treatment" to 100),
            treatmentKey = "treatment",
            targetPercent = 25,
        )
        assertEquals(mapOf("treatment" to 25, "a" to 38, "b" to 37), result)
    }

    @Test
    fun `rampToTarget handles a single treatment key`() {
        assertEquals(
            mapOf("treatment" to 40),
            rampToTarget(mapOf("treatment" to 100), "treatment", 40),
        )
    }

    @Test
    fun `rampToTarget reports the exact variation when an inconsistent map loses a weight`() {
        val missingDuringTotal = object : AbstractMap<String, Int>() {
            override val entries = mapOf("control" to 100, "treatment" to 0).entries
            override fun get(key: String): Int? = if (key == "control") null else super.get(key)
        }
        val totalError = assertFailsWith<IllegalStateException> {
            rampToTarget(missingDuringTotal, "treatment", 50)
        }
        assertTrue(totalError.message?.contains("'control'") == true)

        var controlReads = 0
        val missingDuringSplit = object : AbstractMap<String, Int>() {
            override val entries = mapOf("control" to 100, "treatment" to 0).entries
            override fun get(key: String): Int? = when (key) {
                "control" -> if (controlReads++ == 0) 100 else null
                else -> super.get(key)
            }
        }
        val splitError = assertFailsWith<IllegalStateException> {
            rampToTarget(missingDuringSplit, "treatment", 50)
        }
        assertTrue(splitError.message?.contains("'control'") == true)
    }

    @Test
    fun `rampToTarget to 100 zeros every other variation`() {
        val result = rampToTarget(
            weights = mapOf("a" to 30, "b" to 20, "treatment" to 50),
            treatmentKey = "treatment",
            targetPercent = 100,
        )
        assertEquals(100, result["treatment"])
        assertEquals(0, result["a"])
        assertEquals(0, result["b"])
    }

    @Test
    fun `haltWeights sends all traffic to explicit control and retains topology`() {
        val result = haltWeights(
            weights = mapOf("control-a" to 20, "control-b" to 30, "treatment" to 50),
            treatmentKey = "treatment",
            controlVariationKey = "control-a",
        )
        assertEquals(0, result["treatment"])
        assertEquals(100, result["control-a"])
        assertEquals(0, result["control-b"])
        assertEquals(100, result.values.sum())
    }

    @Test
    fun `treatmentPercent derives integer percent from unitless weights`() {
        assertEquals(25, treatmentPercent(mapOf("control" to 3, "treatment" to 1), "treatment"))
        assertEquals(50, treatmentPercent(mapOf("control" to 50, "treatment" to 50), "treatment"))
        assertEquals(0, treatmentPercent(mapOf("control" to 1, "treatment" to 0), "treatment"))
        assertEquals(100, treatmentPercent(mapOf("control" to 0, "treatment" to 1), "treatment"))
    }

    @Test
    fun `rampToTarget is a no-op when treatment key is absent`() {
        val weights = mapOf("control" to 50, "other" to 50)
        assertEquals(weights, rampToTarget(weights, "treatment", 50))
    }

    @Test
    fun `weight helpers reject invalid topology and percentages`() {
        assertFailsWith<IllegalArgumentException> {
            rampToTarget(mapOf("control" to 100, "treatment" to 0), "treatment", -1)
        }
        assertFailsWith<IllegalArgumentException> {
            rampToTarget(mapOf("control" to 100, "treatment" to 0), "treatment", 101)
        }
        assertFailsWith<IllegalArgumentException> {
            haltWeights(mapOf("same" to 100), "same", "same")
        }
        assertFailsWith<IllegalArgumentException> {
            haltWeights(mapOf("treatment" to 100), "treatment", "control")
        }
        assertFailsWith<IllegalArgumentException> {
            haltWeights(mapOf("control" to 100), "treatment", "control")
        }
    }

    @Test
    fun `treatmentPercent handles absent treatment and zero total`() {
        assertEquals(0, treatmentPercent(mapOf("control" to 100), "treatment"))
        assertEquals(0, treatmentPercent(mapOf("control" to 0, "treatment" to 0), "treatment"))
    }

    // -----------------------------------------------------------------
    // Action type metadata
    // -----------------------------------------------------------------

    @Test
    fun `Action subclasses report their audit-log type`() {
        assertEquals(RolloutPolicyAction.ADVANCED, Action.Advance(mapOf("x" to 1), "r").type)
        assertEquals(RolloutPolicyAction.COMPLETED, Action.Complete(mapOf("x" to 1), "r").type)
        assertEquals(RolloutPolicyAction.HALTED, Action.Halt(mapOf("x" to 1), "r").type)
        assertEquals(RolloutPolicyAction.HELD, Action.Hold("r").type)
    }
}
