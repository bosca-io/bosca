package bosca.localization.workflow

import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationStateMachineTest {

    @Test
    fun `AI origin enters AI_GENERATED state so review is forced before publish`() {
        assertEquals(TranslationState.AI_GENERATED, TranslationStateMachine.initialState(TranslationOrigin.AI))
    }

    @Test
    fun `human, import, and sync origins all enter DRAFT`() {
        assertEquals(TranslationState.DRAFT, TranslationStateMachine.initialState(TranslationOrigin.HUMAN))
        assertEquals(TranslationState.DRAFT, TranslationStateMachine.initialState(TranslationOrigin.IMPORT))
        assertEquals(TranslationState.DRAFT, TranslationStateMachine.initialState(TranslationOrigin.SYNC))
    }

    @Test
    fun `every allowed edge in the documented graph is accepted`() {
        val edges = listOf(
            TranslationState.DRAFT to TranslationState.IN_REVIEW,
            TranslationState.DRAFT to TranslationState.ARCHIVED,
            TranslationState.AI_GENERATED to TranslationState.IN_REVIEW,
            TranslationState.AI_GENERATED to TranslationState.DRAFT,
            TranslationState.AI_GENERATED to TranslationState.ARCHIVED,
            TranslationState.IN_REVIEW to TranslationState.APPROVED,
            TranslationState.IN_REVIEW to TranslationState.REJECTED,
            TranslationState.REJECTED to TranslationState.DRAFT,
            TranslationState.REJECTED to TranslationState.ARCHIVED,
            TranslationState.APPROVED to TranslationState.PUBLISHED,
            TranslationState.APPROVED to TranslationState.DRAFT,
            TranslationState.APPROVED to TranslationState.ARCHIVED,
            TranslationState.PUBLISHED to TranslationState.ARCHIVED,
            TranslationState.PUBLISHED to TranslationState.DRAFT,
            TranslationState.ARCHIVED to TranslationState.DRAFT,
        )
        for ((from, to) in edges) {
            assertTrue(TranslationStateMachine.canTransition(from, to), "expected $from -> $to to be allowed")
        }
    }

    @Test
    fun `every disallowed edge in the workflow is rejected`() {
        val disallowed = listOf(
            TranslationState.DRAFT to TranslationState.PUBLISHED,
            TranslationState.DRAFT to TranslationState.APPROVED,
            TranslationState.DRAFT to TranslationState.REJECTED,
            TranslationState.DRAFT to TranslationState.AI_GENERATED,
            TranslationState.AI_GENERATED to TranslationState.PUBLISHED,
            TranslationState.AI_GENERATED to TranslationState.APPROVED,
            TranslationState.AI_GENERATED to TranslationState.REJECTED,
            TranslationState.IN_REVIEW to TranslationState.PUBLISHED,
            TranslationState.IN_REVIEW to TranslationState.DRAFT,
            TranslationState.REJECTED to TranslationState.PUBLISHED,
            TranslationState.REJECTED to TranslationState.APPROVED,
            TranslationState.REJECTED to TranslationState.IN_REVIEW,
            TranslationState.APPROVED to TranslationState.REJECTED,
            TranslationState.APPROVED to TranslationState.IN_REVIEW,
            TranslationState.PUBLISHED to TranslationState.APPROVED,
            TranslationState.PUBLISHED to TranslationState.IN_REVIEW,
            TranslationState.PUBLISHED to TranslationState.REJECTED,
            TranslationState.ARCHIVED to TranslationState.PUBLISHED,
            TranslationState.ARCHIVED to TranslationState.APPROVED,
            TranslationState.ARCHIVED to TranslationState.IN_REVIEW,
        )
        for ((from, to) in disallowed) {
            assertFalse(TranslationStateMachine.canTransition(from, to), "expected $from -> $to to be disallowed")
        }
    }

    @Test
    fun `self-transitions are rejected for every state`() {
        for (state in TranslationState.entries) {
            assertFalse(TranslationStateMachine.canTransition(state, state), "$state -> $state should not be allowed")
        }
    }

    @Test
    fun `requireTransition throws with a descriptive message on invalid edges`() {
        val ex = assertFailsWith<IllegalStateException> {
            TranslationStateMachine.requireTransition(TranslationState.DRAFT, TranslationState.PUBLISHED)
        }
        assertTrue(ex.message!!.contains("DRAFT"), "message should name the source state")
        assertTrue(ex.message!!.contains("PUBLISHED"), "message should name the target state")
    }

    @Test
    fun `requireTransition does not throw on valid edges`() {
        TranslationStateMachine.requireTransition(TranslationState.DRAFT, TranslationState.IN_REVIEW)
        TranslationStateMachine.requireTransition(TranslationState.ARCHIVED, TranslationState.DRAFT)
    }

    @Test
    fun `stateAfterTextEdit resets reviewed states to DRAFT`() {
        assertEquals(TranslationState.DRAFT, TranslationStateMachine.stateAfterTextEdit(TranslationState.IN_REVIEW, TranslationOrigin.HUMAN))
        assertEquals(TranslationState.DRAFT, TranslationStateMachine.stateAfterTextEdit(TranslationState.APPROVED, TranslationOrigin.HUMAN))
        assertEquals(TranslationState.DRAFT, TranslationStateMachine.stateAfterTextEdit(TranslationState.PUBLISHED, TranslationOrigin.HUMAN))
    }

    @Test
    fun `stateAfterTextEdit preserves pre-review states`() {
        assertEquals(TranslationState.DRAFT, TranslationStateMachine.stateAfterTextEdit(TranslationState.DRAFT, TranslationOrigin.HUMAN))
        assertEquals(TranslationState.AI_GENERATED, TranslationStateMachine.stateAfterTextEdit(TranslationState.AI_GENERATED, TranslationOrigin.AI))
        assertEquals(TranslationState.REJECTED, TranslationStateMachine.stateAfterTextEdit(TranslationState.REJECTED, TranslationOrigin.HUMAN))
    }

    @Test
    fun `stateAfterTextEdit rejects edits to archived translations`() {
        assertFailsWith<IllegalArgumentException> {
            TranslationStateMachine.stateAfterTextEdit(TranslationState.ARCHIVED, TranslationOrigin.HUMAN)
        }
    }
}
