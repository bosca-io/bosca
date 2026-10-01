package bosca.experimentation.jobs

import bosca.experimentation.model.AiInsights
import bosca.server.BoscaApplication
import bosca.service.annotation.ServiceImplementation
import com.google.auth.oauth2.GoogleCredentials
import com.google.genai.Client
import com.google.genai.types.GenerateContentConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * [ExperimentAiAnalyzer] backed by Google's `genai` SDK.
 *
 * Unlike the previous incarnation, this analyzer is *not* asked to
 * paraphrase the deterministic verdict — that's a job the deterministic
 * templater in `ExperimentAnalysis.renderVerdict` already does well, and
 * paraphrase prompts are where LLMs are weakest. Instead the model is
 * given the structured analysis and asked to do the things only a
 * model can plausibly add value at:
 *
 *   1. Reconcile the observed result against the operator's hypothesis.
 *   2. Spot cross-goal patterns (one goal up, another down, etc.).
 *   3. Suggest concrete follow-up experiments grounded in the data.
 *   4. When SRM failed, propose root causes the operator should check.
 *
 * Two safety rails:
 *
 *   - The prompt requires the model to echo the deterministic verdict
 *     back via a top-level `verdictAcknowledged` field. The verifier in
 *     [ensureConsistentWithVerdict] rejects any payload whose
 *     acknowledgement does not match `analysis.verdict.name`. Without
 *     this, a model misreading the glossary could quietly attach an
 *     "AI says don't ship" block to a deterministic SHIP banner.
 *
 *   - The output is persisted to a *separate* `aiInsights` column on
 *     the analysis report, never to `summary` / `recommendation`. The
 *     deterministic prose is always the authoritative product surface,
 *     and the admin UI labels the AI section explicitly.
 */
@ServiceImplementation
open class GenAiExperimentAnalyzer(
    private val application: BoscaApplication,
    private val json: Json,
) : ExperimentAiAnalyzer {

    /**
     * Calls the underlying genai model with the prompt and returns
     * the raw text response, or null on any failure (missing
     * credentials, client build failure, generateContent failure,
     * null text). Extracted as an `open` seam so tests can substitute
     * a deterministic stub without needing real Google credentials or
     * a network connection.
     */
    protected open suspend fun generateText(prompt: String): String? {
        val credentialsBytes = runCatching {
            application.environment.config.property(GENAI_CREDENTIALS_CONFIG_KEY).getString()
        }.onFailure {
            log.warn("AI analyzer: config key '{}' is not set; skipping AI insights: {}", GENAI_CREDENTIALS_CONFIG_KEY, it.message)
        }.getOrNull()?.toByteArray() ?: return null

        return withContext(Dispatchers.IO) {
            val credentials = try {
                credentialsBytes.inputStream().use { GoogleCredentials.fromStream(it) }
            } catch (e: Exception) {
                log.warn("AI analyzer: failed to load Google credentials from '{}': {}", GENAI_CREDENTIALS_CONFIG_KEY, e.message)
                return@withContext null
            }
            val client = try {
                Client.builder().credentials(credentials).build()
            } catch (e: Exception) {
                log.warn("AI analyzer: failed to build genai client: {}", e.message)
                return@withContext null
            }
            val response = try {
                client.models.generateContent(
                    MODEL_NAME,
                    prompt,
                    GenerateContentConfig.builder().build(),
                )
            } catch (e: Exception) {
                log.warn("AI analyzer: generateContent call failed: {}", e.message, e)
                return@withContext null
            }
            val text = response.text()
            if (text == null) {
                log.warn("AI analyzer: generateContent returned a response with null text")
            }
            text
        }
    }

    override suspend fun summarize(analysis: DeterministicAnalysis): AiInsights? {
        log.info("GenAiExperimentAnalyzer.summarize invoked for verdict={}", analysis.verdict.name)
        val prompt = buildPrompt(analysis)
        val responseText = generateText(prompt) ?: return null

        val parsed = parseStructuredResponse(responseText)
        if (parsed == null) {
            log.warn(
                "AI analyzer returned text that didn't parse as JSON; AI insights will be omitted from the report. Raw (truncated): {}",
                responseText.take(200),
            )
            return null
        }
        val verified = ensureConsistentWithVerdict(parsed, analysis)
        if (verified == null) {
            log.warn(
                "AI analyzer response did not echo the deterministic verdict {}; rejecting to avoid contradicting the report.",
                analysis.verdict.name,
            )
            return null
        }
        return decodeInsights(verified)
    }

    /**
     * Decodes the verified model payload into the typed [AiInsights]
     * surface. The `verdictAcknowledged` field is a prompt-engineering
     * guardrail used by [ensureConsistentWithVerdict]; it is dropped
     * here so it never reaches the database or the GraphQL API.
     *
     * Decode failures are allowed to propagate to the caller
     * ([runExperimentAnalysis]), which already wraps the whole AI
     * step in `runCatching { aiAnalyzer.summarize(analysis) }` and
     * leaves `aiInsights = null` on any failure. Catching here as
     * well would swallow useful error context and duplicate the
     * call-site handling. A throw preserves the "AI is additive,
     * failures are silent to the report" contract without hiding
     * the error class or message from the structured log.
     */
    private fun decodeInsights(verified: JsonObject): AiInsights {
        val stripped = JsonObject(verified.filterKeys { it != "verdictAcknowledged" })
        return json.decodeFromJsonElement(AiInsights.serializer(), stripped)
    }

    /**
     * Builds the prompt sent to the model.
     *
     * Structure:
     *  1. A preamble explaining the model's role: it is reading a
     *     completed deterministic analysis and adding *insights* on top
     *     — not paraphrasing, not recomputing, not deciding.
     *  2. The hypothesis (operator's stated intent).
     *  3. The full structured analysis JSON, including per-goal verdicts,
     *     lift CIs, SRM result, and the multiple-testing correction state.
     *  4. The verdict glossary so the model knows what each enum means.
     *  5. The required output schema, with explicit per-field guidance.
     *
     * The model is told *what to do* (reconcile hypothesis, find
     * patterns, suggest follow-ups, root-cause SRM) and *what not to do*
     * (paraphrase the summary, recompute statistics, contradict the
     * verdict). The required `verdictAcknowledged` field forces the
     * model to look at the verdict before writing anything else, and
     * gives the verifier a deterministic check.
     */
    private fun buildPrompt(analysis: DeterministicAnalysis): String {
        val analysisJson = analysisToJson(analysis)
        return """
            You are an experienced product analyst reviewing a controlled software A/B
            experiment. A deterministic statistical pipeline has already computed the
            verdict, the per-goal results, and the per-variation lift confidence
            intervals. That deterministic output is what the operator will be reading
            as the primary report — your job is to add the things only a human
            analyst would add on top: hypothesis reconciliation, cross-goal pattern
            recognition, and concrete follow-up suggestions.

            DO NOT paraphrase or restate the deterministic summary — it is shown to
            the operator above your output. DO NOT recompute statistics or invent
            numbers that aren't already in the analysis. DO NOT contradict the
            verdict; if the verdict is SHIP, your insights describe the win and the
            follow-ups, not whether to ship.

            HYPOTHESIS (what the operator was testing):
            ${analysis.hypothesis.ifBlank { "(no hypothesis recorded)" }}

            STRUCTURED ANALYSIS (the source of truth — do not contradict):
            $analysisJson

            VERDICT CODE: ${analysis.verdict.name}

            Verdict meanings:
              SHIP            — at least one PRIMARY goal treatment beat control at the
                                configured per-test confidence threshold (after Bonferroni
                                correction across the family) and above the practical
                                significance threshold, with no primary regression, no
                                guardrail regression, and no secondary regression.
              HALT            — a GUARDRAIL goal regressed significantly. The rollout
                                controller will (when configured) immediately revert the
                                treatment to 0% and pause the experiment. Dominates SHIP
                                even when a primary goal is winning.
              DO_NOT_SHIP     — a PRIMARY goal regressed significantly (no guardrail
                                trip). Do not advance; revisit the hypothesis.
              KEEP_RUNNING    — at least one goal is approaching significance but no goal
                                has reached the threshold yet.
              INCONCLUSIVE    — the experiment has data but no clear winner on a primary,
                                or a SECONDARY goal regressed (which downgrades but does
                                not halt).
              SRM_FAILED      — sample ratio mismatch detected; bucketing or tracking is
                                broken and the numbers should not be trusted.
              NO_DATA         — not enough data to compute confidence on any goal.

            Respond with a JSON object exactly matching this shape, with no markdown
            fences and no leading or trailing prose:

            {
              "verdictAcknowledged": "<exactly one of SHIP|HALT|DO_NOT_SHIP|KEEP_RUNNING|INCONCLUSIVE|SRM_FAILED|NO_DATA — must equal the VERDICT CODE above>",
              "hypothesisAssessment": "<1-3 sentences: does the observed result confirm, contradict, or simply fail to address the stated hypothesis? Reference specific goal names and lift values from the structured analysis. If no hypothesis was recorded, say so.>",
              "crossGoalPatterns": "<1-3 sentences: observations that span more than one goal (e.g. 'wins on signups but regresses on revenue — likely recruiting lower-intent users'). If there is only one goal, say so briefly.>",
              "followUpExperiments": ["<one concrete next experiment to consider, grounded in the observed data>", "<another, optional>"],
              "srmRootCauseHints": "<only include this field when the verdict is SRM_FAILED — common causes (cookie loss on redirect, bot traffic skew, bucketing-vs-events client-id mismatch, etc.) the operator should check first. Omit the field entirely otherwise.>"
            }
        """.trimIndent()
    }

    /**
     * Encodes the [DeterministicAnalysis] into a compact JSON string the
     * model can parse. Includes the SRM check, multiple-testing
     * correction state, per-goal verdicts, and per-variation rows with
     * lift CIs — everything the deterministic pipeline knows. The
     * model is instructed not to invent values outside this object.
     */
    private fun analysisToJson(analysis: DeterministicAnalysis): String {
        val obj = buildJsonObject {
            put("verdict", analysis.verdict.name)
            put("experimentName", analysis.experimentName)
            put("totalImpressions", analysis.totalImpressions)
            put("totalConversions", analysis.totalConversions)
            analysis.controlKey?.let { put("controlKey", it) }
            putJsonObject("multipleTesting") {
                put("numComparisons", analysis.numComparisons)
                put("effectiveAlpha", analysis.effectiveAlpha)
                put("perTestConfidence", 1.0 - analysis.effectiveAlpha)
                put("corrected", analysis.numComparisons > 1)
            }
            analysis.srm?.let { srm ->
                putJsonObject("srm") {
                    put("failed", srm.failed)
                    put("chiSquared", "%.4f".format(srm.chiSquared).toDouble())
                    put("pValue", "%.6f".format(srm.pValue).toDouble())
                }
            }
            putJsonArray("goals") {
                analysis.goalAnalyses.forEach { goal ->
                    addJsonObject {
                        put("name", goal.goalName)
                        put("metricType", goal.metricType.name)
                        put("verdict", goal.verdict.name)
                        putJsonArray("variations") {
                            goal.variationRows.forEach { row ->
                                addJsonObject {
                                    put("name", row.variationName)
                                    put("isControl", row.isControl)
                                    put("impressions", row.impressions)
                                    put("conversions", row.conversions)
                                    put("rate", "%.4f".format(row.rate).toDouble())
                                    row.mean?.let { put("mean", "%.4f".format(it).toDouble()) }
                                    row.liftPercent?.let { put("liftPercent", "%.2f".format(it).toDouble()) }
                                    if (row.liftCiLowerPercent != null && row.liftCiUpperPercent != null) {
                                        putJsonArray("liftCi95") {
                                            add("%.2f".format(row.liftCiLowerPercent).toDouble())
                                            add("%.2f".format(row.liftCiUpperPercent).toDouble())
                                        }
                                    }
                                    row.confidence?.let { put("confidence", "%.4f".format(it).toDouble()) }
                                }
                            }
                        }
                    }
                }
            }
        }
        return obj.toString()
    }

    /**
     * Parses the model's response into a [JsonObject]. Tolerates the
     * common LLM mistakes of wrapping the JSON in markdown fences or
     * adding leading/trailing prose by extracting the first `{...}`
     * block. Returns null when the response can't be parsed at all.
     */
    private fun parseStructuredResponse(text: String): JsonObject? {
        val trimmed = text.trim()
        val withoutFences = trimmed
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonStart = withoutFences.indexOf('{')
        val jsonEnd = withoutFences.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null
        val jsonString = withoutFences.substring(jsonStart, jsonEnd + 1)
        return try {
            json.parseToJsonElement(jsonString).jsonObject
        } catch (e: Exception) {
            log.debug("Failed to parse AI response as JSON: {}", e.message)
            null
        }
    }

    /**
     * Semantic verifier. Checks the parsed payload's
     * `verdictAcknowledged` field against `analysis.verdict.name`. The
     * comparison is case-insensitive only because models occasionally
     * lowercase enum values; everything else must match exactly.
     *
     * This is the entire reason it is safe to publish AI prose into a
     * UI section labeled "AI Insights": the deterministic verdict is
     * always the headline, and any LLM payload that fails to even
     * acknowledge the same verdict gets dropped on the floor before it
     * reaches the database.
     */
    internal fun ensureConsistentWithVerdict(
        payload: JsonObject,
        analysis: DeterministicAnalysis,
    ): JsonObject? {
        val acknowledged = runCatching {
            payload["verdictAcknowledged"]?.jsonPrimitive?.content
        }.getOrNull() ?: return null
        if (!acknowledged.equals(analysis.verdict.name, ignoreCase = true)) return null
        return payload
    }

    companion object {
        private val log = LoggerFactory.getLogger(GenAiExperimentAnalyzer::class.java)

        /**
         * Application config key holding the Google service-account JSON
         * credentials. Matches the existing usage in
         * `bosca.kit.tools.image.GenerateImageTool` and
         * `bosca.jobs.GoogleGenAITTSJob`.
         */
        private const val GENAI_CREDENTIALS_CONFIG_KEY = "google.genai.account"

        /**
         * Model used for experiment analysis insights. Picked to balance
         * speed and quality on a one-shot reasoning prompt; the
         * deterministic numbers carry the load and the model is doing
         * pattern recognition and follow-up generation around them.
         */
        private const val MODEL_NAME = "gemini-2.5-flash"
    }
}
