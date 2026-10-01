package bosca.ai.agents.graphql

import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpTransportType
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.security.service.GroupEvaluator
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class McpServersControllerTest {

    private val service = mockk<McpServerRegistrationService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = McpServersController(service, groupEvaluator)

    private val testConfig = JsonObject(mapOf("url" to JsonPrimitive("http://localhost:3000")))

    private val testRegistration = McpServerRegistration(
        id = Uuid.random(),
        key = "test-server",
        name = "Test Server",
        description = "Test",
        transportType = McpTransportType.SSE,
        configuration = testConfig,
        enabled = true
    )

    private val adminAuth = mockk<AuthenticationContext>()
    private val nonAdminAuth = mockk<AuthenticationContext>()

    private fun mockAdminAuth() {
        every { groupEvaluator.hasGroup(adminAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(adminAuth) } returns true
    }

    private fun mockNonAdminAuth() {
        every { groupEvaluator.hasGroup(nonAdminAuth, "agent.manager") } returns false
        every { groupEvaluator.hasAdminGroup(nonAdminAuth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("Unauthorized access")
    }

    @Test
    fun `all returns registrations for admin`() = runTest {
        val registrations = listOf(testRegistration)
        mockAdminAuth()
        coEvery { service.getAll() } returns registrations

        val result = controller.all(adminAuth)

        assertEquals(registrations, result)
    }

    @Test
    fun `all throws SecurityException for non-admin`() = runTest {
        mockNonAdminAuth()

        assertFailsWith<SecurityException> {
            controller.all(nonAdminAuth)
        }
    }

    @Test
    fun `enabled returns enabled registrations for admin`() = runTest {
        val registrations = listOf(testRegistration)
        mockAdminAuth()
        coEvery { service.getAllEnabled() } returns registrations

        val result = controller.enabled(adminAuth)

        assertEquals(registrations, result)
    }

    @Test
    fun `enabled throws SecurityException for non-admin`() = runTest {
        mockNonAdminAuth()

        assertFailsWith<SecurityException> {
            controller.enabled(nonAdminAuth)
        }
    }

    @Test
    fun `server returns registration by id for admin`() = runTest {
        mockAdminAuth()
        coEvery { service.get(testRegistration.id) } returns testRegistration

        val result = controller.server(adminAuth, testRegistration.id)

        assertEquals(testRegistration, result)
    }

    @Test
    fun `server returns null when not found`() = runTest {
        val id = Uuid.random()
        mockAdminAuth()
        coEvery { service.get(id) } returns null

        val result = controller.server(adminAuth, id)

        assertNull(result)
    }

    @Test
    fun `server throws SecurityException for non-admin`() = runTest {
        mockNonAdminAuth()

        assertFailsWith<SecurityException> {
            controller.server(nonAdminAuth, testRegistration.id)
        }
    }

    @Test
    fun `serverByKey returns registration for admin`() = runTest {
        mockAdminAuth()
        coEvery { service.getByKey("test-server") } returns testRegistration

        val result = controller.serverByKey(adminAuth, "test-server")

        assertEquals(testRegistration, result)
    }

    @Test
    fun `serverByKey returns null when not found`() = runTest {
        mockAdminAuth()
        coEvery { service.getByKey("missing") } returns null

        val result = controller.serverByKey(adminAuth, "missing")

        assertNull(result)
    }

    @Test
    fun `serverByKey throws SecurityException for non-admin`() = runTest {
        mockNonAdminAuth()

        assertFailsWith<SecurityException> {
            controller.serverByKey(nonAdminAuth, "test-server")
        }
    }
}
