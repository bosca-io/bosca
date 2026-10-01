package bosca.localization.graphql

import bosca.localization.model.TranslationState
import bosca.security.model.PermissionAction
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Table-driven test for the permission mapping in [LocalizationMutationController].
 *
 * `requiredActionForTransition(fromState, toState)` determines which project-scoped
 * permission a caller needs for a state transition. This mapping is the single
 * chokepoint for authorization on all translation/plural/document transitions, so
 * getting it wrong would be a security defect.
 */
class MutationPermissionMatrixTest {

    private val controller = LocalizationMutationController(
        service = mockk(),
        syncService = mockk(),
        evaluator = mockk(),
        groupEvaluator = mockk()
    )

    @Test
    fun `IN_REVIEW requires EDIT -- contributors can submit their own work for review`() {
        assertEquals(PermissionAction.EDIT, controller.requiredActionForTransition(TranslationState.DRAFT, TranslationState.IN_REVIEW))
    }

    @Test
    fun `DRAFT from early states requires EDIT -- contributors can reset to draft`() {
        assertEquals(PermissionAction.EDIT, controller.requiredActionForTransition(TranslationState.AI_GENERATED, TranslationState.DRAFT))
        assertEquals(PermissionAction.EDIT, controller.requiredActionForTransition(TranslationState.REJECTED, TranslationState.DRAFT))
        assertEquals(PermissionAction.EDIT, controller.requiredActionForTransition(TranslationState.ARCHIVED, TranslationState.DRAFT))
    }

    @Test
    fun `DRAFT from APPROVED or PUBLISHED requires MANAGE -- reverting reviewed work is privileged`() {
        assertEquals(PermissionAction.MANAGE, controller.requiredActionForTransition(TranslationState.APPROVED, TranslationState.DRAFT))
        assertEquals(PermissionAction.MANAGE, controller.requiredActionForTransition(TranslationState.PUBLISHED, TranslationState.DRAFT))
    }

    @Test
    fun `APPROVED requires MANAGE -- only managers can approve`() {
        assertEquals(PermissionAction.MANAGE, controller.requiredActionForTransition(TranslationState.IN_REVIEW, TranslationState.APPROVED))
    }

    @Test
    fun `REJECTED requires MANAGE -- only managers can reject`() {
        assertEquals(PermissionAction.MANAGE, controller.requiredActionForTransition(TranslationState.IN_REVIEW, TranslationState.REJECTED))
    }

    @Test
    fun `PUBLISHED requires MANAGE -- only managers can publish`() {
        assertEquals(PermissionAction.MANAGE, controller.requiredActionForTransition(TranslationState.APPROVED, TranslationState.PUBLISHED))
    }

    @Test
    fun `ARCHIVED requires MANAGE -- only managers can archive`() {
        assertEquals(PermissionAction.MANAGE, controller.requiredActionForTransition(TranslationState.DRAFT, TranslationState.ARCHIVED))
        assertEquals(PermissionAction.MANAGE, controller.requiredActionForTransition(TranslationState.PUBLISHED, TranslationState.ARCHIVED))
    }

    @Test
    fun `every valid transition is covered by EDIT or MANAGE`() {
        val validTransitions = listOf(
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
        for ((from, to) in validTransitions) {
            val action = controller.requiredActionForTransition(from, to)
            assert(action == PermissionAction.EDIT || action == PermissionAction.MANAGE) {
                "unmapped transition: $from -> $to"
            }
        }
    }
}
