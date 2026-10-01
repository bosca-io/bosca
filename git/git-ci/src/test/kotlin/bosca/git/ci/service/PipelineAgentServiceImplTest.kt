package bosca.git.ci.service

import bosca.git.ci.repository.PipelineAgentRepository
import bosca.git.model.AgentMode
import bosca.git.model.AgentStatus
import bosca.git.model.PipelineAgent
import bosca.security.service.ApiTokenCreationResult
import bosca.security.service.ApiTokenInput
import bosca.security.service.ApiTokenService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineAgentServiceImplTest {

    private val agentRepository = mockk<PipelineAgentRepository>(relaxed = true)
    private val apiTokenService = mockk<ApiTokenService>(relaxed = true)
    private lateinit var service: PipelineAgentServiceImpl
    private val principalId = UUID.random()

    @BeforeTest
    fun setup() {
        coEvery { agentRepository.create(any()) } answers { (firstArg() as PipelineAgent).copy(id = UUID.random()) }
        coEvery { apiTokenService.createToken(any(), any(), any()) } answers {
            ApiTokenCreationResult(
                credential = mockk(relaxed = true),
                rawToken = "bsk_test_token_${UUID.random()}"
            )
        }
        coEvery { apiTokenService.createEphemeralToken(any(), any(), any()) } answers {
            ApiTokenCreationResult(
                credential = mockk(relaxed = true),
                rawToken = "bsk_test_token_${UUID.random()}"
            )
        }
        service = PipelineAgentServiceImpl(agentRepository, apiTokenService)
    }

    @Test
    fun `register creates persistent agent with hashed token`() = runTest {
        val captured = slot<PipelineAgent>()
        coEvery { agentRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        val (agent, token) = service.register("agent-1", listOf("linux", "gpu"), AgentMode.RUNNER, principalId)

        assertNotNull(agent)
        assertTrue(token.startsWith("bsk_"))
        assertEquals("agent-1", captured.captured.name)
        assertEquals(listOf("linux", "gpu"), captured.captured.labels)
        assertEquals(AgentMode.RUNNER, captured.captured.mode)
        assertEquals(AgentStatus.OFFLINE, captured.captured.status)
        assertEquals(false, captured.captured.ephemeral)
        assertTrue(captured.captured.tokenHash.isNotBlank())
        coVerify(exactly = 1) {
            apiTokenService.createToken(
                principalId,
                match<ApiTokenInput> { it.scopes?.containsAll(listOf("ci:edit", "git:read", "git:write")) == true },
                principalId,
            )
        }
    }

    @Test
    fun `register generates unique tokens`() = runTest {
        val tokens = mutableSetOf<String>()
        repeat(10) {
            val (_, token) = service.register("agent-$it", listOf("linux"), AgentMode.RUNNER, principalId)
            tokens.add(token)
        }
        assertEquals(10, tokens.size)
    }

    @Test
    fun `registerEphemeral creates transient agent scoped to job`() = runTest {
        val jobId = UUID.random()
        val parentId = UUID.random()

        val captured = slot<PipelineAgent>()
        coEvery { agentRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }
        coEvery { agentRepository.findById(parentId) } returns null

        val (agent, token) = service.registerEphemeral(jobId, "ephemeral-1", listOf("linux"), parentId, 30, principalId)

        assertNotNull(agent)
        assertTrue(token.startsWith("bsk_"))
        assertTrue(captured.captured.ephemeral)
        assertEquals(jobId, captured.captured.jobId)
        assertEquals(parentId, captured.captured.parentAgentId)
        assertEquals(AgentMode.RUNNER, captured.captured.mode)
        assertNotNull(captured.captured.expiresAt)
        coVerify(exactly = 1) {
            apiTokenService.createToken(
                principalId,
                match<ApiTokenInput> { it.scopes?.containsAll(listOf("ci:edit", "git:read", "git:write")) == true },
                principalId,
            )
        }
    }

    @Test
    fun `registerKubernetesEphemeral creates a scoped expiring token`() = runTest {
        val jobId = UUID.random()
        val captured = slot<PipelineAgent>()
        coEvery { agentRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        val (agent, token) = service.registerKubernetesEphemeral(
            jobId,
            "kubernetes-agent",
            listOf("android"),
            lifetimeMinutes = 60,
            principalId = principalId,
        )

        assertTrue(agent.ephemeral)
        assertTrue(token.startsWith("bsk_"))
        assertEquals(jobId, agent.jobId)
        assertNull(agent.parentAgentId)
        assertEquals(listOf("android"), agent.labels)
        assertNotNull(agent.expiresAt)
        coVerify(exactly = 1) {
            apiTokenService.createEphemeralToken(
                principalId,
                match<ApiTokenInput> { it.scopes?.containsAll(listOf("ci:edit", "git:read", "git:write")) == true },
                principalId,
            )
        }
    }

    @Test
    fun `registerKubernetesEphemeral rejects a nonpositive lifetime`() = runTest {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            service.registerKubernetesEphemeral(
                UUID.random(),
                "kubernetes-agent",
                listOf("android"),
                lifetimeMinutes = 0,
                principalId = principalId,
            )
        }
        coVerify(exactly = 0) { apiTokenService.createEphemeralToken(any(), any(), any()) }
    }

    @Test
    fun `registerEphemeral caps timeout at orchestrator max`() = runTest {
        val parentId = UUID.random()
        val parent = testAgent(
            id = parentId,
            mode = AgentMode.ORCHESTRATOR,
            providerConfig = """{"maxJobTimeoutMinutes": 15}"""
        )
        coEvery { agentRepository.findById(parentId) } returns parent

        val captured = slot<PipelineAgent>()
        coEvery { agentRepository.create(capture(captured)) } answers {
            captured.captured.copy(id = UUID.random())
        }

        service.registerEphemeral(UUID.random(), "ephemeral", listOf("linux"), parentId, 60, principalId)

        assertNotNull(captured.captured.expiresAt)
    }

    @Test
    fun `registerEphemeral falls back when orchestrator timeout is malformed`() = runTest {
        val parentId = UUID.random()
        coEvery { agentRepository.findById(parentId) } returns testAgent(
            id = parentId,
            mode = AgentMode.ORCHESTRATOR,
            providerConfig =
                """{"maxJobTimeoutMinutes":999999999999999999999999999999999999999}""",
        )

        val (agent, _) = service.registerEphemeral(
            UUID.random(),
            "ephemeral",
            listOf("linux"),
            parentId,
            30,
            principalId,
        )

        assertNotNull(agent.expiresAt)
    }

    @Test
    fun `findByToken authenticates with hashed token`() = runTest {
        val (_, rawToken) = service.register("agent-1", listOf("linux"), AgentMode.RUNNER, principalId)

        val agent = testAgent()
        coEvery { agentRepository.findByTokenHash(any()) } returns agent

        val result = service.findByToken(rawToken)
        assertNotNull(result)
        coVerify { agentRepository.findByTokenHash(any()) }
    }

    @Test
    fun `findByToken returns null for invalid token`() = runTest {
        coEvery { agentRepository.findByTokenHash(any()) } returns null

        assertNull(service.findByToken("invalid-token"))
    }

    @Test
    fun `heartbeat delegates to repository`() = runTest {
        val agentId = UUID.random()
        service.heartbeat(agentId)
        coVerify { agentRepository.heartbeat(agentId) }
    }

    @Test
    fun `updateStatus delegates to repository`() = runTest {
        val agentId = UUID.random()
        service.updateStatus(agentId, AgentStatus.BUSY)
        coVerify { agentRepository.updateStatus(agentId, AgentStatus.BUSY) }
    }

    @Test
    fun `setInstanceId delegates to repository`() = runTest {
        val agentId = UUID.random()
        service.setInstanceId(agentId, "droplet-12345")
        coVerify { agentRepository.setInstanceId(agentId, "droplet-12345") }
    }

    @Test
    fun `listAgents with status filter delegates to findByStatus`() = runTest {
        val agents = listOf(testAgent(status = AgentStatus.ONLINE))
        coEvery { agentRepository.findByStatus(AgentStatus.ONLINE) } returns agents

        val result = service.listAgents(AgentStatus.ONLINE)
        assertEquals(1, result.size)
    }

    @Test
    fun `listAgents without filter delegates to findAll`() = runTest {
        val agents = listOf(testAgent(), testAgent())
        coEvery { agentRepository.findAll() } returns agents

        val result = service.listAgents(null)
        assertEquals(2, result.size)
    }

    @Test
    fun `deregister deletes agent`() = runTest {
        val agentId = UUID.random()
        coEvery { agentRepository.findById(agentId) } returns testAgent(id = agentId)
        service.deregister(agentId)
        coVerify { agentRepository.delete(agentId) }
    }

    @Test
    fun `deregister revokes and deletes the owned API token before deleting the agent`() = runTest {
        val agentId = UUID.random()
        val tokenPrincipalId = UUID.random()
        coEvery { agentRepository.findById(agentId) } returns testAgent(id = agentId).copy(
            apiTokenCredentialId = 51L,
            tokenPrincipalId = tokenPrincipalId,
        )

        service.deregister(agentId)

        coVerify { apiTokenService.revokeToken(51L, tokenPrincipalId) }
        coVerify { apiTokenService.deleteToken(51L, tokenPrincipalId) }
        coVerify { agentRepository.delete(agentId) }
    }

    @Test
    fun `deregister is idempotent for a missing agent`() = runTest {
        val agentId = UUID.random()
        coEvery { agentRepository.findById(agentId) } returns null

        service.deregister(agentId)

        coVerify(exactly = 0) { agentRepository.delete(any()) }
        coVerify(exactly = 0) { apiTokenService.revokeToken(any(), any()) }
    }

    @Test
    fun `deregister does not attempt partial credential cleanup`() = runTest {
        val agentId = UUID.random()
        coEvery { agentRepository.findById(agentId) } returns testAgent(id = agentId).copy(
            apiTokenCredentialId = 52L,
            tokenPrincipalId = null,
        )

        service.deregister(agentId)

        coVerify(exactly = 0) { apiTokenService.revokeToken(any(), any()) }
        coVerify { agentRepository.delete(agentId) }
    }

    @Test
    fun `updates agent fields and returns the refreshed row`() = runTest {
        val agentId = UUID.random()
        val updated = testAgent(id = agentId).copy(name = "renamed", labels = listOf("gpu"))
        coEvery { agentRepository.findById(agentId) } returns updated

        assertEquals(updated, service.updateAgent(agentId, "renamed", listOf("gpu")))

        coVerify { agentRepository.updateName(agentId, "renamed") }
        coVerify { agentRepository.updateLabels(agentId, "gpu") }
    }

    @Test
    fun `agent and provider updates fail if the row disappears`() = runTest {
        val agentId = UUID.random()
        coEvery { agentRepository.findById(agentId) } returns null

        kotlin.test.assertFailsWith<NoSuchElementException> {
            service.updateAgent(agentId, "renamed", listOf("linux"))
        }
        kotlin.test.assertFailsWith<NoSuchElementException> {
            service.updateProviderConfig(agentId, "{}")
        }
    }

    @Test
    fun `provider update returns refreshed orchestrator`() = runTest {
        val agentId = UUID.random()
        val updated = testAgent(
            id = agentId,
            mode = AgentMode.ORCHESTRATOR,
            providerConfig = """{"maxJobTimeoutMinutes":30}""",
        )
        coEvery { agentRepository.findById(agentId) } returns updated

        val providerConfig = requireNotNull(updated.providerConfig)
        assertEquals(updated, service.updateProviderConfig(agentId, providerConfig))
        coVerify { agentRepository.updateProviderConfig(agentId, providerConfig) }
    }

    @Test
    fun `findExpiredEphemeralAgents delegates to repository`() = runTest {
        val expired = listOf(testAgent(ephemeral = true))
        coEvery { agentRepository.findExpiredEphemeral() } returns expired

        val result = service.findExpiredEphemeralAgents()
        assertEquals(1, result.size)
    }

    @Test
    fun `findById returns agent when found`() = runTest {
        val agent = testAgent()
        coEvery { agentRepository.findById(agent.id) } returns agent
        assertNotNull(service.findById(agent.id))
    }

    @Test
    fun `findById returns null when not found`() = runTest {
        coEvery { agentRepository.findById(any()) } returns null
        assertNull(service.findById(UUID.random()))
    }

    @Test
    fun `findStalePersistentAgents delegates to repository`() = runTest {
        val stale = listOf(testAgent(status = AgentStatus.ONLINE))
        coEvery { agentRepository.findStalePersistent() } returns stale

        val result = service.findStalePersistentAgents()
        assertEquals(1, result.size)
    }

    private fun testAgent(
        id: UUID = UUID.random(),
        mode: AgentMode = AgentMode.RUNNER,
        status: AgentStatus = AgentStatus.OFFLINE,
        ephemeral: Boolean = false,
        providerConfig: String? = null
    ) = PipelineAgent(
        id = id,
        name = "test-agent",
        labels = listOf("linux"),
        mode = mode,
        status = status,
        ephemeral = ephemeral,
        tokenHash = "hash",
        providerConfig = providerConfig
    )
}
