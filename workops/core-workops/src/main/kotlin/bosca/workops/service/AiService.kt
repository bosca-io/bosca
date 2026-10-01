package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.ai.AiOptOutException
import bosca.workops.model.ai.BqlTranslation
import bosca.workops.model.ai.DuplicateSuggestion
import bosca.workops.model.ai.TriageSuggestion
import bosca.workops.model.task.Task

/**
 * R33 — three-layer opt-in resolver. The dispatcher walks
 * org → project → task and short-circuits with [AiOptOutException]
 * when any layer declines. Phase 13 ships the resolver; the
 * actual AI calls (`core-ai`) plug into the service contracts
 * below.
 */
interface AiOptInService : Service {
    suspend fun isEnabled(projectId: UUID? = null, taskId: UUID? = null): Boolean
    /** Throws [AiOptOutException] when any layer declines. */
    suspend fun requireEnabled(projectId: UUID? = null, taskId: UUID? = null)
    suspend fun setOrgEnabled(enabled: Boolean)
    suspend fun setProjectEnabled(projectId: UUID, enabled: Boolean)
    suspend fun setTaskEnabled(taskId: UUID, enabled: Boolean?)
}

interface DuplicateSuggestionService : Service {
    /**
     * R33 — surfaces the top-N most-similar tasks. Phase 13 ships
     * the lexical branch via the in-memory search sink; the
     * vector branch lights up in Phase 23.
     */
    suspend fun suggest(
        text: String,
        projectId: UUID,
        limit: Int = 5,
    ): List<DuplicateSuggestion>
}

/**
 * R33 — triage / summarize / translate-BQL all need a real
 * `core-ai` model binding to compute their structured outputs.
 * Phase 13 ships the contract; the binding ships separately
 * (Phase 19 AI agents wires the model invocations).
 */
interface TriageSuggestionService : Service {
    suspend fun suggest(taskId: UUID): TriageSuggestion
    suspend fun acceptTriageSuggestion(taskId: UUID, suggestion: TriageSuggestion, actingPrincipalId: UUID): Task
}

/**
 * R33 — single-shot summarization. The cache key (24h) lives on
 * the consumer; the contract here just gates the AI call behind
 * the opt-in resolver.
 */
interface SummarizationService : Service {
    suspend fun summarizeThread(taskId: UUID, kind: String = "DEFAULT"): String
    suspend fun summarizeSprint(sprintId: UUID): String
}

interface BqlTranslationService : Service {
    suspend fun translate(naturalLanguage: String, projectContextId: UUID? = null): BqlTranslation
}
