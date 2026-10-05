package bosca.experimentation.jobs

import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.GoalMetricType
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GenAiExperimentAnalyzer.summarize] that exercise the
 * orchestration around the genai client without standing up a real
 * model. The single seam is the protected `generateText` method,
 * which a test subclass overrides to return a scripted string. Every
 * other branch of the orchestration — credential gating, prompt
 * generation, JSON parsing tolerance, verdict consistency check, and
 * typed [bosca.experimentation.model.AiInsights] decoding — is hit
 * through that seam.
 *
 * The credential-missing branch is covered by leaving the prod
 * `summarize` path with no override; with no
 * `google.genai.account` config key the `runCatching` lookup
 * returns null and `generateText` short-circuits before any client
 * is built.
 */
class GenAiAnalyzerSummarizeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns a relaxed-mocked [BoscaApplication]. With no explicit
     * config stubs, the production `generateText` path's
     * `application.environment.config.property(...)` call returns a
     * relaxed mock whose `getString()` returns an empty string —
     * which then throws when `Client.builder()` tries to parse it as
     * credentials, hitting the "credentials parse failed" branch.
     * Tests that exercise the orchestration above this layer use a
     * [ScriptedAnalyzer] subclass and never reach the prod
     * `generateText` at all.
     */
    private fun mockApp(): BoscaApplication = mockk(relaxed = true)

    private fun mockAppWithConfig(yaml: String): BoscaApplication {
        val application = mockk<BoscaApplication>()
        val config = ApplicationConfig.load(yaml.byteInputStream())
        every { application.environment } returns BoscaApplication.Environment(config)
        return application
    }

    private fun analysis(verdict: Verdict = Verdict.SHIP) = DeterministicAnalysis(
        experimentName = "exp",
        hypothesis = "treatment converts better",
        controlKey = "control",
        variationCount = 2,
        goalCount = 1,
        totalImpressions = 1000,
        totalConversions = 120,
        srm = null,
        goalAnalyses = emptyList(),
        verdict = verdict,
        summary = "deterministic summary",
        recommendation = "deterministic recommendation",
        confidence = 0.97,
        numComparisons = 1,
        effectiveAlpha = 0.05,
    )

    private fun richAnalysis() = analysis().copy(
        controlKey = "control",
        srm = SrmResult(
            observed = mapOf("control" to 500, "treatment" to 500),
            expected = mapOf("control" to 500.0, "treatment" to 500.0),
            chiSquared = 0.12345,
            pValue = 0.9876543,
            failed = false,
        ),
        goalAnalyses = listOf(
            GoalAnalysis(
                goalId = "signup",
                goalName = "Signup",
                metricType = GoalMetricType.UNIQUE_CONVERSION,
                role = ConversionGoalRole.PRIMARY,
                variationRows = listOf(
                    VariationRow(
                        variationKey = "control",
                        variationName = "Control",
                        isControl = true,
                        impressions = 500,
                        observationCount = 500,
                        coverage = 1.0,
                        conversions = 50,
                        rate = 0.1,
                        mean = null,
                        variance = null,
                        liftPercent = null,
                        liftCiLowerPercent = null,
                        liftCiUpperPercent = null,
                        confidence = null,
                    ),
                    VariationRow(
                        variationKey = "treatment",
                        variationName = "Treatment",
                        isControl = false,
                        impressions = 500,
                        observationCount = 500,
                        coverage = 1.0,
                        conversions = 70,
                        rate = 0.14,
                        mean = 1.23456,
                        variance = 0.5,
                        liftPercent = 40.123,
                        liftCiLowerPercent = 10.126,
                        liftCiUpperPercent = 70.987,
                        confidence = 0.99123,
                    ),
                ),
                winner = null,
                verdict = GoalVerdict.WINNER,
            ),
        ),
        numComparisons = 2,
        effectiveAlpha = 0.025,
    )

    /** Test subclass that returns a scripted text from generateText. */
    private class ScriptedAnalyzer(
        application: BoscaApplication,
        json: Json,
        private val script: (String) -> String?,
    ) : GenAiExperimentAnalyzer(application, json) {
        var capturedPrompt: String? = null
            private set
        override suspend fun generateText(prompt: String): String? {
            capturedPrompt = prompt
            return script(prompt)
        }
    }

    // -----------------------------------------------------------------
    // Credential-missing branch — the production path
    // -----------------------------------------------------------------

    @Test
    fun `summarize returns null when credentials cannot be loaded`() = runTest {
        val analyzer = GenAiExperimentAnalyzer(mockApp(), json)
        assertNull(analyzer.summarize(analysis()), "credential failure must yield null insights")
    }

    @Test
    fun `summarize returns null when configured credentials are malformed`() = runTest {
        val analyzer = GenAiExperimentAnalyzer(
            mockAppWithConfig(
                """
                google:
                  genai:
                    account: not-json-credentials
                """.trimIndent(),
            ),
            json,
        )

        assertNull(analyzer.summarize(analysis()))
    }

    // -----------------------------------------------------------------
    // generateText returns null — orchestration short-circuits
    // -----------------------------------------------------------------

    @Test
    fun `summarize returns null when generateText returns null`() = runTest {
        val analyzer = ScriptedAnalyzer(mockApp(), json) { null }
        assertNull(analyzer.summarize(analysis()))
        // Prompt is still built and passed to generateText, even though
        // the model returned nothing — pin that the orchestrator does
        // not skip prompt construction when the seam returns null.
        assertNotNull(analyzer.capturedPrompt)
    }

    // -----------------------------------------------------------------
    // Non-JSON text — parseStructuredResponse returns null
    // -----------------------------------------------------------------

    @Test
    fun `summarize returns null when model text is not parseable JSON`() = runTest {
        val analyzer = ScriptedAnalyzer(mockApp(), json) {
            "I'm sorry Dave, I'm afraid I can't do that."
        }
        assertNull(analyzer.summarize(analysis()))
    }

    @Test
    fun `summarize returns null when extracted object is malformed JSON`() = runTest {
        val analyzer = ScriptedAnalyzer(mockApp(), json) { "prefix {not-json} suffix" }

        assertNull(analyzer.summarize(analysis()))
    }

    @Test
    fun `summarize rejects an unterminated JSON object`() = runTest {
        val analyzer = ScriptedAnalyzer(mockApp(), json) { "prefix {" }
        assertNull(analyzer.summarize(analysis()))
    }

    @Test
    fun `prompt includes complete deterministic evidence with rounded optional metrics`() = runTest {
        val analyzer = ScriptedAnalyzer(mockApp(), json) {
            """
            {
              "verdictAcknowledged": "SHIP",
              "hypothesisAssessment": "ok",
              "crossGoalPatterns": "ok",
              "followUpExperiments": []
            }
            """.trimIndent()
        }

        assertNotNull(analyzer.summarize(richAnalysis()))
        val prompt = assertNotNull(analyzer.capturedPrompt)
        assertTrue(prompt.contains("\"srm\""))
        assertTrue(prompt.contains("\"chiSquared\":0.1235"))
        assertTrue(prompt.contains("\"pValue\":0.987654"))
        assertTrue(prompt.contains("\"corrected\":true"))
        assertTrue(prompt.contains("\"mean\":1.2346"))
        assertTrue(prompt.contains("\"liftPercent\":40.12"))
        assertTrue(prompt.contains("\"liftCi95\":[10.13,70.99]"))
        assertTrue(prompt.contains("\"confidence\":0.9912"))
    }

    @Test
    fun `prompt represents blank hypothesis null control and partial confidence bounds safely`() = runTest {
        val base = richAnalysis()
        val goal = base.goalAnalyses.single()
        val treatment = goal.variationRows.last()
        val sparse = base.copy(
            hypothesis = "",
            controlKey = null,
            srm = null,
            goalAnalyses = listOf(
                goal.copy(
                    variationRows = listOf(
                        treatment.copy(
                            variationKey = "lower-only",
                            liftCiUpperPercent = null,
                        ),
                        treatment.copy(
                            variationKey = "upper-only",
                            liftCiLowerPercent = null,
                        ),
                    ),
                ),
            ),
        )
        val analyzer = ScriptedAnalyzer(mockApp(), json) {
            """{"verdictAcknowledged":"SHIP","hypothesisAssessment":"ok","crossGoalPatterns":"ok","followUpExperiments":[]}"""
        }

        assertNotNull(analyzer.summarize(sparse))
        val prompt = assertNotNull(analyzer.capturedPrompt)
        assertTrue(prompt.contains("(no hypothesis recorded)"))
        assertTrue(!prompt.contains("\"controlKey\""))
        assertTrue(!prompt.contains("\"liftCi95\""))
    }

    @Test
    fun `verdict verifier rejects absent and non-primitive acknowledgements`() {
        val analyzer = GenAiExperimentAnalyzer(mockApp(), json)
        assertNull(analyzer.ensureConsistentWithVerdict(buildJsonObject { }, analysis()))
        assertNull(
            analyzer.ensureConsistentWithVerdict(
                buildJsonObject { put("verdictAcknowledged", buildJsonObject { put("value", "SHIP") }) },
                analysis(),
            ),
        )
        assertNotNull(
            analyzer.ensureConsistentWithVerdict(
                buildJsonObject { put("verdictAcknowledged", "ship") },
                analysis(),
            ),
        )
    }

    // -----------------------------------------------------------------
    // Verdict-acknowledgement mismatch — verifier rejects
    // -----------------------------------------------------------------

    @Test
    fun `summarize returns null when verdictAcknowledged does not match analysis verdict`() = runTest {
        val analyzer = ScriptedAnalyzer(mockApp(), json) {
            """
            {
              "verdictAcknowledged": "DO_NOT_SHIP",
              "hypothesisAssessment": "x",
              "crossGoalPatterns": "y",
              "followUpExperiments": []
            }
            """.trimIndent()
        }
        assertNull(
            analyzer.summarize(analysis(Verdict.SHIP)),
            "wrong verdictAcknowledged must be rejected before persisting",
        )
    }

    // -----------------------------------------------------------------
    // Happy path — full decode into AiInsights
    // -----------------------------------------------------------------

    @Test
    fun `summarize returns decoded AiInsights on a well-formed matching response`() = runTest {
        val analyzer = ScriptedAnalyzer(mockApp(), json) {
            """
            {
              "verdictAcknowledged": "SHIP",
              "hypothesisAssessment": "treatment confirmed the hypothesis",
              "crossGoalPatterns": "single goal — no cross-goal patterns",
              "followUpExperiments": ["try a wider rollout", "ramp to 50%"]
            }
            """.trimIndent()
        }
        val result = assertNotNull(analyzer.summarize(analysis(Verdict.SHIP)))
        assertEquals("treatment confirmed the hypothesis", result.hypothesisAssessment)
        assertEquals("single goal — no cross-goal patterns", result.crossGoalPatterns)
        assertEquals(listOf("try a wider rollout", "ramp to 50%"), result.followUpExperiments)
        assertNull(result.srmRootCauseHints, "srmRootCauseHints absent in payload should decode to null")
    }

    // -----------------------------------------------------------------
    // Markdown-fenced response — parseStructuredResponse strips fences
    // -----------------------------------------------------------------

    @Test
    fun `summarize tolerates markdown-fenced JSON response`() = runTest {
        val analyzer = ScriptedAnalyzer(mockApp(), json) {
            """
            ```json
            {
              "verdictAcknowledged": "SHIP",
              "hypothesisAssessment": "ok",
              "crossGoalPatterns": "ok",
              "followUpExperiments": []
            }
            ```
            """.trimIndent()
        }
        val result = assertNotNull(analyzer.summarize(analysis(Verdict.SHIP)))
        assertEquals("ok", result.hypothesisAssessment)
    }

    // -----------------------------------------------------------------
    // SRM_FAILED branch — srmRootCauseHints flows through
    // -----------------------------------------------------------------

    @Test
    fun `summarize forwards srmRootCauseHints when present in payload`() = runTest {
        val analyzer = ScriptedAnalyzer(mockApp(), json) {
            """
            {
              "verdictAcknowledged": "SRM_FAILED",
              "hypothesisAssessment": "untrustworthy",
              "crossGoalPatterns": "n/a",
              "followUpExperiments": [],
              "srmRootCauseHints": "check cookie loss on redirect; verify bucketing client_id matches events client_id"
            }
            """.trimIndent()
        }
        val result = assertNotNull(analyzer.summarize(analysis(Verdict.SRM_FAILED)))
        assertEquals(
            "check cookie loss on redirect; verify bucketing client_id matches events client_id",
            result.srmRootCauseHints,
        )
    }
}
