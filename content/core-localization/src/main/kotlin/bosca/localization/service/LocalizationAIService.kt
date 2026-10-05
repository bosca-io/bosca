package bosca.localization.service

import bosca.localization.model.LocalizationAITranslationRequest
import bosca.localization.model.LocalizationAITranslationResult
import bosca.service.Service

/**
 * Read-only AI generation seam owned by localization. Implementations translate the exact source
 * strings requested; [LocalizationService] remains responsible for validation, persistence,
 * workflow state, and audit history.
 */
interface LocalizationAIService : Service {
    /** Returns exactly one result for every source-string and target-language pair in [request]. */
    suspend fun translate(request: LocalizationAITranslationRequest): List<LocalizationAITranslationResult>
}
