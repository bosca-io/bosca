package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatDispatcher
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.harness.KitHarnessService
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ChatHarnessAuthorizationTest {
    private val history = mockk<ChatHistoryService>()
    private val harness = mockk<KitHarnessService>(relaxed = true)
    private val controller = ChatSessionsMutationController(history, harness, mockk<ChatDispatcher>())
    private val id = UUID.random()
    private val owner = UUID.random()
    private val approval = UUID.random()

    @Test
    fun `only the conversation owner can cancel or answer native permissions`(): Unit = runBlocking {
        val session = mockk<ChatSession> { every { principalId } returns owner }
        coEvery { history.getSession(id) } returns session
        val intruder = mockk<AuthenticationContext> { every { principal()?.id } returns UUID.random() }
        assertFailsWith<SecurityException> { controller.cancel(intruder, id) }
        assertFailsWith<SecurityException> { controller.decideHarnessPermission(intruder, id, approval, "allow") }
        assertFailsWith<SecurityException> { controller.setHarnessSettings(intruder, id, null) }
        coVerify(exactly = 0) { harness.cancel(any()) }
        coVerify(exactly = 0) { harness.decide(any(), any(), any(), any()) }
        val authentication = mockk<AuthenticationContext> { every { principal()?.id } returns owner }
        assertTrue(controller.cancel(authentication, id))
        assertTrue(controller.decideHarnessPermission(authentication, id, approval, "deny"))
        coVerify(exactly = 1) { harness.cancel(id) }
        coVerify(exactly = 1) { harness.decide(authentication, id, approval, "deny") }
        coEvery { harness.verifySettings(authentication, id, any()) } throws IllegalArgumentException("Not allowed")
        assertFailsWith<IllegalArgumentException> { controller.setHarnessSettings(authentication, id, bosca.ai.kit.harness.KitHarnessChoice("forged", "old")) }
        coVerify(exactly = 0) { harness.setSettings(any(), any()) }
    }
}
