package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.harness.KitHarnessService
import bosca.security.service.AuthenticationContext
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChatHarnessLifecycleControllerTest {
    private val owner = UUID.random()
    private val session = ChatSession(UUID.random(), owner, agentKey = "kit")
    private val principal = mockk<AuthenticatedPrincipal> { every { id } returns owner }
    private val auth = mockk<AuthenticationContext> { every { principal() } returns this@ChatHarnessLifecycleControllerTest.principal }
    private val history = mockk<ChatHistoryService> { coEvery { getSession(session.id) } returns session }
    private val harness = mockk<KitHarnessService>(relaxed = true)
    private val controller = ChatSessionsMutationController(history, harness, mockk())

    @Test fun `owner can archive and resume without another user message`() = runTest {
        assertTrue(controller.archiveHarness(auth, session.id))
        assertTrue(controller.activateHarness(auth, session.id))
        coVerify(exactly = 1) { harness.retire(session.id) }
        coVerify(exactly = 1) { harness.activate(auth, session.id) }
        coVerify(exactly = 0) { harness.send(any(), any(), any()) }
    }

    @Test fun `foreign and unauthenticated callers cannot operate runtimes`() = runTest {
        every { principal.id } returns UUID.random()
        assertFailsWith<SecurityException> { controller.activateHarness(auth, session.id) }
        assertFailsWith<SecurityException> { controller.archiveHarness(auth, session.id) }
        every { auth.principal() } returns null
        assertFailsWith<SecurityException> { controller.activateHarness(auth, session.id) }
        coVerify(exactly = 0) { harness.activate(any(), any()) }
        coVerify(exactly = 0) { harness.retire(any()) }
    }
}
