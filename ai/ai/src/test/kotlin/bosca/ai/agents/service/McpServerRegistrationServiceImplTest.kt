package bosca.ai.agents.service

import bosca.ai.agents.model.McpServerRegistration
import bosca.ai.agents.model.McpServerRegistrationInput
import bosca.ai.agents.model.McpTransportType
import bosca.ai.agents.repository.McpServerRegistrationRepository
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class McpServerRegistrationServiceImplTest {

    private val repository = mockk<McpServerRegistrationRepository>()
    private val service = McpServerRegistrationServiceImpl(repository)

    private val testConfig = JsonObject(mapOf("url" to JsonPrimitive("http://localhost:3000")))

    private val testRegistration = McpServerRegistration(
        id = Uuid.random(),
        key = "test-server",
        name = "Test Server",
        description = "A test MCP server",
        transportType = McpTransportType.SSE,
        configuration = testConfig,
        enabled = true
    )

    @Test
    fun `getAll delegates to repository`() = runTest {
        val registrations = listOf(testRegistration)
        coEvery { repository.getAll() } returns registrations

        val result = service.getAll()

        assertEquals(registrations, result)
        coVerify { repository.getAll() }
    }

    @Test
    fun `getAll returns empty list when no registrations exist`() = runTest {
        coEvery { repository.getAll() } returns emptyList()

        val result = service.getAll()

        assertEquals(emptyList(), result)
    }

    @Test
    fun `getAllEnabled delegates to repository`() = runTest {
        val registrations = listOf(testRegistration)
        coEvery { repository.getAllEnabled() } returns registrations

        val result = service.getAllEnabled()

        assertEquals(registrations, result)
        coVerify { repository.getAllEnabled() }
    }

    @Test
    fun `get delegates to repository`() = runTest {
        coEvery { repository.getById(testRegistration.id) } returns testRegistration

        val result = service.get(testRegistration.id)

        assertEquals(testRegistration, result)
    }

    @Test
    fun `get returns null when not found`() = runTest {
        val id = Uuid.random()
        coEvery { repository.getById(id) } returns null

        val result = service.get(id)

        assertNull(result)
    }

    @Test
    fun `getByKey delegates to repository`() = runTest {
        coEvery { repository.getByKey("test-server") } returns testRegistration

        val result = service.getByKey("test-server")

        assertEquals(testRegistration, result)
    }

    @Test
    fun `getByKey returns null when not found`() = runTest {
        coEvery { repository.getByKey("missing") } returns null

        val result = service.getByKey("missing")

        assertNull(result)
    }

    @Test
    fun `add converts input to registration and delegates to repository`() = runTest {
        val input = McpServerRegistrationInput(
            key = "new-server",
            name = "New Server",
            description = "A new server",
            transportType = McpTransportType.STDIO,
            configuration = testConfig,
            enabled = true
        )
        val saved = McpServerRegistration(
            id = Uuid.random(),
            key = input.key,
            name = input.name,
            description = input.description,
            transportType = input.transportType,
            configuration = input.configuration,
            enabled = input.enabled
        )

        val registrationSlot = slot<McpServerRegistration>()
        coEvery { repository.add(capture(registrationSlot)) } returns saved

        val result = service.add(input)

        assertEquals(saved, result)
        val captured = registrationSlot.captured
        assertEquals(input.key, captured.key)
        assertEquals(input.name, captured.name)
        assertEquals(input.description, captured.description)
        assertEquals(input.transportType, captured.transportType)
        assertEquals(input.configuration, captured.configuration)
        assertEquals(input.enabled, captured.enabled)
    }

    @Test
    fun `edit updates existing registration`() = runTest {
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        withContext(connectionManager.asCoroutineContext()) {
            val id = testRegistration.id
            val input = McpServerRegistrationInput(
                key = "updated-key",
                name = "Updated Name",
                description = "Updated description",
                transportType = McpTransportType.STREAMABLE_HTTP,
                configuration = JsonObject(mapOf("endpoint" to JsonPrimitive("http://new"))),
                enabled = false
            )
            val updated = testRegistration.copy(
                key = input.key,
                name = input.name,
                description = input.description,
                transportType = input.transportType,
                configuration = input.configuration,
                enabled = input.enabled
            )

            coEvery { repository.getById(id) } returns testRegistration
            val registrationSlot = slot<McpServerRegistration>()
            coEvery { repository.update(capture(registrationSlot)) } returns updated

            val result = service.edit(id, input)

            assertEquals(updated, result)
            val captured = registrationSlot.captured
            assertEquals(id, captured.id)
            assertEquals(input.key, captured.key)
            assertEquals(input.name, captured.name)
            assertEquals(input.description, captured.description)
            assertEquals(input.transportType, captured.transportType)
            assertEquals(input.configuration, captured.configuration)
            assertEquals(input.enabled, captured.enabled)
        }
    }

    @Test
    fun `edit throws NoSuchElementException when registration not found`() = runTest {
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        withContext(connectionManager.asCoroutineContext()) {
            val id = Uuid.random()
            val input = McpServerRegistrationInput(
                key = "k",
                name = "n",
                transportType = McpTransportType.SSE,
                configuration = testConfig
            )

            coEvery { repository.getById(id) } returns null

            assertFailsWith<NoSuchElementException> {
                service.edit(id, input)
            }
        }
    }

    @Test
    fun `delete delegates to repository`() = runTest {
        val id = testRegistration.id
        coEvery { repository.getById(id) } returns testRegistration
        coEvery { repository.deleteById(id) } returns Unit

        val result = service.delete(id)

        assertEquals(true, result)
        coVerify { repository.deleteById(id) }
    }

    @Test
    fun `delete returns false when entity not found`() = runTest {
        val id = Uuid.random()
        coEvery { repository.getById(id) } returns null

        val result = service.delete(id)

        assertEquals(false, result)
    }
}
