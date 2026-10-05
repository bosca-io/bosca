package bosca.experimentation.jobs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests for [GenAiExperimentAnalyzer.ensureConsistentWithVerdict].
 *
 * The verifier is the *only* thing standing between an LLM that
 * misreads a verdict glossary and the persisted analysis report. If
 * this function silently lets a payload through whose
 * `verdictAcknowledged` does not match the deterministic verdict, an
 * "AI says don't ship" block can attach itself to a deterministic SHIP
 * banner — exactly the failure mode the AI step was redesigned to
 * prevent. So the test surface here is small but high-value.
 */
class GenAiAnalyzerVerifierTest {

    private val analyzer = GenAiExperimentAnalyzer(
        application = mockk(relaxed = true),
        json = Json,
    )

    private fun analysisWith(verdict: Verdict): DeterministicAnalysis {
        // The verifier only reads `analysis.verdict`. The other fields
        // are populated with cheap defaults so the data class init
        // accepts the value — every other field is irrelevant to the
        // contract under test.
        return DeterministicAnalysis(
            experimentName = "test",
            hypothesis = "",
            controlKey = "control",
            variationCount = 2,
            goalCount = 1,
            totalImpressions = 1000,
            totalConversions = 100,
            srm = null,
            goalAnalyses = emptyList(),
            verdict = verdict,
            summary = "deterministic summary",
            recommendation = "deterministic recommendation",
            confidence = 0.97,
            numComparisons = 1,
            effectiveAlpha = 0.05,
        )
    }

    private fun payload(verdictAck: String?, extraFields: JsonObject = buildJsonObject {}): JsonObject =
        buildJsonObject {
            verdictAck?.let { put("verdictAcknowledged", it) }
            put("hypothesisAssessment", "treatment confirmed the hypothesis")
            put("crossGoalPatterns", "single goal — no cross-goal patterns")
            extraFields.forEach { (k, v) -> put(k, v) }
        }

    // -----------------------------------------------------------------
    // Acceptance: matching verdict
    // -----------------------------------------------------------------

    @Test
    fun `accepts payload whose verdictAcknowledged matches the deterministic verdict`() {
        val analysis = analysisWith(Verdict.SHIP)
        val input = payload("SHIP")
        val verified = analyzer.ensureConsistentWithVerdict(input, analysis)
        assertNotNull(verified, "matching verdict must be accepted")
        // The verifier returns the original payload unchanged — the
        // executor persists exactly what the model produced (minus the
        // contradiction risk).
        assertSame(input, verified)
    }

    @Test
    fun `accepts case insensitive verdict acknowledgement`() {
        // Models occasionally lowercase enum values. The deterministic
        // verdict is the source of truth; lowercase variants are
        // tolerated to keep the success path forgiving for a benign
        // formatting variation that doesn't affect meaning.
        val analysis = analysisWith(Verdict.DO_NOT_SHIP)
        val verified = analyzer.ensureConsistentWithVerdict(payload("do_not_ship"), analysis)
        assertNotNull(verified, "case-insensitive match must be accepted")
    }

    // -----------------------------------------------------------------
    // Rejection: this is the load-bearing half of the test surface.
    // -----------------------------------------------------------------

    @Test
    fun `rejects payload that acknowledges the wrong verdict`() {
        // The single failure mode that matters: deterministic SHIP,
        // model says DO_NOT_SHIP. Without this rejection the admin UI
        // ends up showing both side by side and operators get to pick
        // their favorite.
        val analysis = analysisWith(Verdict.SHIP)
        val verified = analyzer.ensureConsistentWithVerdict(payload("DO_NOT_SHIP"), analysis)
        assertNull(verified, "contradictory verdict must be rejected")
    }

    @Test
    fun `rejects payload that omits verdictAcknowledged entirely`() {
        // No acknowledgement = no proof the model even read the
        // verdict. Refuse rather than persist insights of unknown
        // provenance.
        val analysis = analysisWith(Verdict.SHIP)
        val verified = analyzer.ensureConsistentWithVerdict(payload(verdictAck = null), analysis)
        assertNull(verified, "missing acknowledgement must be rejected")
    }

    @Test
    fun `rejects payload whose verdictAcknowledged is an unrelated string`() {
        // A model that hallucinates a non-enum value (e.g. "MAYBE",
        // "UNKNOWN") must not be persisted — the verifier is enum-strict.
        val analysis = analysisWith(Verdict.KEEP_RUNNING)
        val verified = analyzer.ensureConsistentWithVerdict(payload("MAYBE"), analysis)
        assertNull(verified, "unrelated acknowledgement must be rejected")
    }

    @Test
    fun `rejects payload whose verdictAcknowledged is a valid but different enum value`() {
        // The trickiest case: model produces a real verdict name, just
        // not the right one. This is the "model misread the glossary"
        // failure mode and must be caught.
        val analysis = analysisWith(Verdict.KEEP_RUNNING)
        val verified = analyzer.ensureConsistentWithVerdict(payload("INCONCLUSIVE"), analysis)
        assertNull(verified, "valid-but-wrong enum must be rejected")
    }

    @Test
    fun `every verdict can pass when the acknowledgement matches`() {
        // Sanity check that the verifier isn't accidentally hard-coded
        // to one verdict's name. Iterate every enum value and assert
        // self-consistency goes through.
        Verdict.entries.forEach { v ->
            val verified = analyzer.ensureConsistentWithVerdict(payload(v.name), analysisWith(v))
            assertNotNull(verified, "verdict $v should accept its own name")
        }
    }
}
