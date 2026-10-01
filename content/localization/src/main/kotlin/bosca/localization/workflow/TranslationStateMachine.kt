package bosca.localization.workflow

import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState

/**
 * Centralizes the workflow rules for translation state transitions.
 *
 * Mechanical rules the machine enforces:
 * - New translations enter [TranslationState.AI_GENERATED] when their origin is
 *   [TranslationOrigin.AI], otherwise [TranslationState.DRAFT]. This is what forces
 *   human review of AI-produced work before it can ship.
 * - Only the transitions listed in [allowed] are permitted. An attempt to transition
 *   outside this set throws [IllegalStateException]; callers should catch and surface
 *   a friendly error in their GraphQL/REST layer.
 * - `approved` and higher states require a reviewer principal; the service layer passes
 *   the currently authenticated principal ID through the transition call so the
 *   audit log captures who approved what.
 */
object TranslationStateMachine {

    /** Returns the initial state a freshly created translation with the given [origin] should take. */
    fun initialState(origin: TranslationOrigin): TranslationState = when (origin) {
        TranslationOrigin.AI -> TranslationState.AI_GENERATED
        TranslationOrigin.HUMAN,
        TranslationOrigin.IMPORT,
        TranslationOrigin.SYNC -> TranslationState.DRAFT
    }

    /** Every allowed `(from -> to)` transition. */
    val allowed: Map<TranslationState, Set<TranslationState>> = mapOf(
        TranslationState.DRAFT to setOf(TranslationState.IN_REVIEW, TranslationState.ARCHIVED),
        TranslationState.AI_GENERATED to setOf(TranslationState.IN_REVIEW, TranslationState.DRAFT, TranslationState.ARCHIVED),
        TranslationState.IN_REVIEW to setOf(TranslationState.APPROVED, TranslationState.REJECTED),
        TranslationState.REJECTED to setOf(TranslationState.DRAFT, TranslationState.ARCHIVED),
        TranslationState.APPROVED to setOf(TranslationState.PUBLISHED, TranslationState.DRAFT, TranslationState.ARCHIVED),
        TranslationState.PUBLISHED to setOf(TranslationState.ARCHIVED, TranslationState.DRAFT),
        TranslationState.ARCHIVED to setOf(TranslationState.DRAFT)
    )

    /** True iff `from -> to` is a permitted transition. */
    fun canTransition(from: TranslationState, to: TranslationState): Boolean =
        allowed[from]?.contains(to) == true

    /**
     * Enforces [canTransition] and throws with a human-readable message when invalid.
     * Returns silently when the transition is allowed so callers can use it as a guard.
     */
    fun requireTransition(from: TranslationState, to: TranslationState) {
        if (!canTransition(from, to)) {
            throw IllegalStateException("Invalid translation state transition: $from -> $to")
        }
    }

    private val reviewedStates = setOf(
        TranslationState.IN_REVIEW,
        TranslationState.APPROVED,
        TranslationState.PUBLISHED
    )

    /**
     * Returns the state a translation should take when its text is edited.
     * Archived translations cannot be edited — they must be explicitly restored first.
     * Translations that have passed into or beyond review are reset to [TranslationState.DRAFT]
     * so they go through the review cycle again; earlier states are preserved.
     */
    fun stateAfterTextEdit(currentState: TranslationState, newOrigin: TranslationOrigin): TranslationState {
        require(currentState != TranslationState.ARCHIVED) {
            "Cannot edit an archived translation — restore it to draft first"
        }
        return if (currentState in reviewedStates) TranslationState.DRAFT else currentState
    }
}
