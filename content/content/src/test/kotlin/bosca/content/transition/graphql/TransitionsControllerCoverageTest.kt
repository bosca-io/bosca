package bosca.content.transition.graphql

import bosca.content.transition.model.Transition
import bosca.content.transition.service.TransitionService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TransitionsControllerCoverageTest {

    private val service = mockk<TransitionService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = TransitionsController(
        service = service,
        groupEvaluator = groupEvaluator,
    )

    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun transition(
        fromStateId: String = "draft",
        toStateId: String = "published",
    ) = Transition(
        fromStateId = fromStateId,
        toStateId = toStateId,
        description = "d",
        enterJobName = null,
        exitJobName = null,
        configuration = null,
    )

    // ---- marker object ----

    @Test
    fun `Transitions marker object is a singleton`() {
        assertEquals(Transitions, Transitions)
    }

    // ---- all ----

    @Test
    fun `all verifies editor group and returns service results`() = runTest {
        val transitions = listOf(transition(), transition(fromStateId = "review", toStateId = "draft"))
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.getAll() } returns transitions

        assertEquals(transitions, controller.all(authentication))
        coVerify(exactly = 1) { service.getAll() }
    }

    @Test
    fun `all returns empty list when service has none`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.getAll() } returns emptyList()

        assertEquals(emptyList(), controller.all(authentication))
    }

    @Test
    fun `all throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> { controller.all(authentication) }
        coVerify(exactly = 0) { service.getAll() }
    }

    // ---- transition ----

    @Test
    fun `transition verifies editor group and returns service result`() = runTest {
        val transition = transition()
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.get("draft", "published") } returns transition

        assertEquals(transition, controller.transition(authentication, "draft", "published"))
        coVerify(exactly = 1) { service.get("draft", "published") }
    }

    @Test
    fun `transition returns null when service has no match`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.get("draft", "archived") } returns null

        assertNull(controller.transition(authentication, "draft", "archived"))
        coVerify(exactly = 1) { service.get("draft", "archived") }
    }

    @Test
    fun `transition throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> { controller.transition(authentication, "draft", "published") }
        coVerify(exactly = 0) { service.get(any(), any()) }
    }
}
