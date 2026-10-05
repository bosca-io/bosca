package bosca.ai.agents.graphql

import bosca.ai.agents.git.AgentGitSyncService
import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpServerRegistrationInput
import bosca.ai.agents.model.McpTransportType
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.di.ObjectProvider
import bosca.security.service.GroupEvaluator
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class McpServersMutationControllerTest {

    private val service = mockk<McpServerRegistrationService>()
    private val gitSyncProvider = mockk<ObjectProvider<AgentGitSyncService>>().also {
        every { it.exists } returns false
    }
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = McpServersMutationController(service, gitSyncProvider, groupEvaluator)

    private val testConfig = JsonObject(mapOf("url" to JsonPrimitive("http://localhost:3000")))

    private val testInput = McpServerRegistrationInput(
        key = "test-server",
        name = "Test Server",
        description = "Test",
        transportType = McpTransportType.SSE,
        configuration = testConfig,
        enabled = true
    )

    private val testRegistration = McpServerRegistration(
        id = Uuid.random(),
        key = testInput.key,
        name = testInput.name,
        description = testInput.description,
        transportType = testInput.transportType,
        configuration = testInput.configuration,
        enabled = testInput.enabled
    )

    private val agentManagerAuth = mockk<AuthenticationContext>()
    private val adminAuth = mockk<AuthenticationContext>()
    private val unauthorizedAuth = mockk<AuthenticationContext>()

    // --- add ---

    @Test
    fun `add succeeds for agent manager`() = runTest {
        every { groupEvaluator.hasGroup(agentManagerAuth, "agent.manager") } returns true
        every { groupEvaluator.hasAdminGroup(agentManagerAuth) } returns false
        coEvery { service.add(testInput) } returns testRegistration

        val result = controller.add(agentManagerAuth, testInput)

        assertEquals(testRegistration, result)
    }

    @Test
    fun `add succeeds for admin`() = runTest {
        every { groupEvaluator.hasGroup(adminAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(adminAuth) } returns true
        coEvery { service.add(testInput) } returns testRegistration

        val result = controller.add(adminAuth, testInput)

        assertEquals(testRegistration, result)
    }

    @Test
    fun `add throws SecurityException for unauthorized user`() = runTest {
        every { groupEvaluator.hasGroup(unauthorizedAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(unauthorizedAuth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.add(unauthorizedAuth, testInput)
        }
    }

    // --- edit ---

    @Test
    fun `edit succeeds for agent manager`() = runTest {
        val id = testRegistration.id
        every { groupEvaluator.hasGroup(agentManagerAuth, "agent.manager") } returns true
        every { groupEvaluator.hasAdminGroup(agentManagerAuth) } returns false
        coEvery { service.edit(id, testInput) } returns testRegistration

        val result = controller.edit(agentManagerAuth, id, testInput)

        assertEquals(testRegistration, result)
    }

    @Test
    fun `edit succeeds for admin`() = runTest {
        val id = testRegistration.id
        every { groupEvaluator.hasGroup(adminAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(adminAuth) } returns true
        coEvery { service.edit(id, testInput) } returns testRegistration

        val result = controller.edit(adminAuth, id, testInput)

        assertEquals(testRegistration, result)
    }

    @Test
    fun `edit throws SecurityException for unauthorized user`() = runTest {
        every { groupEvaluator.hasGroup(unauthorizedAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(unauthorizedAuth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.edit(unauthorizedAuth, Uuid.random(), testInput)
        }
    }

    // --- delete ---

    @Test
    fun `delete succeeds for agent manager and returns true`() = runTest {
        val id = testRegistration.id
        every { groupEvaluator.hasGroup(agentManagerAuth, "agent.manager") } returns true
        every { groupEvaluator.hasAdminGroup(agentManagerAuth) } returns false
        coEvery { service.delete(id) } returns true

        val result = controller.delete(agentManagerAuth, id)

        assertTrue(result)
        coVerify { service.delete(id) }
    }

    @Test
    fun `delete succeeds for admin and returns true`() = runTest {
        val id = testRegistration.id
        every { groupEvaluator.hasGroup(adminAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(adminAuth) } returns true
        coEvery { service.delete(id) } returns true

        val result = controller.delete(adminAuth, id)

        assertTrue(result)
    }

    @Test
    fun `delete throws SecurityException for unauthorized user`() = runTest {
        every { groupEvaluator.hasGroup(unauthorizedAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(unauthorizedAuth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.delete(unauthorizedAuth, Uuid.random())
        }
    }
}
