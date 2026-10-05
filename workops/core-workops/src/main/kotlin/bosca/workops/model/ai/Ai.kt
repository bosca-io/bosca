package bosca.workops.model.ai

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * R33 — opt-in scope. The dispatcher walks org → project →
 * task scopes and short-circuits to `AI_OPT_OUT` if any layer
 * declines.
 */
@Serializable
enum class AiOptInScope { ORG, PROJECT, TASK }

@Serializable
data class AiOptIn(
    val scope: AiOptInScope,
    @Contextual
    val scopeId: UUID? = null,
    val enabled: Boolean,
)

/**
 * R33 — duplicate-detection result. The lexical score combines
 * Meilisearch's relevance with a simple Jaccard overlap; the
 * vector branch lights up in Phase 23 RAG.
 */
@Serializable
data class DuplicateSuggestion(
    @Contextual val candidateTaskId: UUID,
    val candidateKey: String,
    val candidateSummary: String,
    /** 0.0–1.0 lexical similarity. */
    val lexicalScore: Double,
    /** Reason summary the UI shows alongside the candidate. */
    val reasoning: String,
)

/**
 * R33 — triage suggestion shape. Phase 13 ships the protocol;
 * the AI binding fills the values.
 */
@Serializable
data class TriageSuggestion(
    @Contextual
    val priorityId: UUID? = null,
    @Contextual
    val assigneeProfileId: UUID? = null,
    val componentIds: List<@Contextual UUID> = emptyList(),
    val labels: List<@Contextual UUID> = emptyList(),
    val reasoning: String,
    /**
     * `aiSuggested` is stamped onto the audit row when an admin
     * accepts a suggestion via [acceptTriageSuggestion]. The flag
     * preserves attribution at any analytics depth.
     */
    val aiSuggested: Boolean = true,
)

/** R33 — translated BQL with the original natural-language text. */
@Serializable
data class BqlTranslation(
    val source: String,
    val translatedBql: String,
    val rationale: String,
)

/** R33 — opt-out short-circuit thrown by the AI services. */
class AiOptOutException(val scope: AiOptInScope, val scopeId: UUID?) : RuntimeException(
    "AI_OPT_OUT: $scope${if (scopeId != null) "($scopeId)" else ""}",
)
