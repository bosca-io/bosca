package bosca.ai.agents.graphql

import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpTransportType
import bosca.security.service.GroupEvaluator
import bosca.security.service.AuthenticationContext
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class McpServerRegistrationControllerTest {

    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = McpServerRegistrationController(groupEvaluator)

    private val testConfig = JsonObject(mapOf("url" to JsonPrimitive("http://localhost:3000")))

    private val testRegistration = McpServerRegistration(
        id = Uuid.random(),
        key = "test-server",
        name = "Test Server",
        description = "A test MCP server",
        transportType = McpTransportType.STREAMABLE_HTTP,
        configuration = testConfig,
        enabled = true
    )

    @Test
    fun `id returns registration id`() {
        assertEquals(testRegistration.id, controller.id(testRegistration))
    }

    @Test
    fun `key returns registration key`() {
        assertEquals("test-server", controller.key(testRegistration))
    }

    @Test
    fun `name returns registration name`() {
        assertEquals("Test Server", controller.name(testRegistration))
    }

    @Test
    fun `description returns registration description`() {
        assertEquals("A test MCP server", controller.description(testRegistration))
    }

    @Test
    fun `transportType returns registration transport type`() {
        assertEquals(McpTransportType.STREAMABLE_HTTP, controller.transportType(testRegistration))
    }

    @Test
    fun `enabled returns registration enabled flag`() {
        assertEquals(true, controller.enabled(testRegistration))
    }

    @Test
    fun `configuration returns value for agent manager`() {
        val auth = mockk<AuthenticationContext>()
        every { groupEvaluator.hasGroup(auth, "agent.manager") } returns true
        every { groupEvaluator.hasAdminGroup(auth) } returns false

        val result = controller.configuration(auth, testRegistration)

        assertEquals(testConfig, result)
    }

    @Test
    fun `configuration returns value for admin`() {
        val auth = mockk<AuthenticationContext>()
        every { groupEvaluator.hasGroup(auth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        val result = controller.configuration(auth, testRegistration)

        assertEquals(testConfig, result)
    }

    @Test
    fun `configuration returns null for unauthorized user`() {
        val auth = mockk<AuthenticationContext>()
        every { groupEvaluator.hasGroup(auth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(auth) } returns false

        val result = controller.configuration(auth, testRegistration)

        assertNull(result)
    }

    @Test
    fun `configuration returns null for null authentication`() {
        every { groupEvaluator.hasGroup(null, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(null) } returns false

        val result = controller.configuration(null, testRegistration)

        assertNull(result)
    }
}
