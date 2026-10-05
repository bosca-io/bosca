package bosca.analytics.ai.service

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.models.model.Model
import bosca.analytics.ai.agents.errors.ErrorGroupAnalysisAgent
import bosca.analytics.model.ErrorGroup
import bosca.analytics.service.ErrorGroupAnalysisService
import bosca.analytics.service.ErrorGroupAnalysisServiceProvider
import bosca.analytics.service.ErrorGroupService
import bosca.analytics.service.NoOpErrorGroupAnalysisService
import bosca.di.ObjectProvider
import kotlinx.coroutines.CancellationException
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Claude / koog-backed implementation of [ErrorGroupAnalysisService].
 *
 * Looks up the group, asks the agent for a root-cause summary, persists
 * the result via [ErrorGroupService.setAiSummary], and returns the
 * updated group. Transient executor failures are logged and the
 * existing group (with whatever previous `aiSummary` was cached) is
 * returned unchanged — analysis is best-effort and should never block
 * the caller.
 *
 * Registered via the provider below; because the analytics-ai
 * provider registrar runs after the analytics provider registrar in
 * `Application.kt`, this implementation overrides the no-op fallback
 * provided by the analytics module.
 */
class AiErrorGroupAnalysisService(
    private val errorGroupService: ErrorGroupService,
    private val agent: ErrorGroupAnalysisAgent,
) : ErrorGroupAnalysisService {

    override suspend fun analyze(fingerprint: String): ErrorGroup {
        val group = errorGroupService.getByFingerprint(fingerprint)
            ?: error("Error group not found: $fingerprint")
        val lastAnalyzed = group.aiSummaryAt
        if (lastAnalyzed != null) {
            val elapsed = Duration.between(lastAnalyzed.toInstant(), OffsetDateTime.now(ZoneOffset.UTC).toInstant())
            if (elapsed < ANALYSIS_COOLDOWN) {
                log.info(
                    "Skipping analysis for fingerprint={}: last analyzed {}s ago (cooldown={}s)",
                    fingerprint, elapsed.seconds, ANALYSIS_COOLDOWN.seconds,
                )
                return group
            }
        }
        return try {
            val summary = agent.analyze(group)
            if (summary.isNullOrBlank()) {
                log.warn("AI analyzer returned no content for fingerprint={}", fingerprint)
                group
            } else {
                if (summary.length > MAX_SUMMARY_CHARS) {
                    log.warn(
                        "AI summary for fingerprint={} was truncated from {} to {} chars",
                        fingerprint, summary.length, MAX_SUMMARY_CHARS,
                    )
                }
                errorGroupService.setAiSummary(fingerprint, summary.take(MAX_SUMMARY_CHARS))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("AI analyzer failed for fingerprint={}", fingerprint, e)
            group
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AiErrorGroupAnalysisService::class.java)

        /** Minimum interval between AI analyses for the same error group. */
        internal val ANALYSIS_COOLDOWN: Duration = Duration.ofMinutes(5)

        /** Maximum length of an AI-generated summary before truncation.
         *  Matches [ErrorGroupServiceImpl.MAX_AI_SUMMARY_CHARS]. */
        private const val MAX_SUMMARY_CHARS = 65_536
    }
}

/**
 * DI registration for the named AI-backed analyzer. The base analytics module owns the unnamed
 * [ErrorGroupAnalysisService] binding and selects this implementation when it is available.
 */
@Providers
class AiErrorGroupAnalysisServiceProvider {

    @Provider(name = ErrorGroupAnalysisServiceProvider.AI_PROVIDER)
    suspend fun analysisService(
        errorGroupService: ErrorGroupService,
        executor: ObjectProvider<PromptExecutor>,
        application: BoscaApplication,
    ): ErrorGroupAnalysisService {
        if (!executor.exists) {
            log.info("PromptExecutor not available — using no-op error group analysis")
            return NoOpErrorGroupAnalysisService(errorGroupService)
        }
        val modelType = application.environment.config
            .propertyOrNull("analytics.errorAnalysis.model")
            ?.getString()
        val model: LLModel = if (modelType != null) {
            Model(key = "", type = modelType, name = "", description = "").toLLMModel()
        } else {
            OpenAIModels.Chat.GPT4_1
        }
        return AiErrorGroupAnalysisService(
            errorGroupService = errorGroupService,
            agent = ErrorGroupAnalysisAgent(executor = executor.get(), model = model),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(AiErrorGroupAnalysisServiceProvider::class.java)
    }
}
