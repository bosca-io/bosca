package bosca.ai.kit.harness.runtime

import bosca.ai.agents.model.*
import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.McpServerRegistrationService
import bosca.ai.kit.harness.*
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class KitMcpResolverTest {
    private val json = Json
    private var agent = Agent(UUID.random(), "kit", "Kit", "", UUID.random(), UUID.random())
    private var registration = McpServerRegistration(UUID.random(), "docs", "Documents", transportType = McpTransportType.STREAMABLE_HTTP,
        configuration = buildJsonObject { put("harness", json.encodeToJsonElement(KitMcpConfiguration.serializer(), KitMcpConfiguration(
            url = "https://approved.test/mcp", headers = mapOf("Authorization" to KitMcpSecretReference("DOCS_ACCESS", "Bearer "))))) })
    private val agents = mockk<AgentService> {
        coEvery { getByKey("kit") } answers { agent }
        coEvery { getTools(agent.id) } answers { listOf(AgentTool(key = "docs-tool", name = "Docs", description = "", mcpServerId = registration.id)) }
    }
    private val registrations = mockk<McpServerRegistrationService> {
        coEvery { get(any<UUID>()) } answers { registration }
    }
    private val resolver = KitMcpResolver(agents, registrations, json)
    private val profile = KitHarnessConfiguration("codex", "http://runtime", "http://bosca", scopes = listOf("content:view"),
        image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64), versions = RuntimeVersions("test", "test", "test", "codex-0.160.1"),
        mcpExecutables = mapOf("local-docs" to listOf("/opt/integrations/docs", "--stdio")), mcpSecretEnvironment = listOf("DOCS_ACCESS"))

    @Test fun `assigned registration resolves references only and never enumerates the registry`() = runTest {
        val result = resolver.catalog("kit", profile)
        val connection = result.connections.single()
        assertEquals("docs", connection.key)
        assertEquals("DOCS_ACCESS", connection.headers.getValue("Authorization").environment)
        assertEquals(listOf(KitMcpOption("docs", "Documents", true)), result.options)
        coVerify(exactly = 0) { registrations.getAll() }
        coVerify(exactly = 0) { registrations.getAllEnabled() }
    }

    @Test fun `selection cannot add registrations outside Agent tools`() = runTest {
        agent = agent.copy(configuration = buildJsonObject { putJsonArray("harnessMcp") { add(buildJsonObject { put("key", "unassigned") }) } })
        assertContains(assertFailsWith<KitMcpConfigurationException> { resolver.catalog("kit", profile) }.message.orEmpty(), "not assigned")
        coVerify(exactly = 0) { registrations.getByKey(any()) }
    }

    @Test fun `disabled required blocks and disabled optional stays visible`() = runTest {
        registration = registration.copy(enabled = false)
        assertContains(assertFailsWith<KitMcpConfigurationException> { resolver.catalog("kit", profile) }.message.orEmpty(), "docs is disabled")
        optional()
        val result = resolver.catalog("kit", profile)
        assertTrue(result.connections.isEmpty())
        assertFalse(result.options.single().required)
        assertContains(result.notices.single(), "docs is disabled")
    }

    @Test fun `stdio command resolves only from packaged executable profile`() = runTest {
        registration = registration.copy(transportType = McpTransportType.STDIO,
            configuration = buildJsonObject { put("harness", json.encodeToJsonElement(KitMcpConfiguration.serializer(), KitMcpConfiguration(executable = "local-docs"))) })
        assertEquals(listOf("/opt/integrations/docs", "--stdio"), resolver.catalog("kit", profile).connections.single().command)
        assertFailsWith<KitMcpConfigurationException> { resolver.catalog("kit", profile.copy(mcpExecutables = emptyMap())) }
        registration = registration.copy(configuration = buildJsonObject { putJsonObject("harness") { put("command", "/bin/sh"); put("args", "unsafe") } })
        assertFailsWith<KitMcpConfigurationException> { resolver.catalog("kit", profile) }
    }

    @Test fun `secret references cannot reach Bosca control or unapproved provider environment`() = runTest {
        for (name in listOf("BOSCA_TOKEN", "BOSCA_RUNTIME_TOKEN", "OPENAI_API_KEY")) {
            registration = registration.copy(configuration = buildJsonObject { put("harness", json.encodeToJsonElement(KitMcpConfiguration.serializer(),
                KitMcpConfiguration(url = "https://approved.test/mcp", headers = mapOf("Authorization" to KitMcpSecretReference(name))))) })
            assertFailsWith<KitMcpConfigurationException> { resolver.catalog("kit", profile) }
        }
    }

    @Test fun `inline credentials invalid endpoints and arbitrary fields never enter plans or errors`() = runTest {
        for (url in listOf("https://user:secret-value@approved.test/mcp", "https://approved.test/mcp?token=secret-value", "file:///tmp/secret-value", "https://[secret-value")) {
            registration = registration.copy(configuration = buildJsonObject { putJsonObject("harness") { put("url", url) } })
            val error = assertFailsWith<KitMcpConfigurationException> { resolver.catalog("kit", profile) }
            assertFalse(error.message.orEmpty().contains("secret-value"))
        }
        registration = registration.copy(configuration = buildJsonObject { putJsonObject("harness") { put("url", "https://approved.test"); put("token", "secret-value") } })
        assertFailsWith<KitMcpConfigurationException> { resolver.catalog("kit", profile) }
    }

    private fun optional() {
        agent = agent.copy(configuration = buildJsonObject { putJsonArray("harnessMcp") { add(buildJsonObject { put("key", "docs"); put("required", false) }) } })
    }
}
