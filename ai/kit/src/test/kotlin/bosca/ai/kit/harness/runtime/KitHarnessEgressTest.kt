package bosca.ai.kit.harness.runtime

import bosca.ai.agents.model.McpTransportType
import bosca.ai.kit.harness.*
import bosca.configuration.model.Configuration
import bosca.configuration.security.ConfigurationPermissionEvaluator
import bosca.configuration.service.ConfigurationService
import bosca.security.encryption.EncryptionService
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.*
import bosca.server.BoscaApplication
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.net.URI
import java.util.Base64
import kotlin.test.*

class KitHarnessEgressTest {
    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
    } }
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private val configuration = Configuration(UUID.random(), "kit.harness", "Harness policy", false)
    private var policy = KitHarnessPolicy("managed", listOf("managed"), egressHosts = listOf("Tools.Example.com", "files.example.com:8443"))
    private var profile = KitHarnessConfiguration("managed", "http://internal", "https://bosca.invalid", scopes = listOf("content:view"),
        image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64), versions = RuntimeVersions("test", "test", "test", "codex-0.160.1"),
        egressProxy = "http://kit-egress:3128", egressHosts = listOf("api.openai.com"))
    private val security = mockk<SecurityService> {
        coEvery { getPrincipalGroups(principal.id) } returns emptyList()
        coEvery { getPrincipalById(principal.id) } returns principal
    }
    private val configurations = mockk<ConfigurationService> {
        coEvery { getByKey("kit.harness") } returns configuration
        coEvery { getValue(configuration.id) } answers { json.encodeToJsonElement(KitHarnessPolicy.serializer(), policy) }
        coEvery { getPermissions(any()) } returns emptyList()
        coEvery { isParentAllowed(any(), any(), any()) } returns false
    }
    private val application = mockk<BoscaApplication>(relaxed = true) {
        every { environment.config.propertyOrNull("kit.harness")?.getString() } answers {
            this@KitHarnessEgressTest.json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(listOf(profile)))
        }
    }
    private val mcp = mockk<KitMcpResolver> { coEvery { catalog(any(), any()) } returns KitMcpCatalog(emptyList(), emptyList(), emptyList()) }
    private val resolver = KitHarnessPolicyResolver(configurations, ConfigurationPermissionEvaluator(security, GroupEvaluator(security), configurations),
        security, application, json, mcp)
    private val encryption = mockk<EncryptionService> {
        coEvery { decrypt(any(), any()) } answers { firstArg<EncryptionService.Encrypted>().data }
    }
    private var revokedAt: String? = null
    private val tokens = mockk<ApiTokenService> {
        coEvery { getTokenById(7) } answers {
            PrincipalCredential(principal.id, ApiTokenCredentialAttributes("sha256:x", "Kit", tokenPrefix = "bosca_",
                revokedAt = revokedAt, createdBy = principal.id.toString())).copy(id = 7)
        }
        coEvery { getTokenById(neq(7L)) } returns null
    }
    private val sessions = mutableMapOf<UUID, KitRuntimeSession>()
    private val repository = mockk<KitRuntimeRepository>().also { repository ->
        coEvery { repository.get(any()) } answers { sessions[firstArg()] }
    }
    private val service = KitHarnessServiceImpl(repository, mockk(), mockk(), security, resolver, mockk(), json, tokens, mockk(), mockk(), mockk(), encryption)

    private suspend fun runtime(environment: Map<String, String> = mapOf("BOSCA_EGRESS_TOKEN" to "egress-secret"),
                                expires: Long = java.time.Instant.now().epochSecond + 3600, credentialId: Long? = 7,
                                mcpUrls: List<String> = listOf("https://mcp.example.com/sse", "http://plain.example.com:8080/mcp")): KitRuntimeSession {
        val resolution = resolver.resolve(principal.id, KitRuntimeAuthority(listOf("content:view"), emptyList()), null, "kit").copy(
            mcpConnections = mcpUrls.mapIndexed { index, url -> KitMcpConnection("remote$index", "Remote", McpTransportType.STREAMABLE_HTTP, false, url) })
        val values = environment + ("BOSCA_RUNTIME_EXPIRES" to expires.toString())
        val plain = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), values).encodeToByteArray()
        val secrets = KitRuntimeSecrets(UUID.random(), Base64.getEncoder().encodeToString(byteArrayOf(1)), Base64.getEncoder().encodeToString(plain), values.keys.toList())
        val session = KitRuntimeSession(UUID.random(), principal.id, json.encodeToJsonElement(KitHarnessConfiguration.serializer(), resolution.configuration),
            json.encodeToJsonElement(KitRuntimeAuthority.serializer(), resolution.authority),
            resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), resolution), state = "READY",
            secrets = json.encodeToJsonElement(KitRuntimeSecrets.serializer(), secrets), credentialId = credentialId, idleAt = OffsetDateTime.now())
        sessions[session.sessionId] = session
        return session
    }

    @Test fun `lookup combines profile, resolved MCP and administrator destinations`() = runTest {
        val session = runtime()
        assertEquals(listOf("api.openai.com:443", "files.example.com:8443", "mcp.example.com:443", "plain.example.com:8080", "tools.example.com:443"),
            service.egress(session.sessionId, "egress-secret")?.destinations)
    }

    @Test fun `administrator changes apply to the next lookup of a running generation`() = runTest {
        val session = runtime()
        policy = policy.copy(egressHosts = emptyList())
        assertEquals(listOf("api.openai.com:443", "mcp.example.com:443", "plain.example.com:8080"),
            service.egress(session.sessionId, "egress-secret")?.destinations)
    }

    @Test fun `lookup is not found for every unusable generation`() = runTest {
        assertNull(service.egress(UUID.random(), "egress-secret"))
        assertNull(service.egress(runtime().sessionId, "other"))
        assertNull(service.egress(runtime(environment = emptyMap()).sessionId, ""))
        assertNull(service.egress(runtime(expires = java.time.Instant.now().epochSecond - 1).sessionId, "egress-secret"))
        assertNull(service.egress(runtime(credentialId = null).sessionId, "egress-secret"))
        assertNull(service.egress(runtime(credentialId = 8).sessionId, "egress-secret"))
        val deleted = runtime()
        sessions[deleted.sessionId] = deleted.copy(deletedAt = OffsetDateTime.now())
        assertNull(service.egress(deleted.sessionId, "egress-secret"))
        val cancelled = runtime()
        sessions[cancelled.sessionId] = cancelled.copy(cancelRequestedAt = OffsetDateTime.now())
        assertNull(service.egress(cancelled.sessionId, "egress-secret"))
        revokedAt = OffsetDateTime.now().toString()
        assertNull(service.egress(runtime().sessionId, "egress-secret"))
    }

    @Test fun `editing administrator destinations keeps the policy version`() = runTest {
        val before = resolver.resolve(principal.id, KitRuntimeAuthority(listOf("content:view"), emptyList()), null, "kit").policyVersion
        policy = policy.copy(egressHosts = listOf("other.example.com"))
        assertEquals(before, resolver.resolve(principal.id, KitRuntimeAuthority(listOf("content:view"), emptyList()), null, "kit").policyVersion)
    }

    @Test fun `invalid destinations and proxies are rejected by policy validation`() = runTest {
        policy = policy.copy(egressHosts = listOf("*.example.com"))
        assertFailsWith<IllegalArgumentException> { resolver.egressHosts() }
        policy = policy.copy(egressHosts = emptyList())
        profile = profile.copy(egressProxy = "http://user:secret@kit-egress:3128")
        assertFailsWith<IllegalArgumentException> { resolver.deployment() }
        profile = profile.copy(egressProxy = "http://kit-egress:3128", egressHosts = listOf("api.openai.com:0"))
        assertFailsWith<IllegalArgumentException> { resolver.deployment() }
    }

    @Test fun `destinations normalize to host and port`() {
        assertEquals("api.openai.com:443", egressDestination(" API.OpenAI.com. "))
        assertEquals("example.com:8443", egressDestination("example.com:8443"))
        listOf("", "*.example.com", "example.com:", "example.com:70000", "a:b:c", "[::1]:443", "exa mple.com", "-bad.example.com").forEach {
            assertFailsWith<IllegalArgumentException>(it) { egressDestination(it) }
        }
        assertEquals("mcp.example.com:443", egressDestination(URI("https://MCP.example.com/sse")))
        assertEquals("mcp.example.com:80", egressDestination(URI("http://mcp.example.com/sse")))
        assertEquals("mcp.example.com:9000", egressDestination(URI("https://mcp.example.com:9000")))
        assertNull(egressDestination(URI("https://[::1]/sse")))
        listOf("https://kit-egress:3128", "http://kit-egress", "http://kit-egress:3128/path", "http://kit-egress:3128?x=1").forEach {
            assertFailsWith<IllegalArgumentException>(it) { requireEgressProxy(it) }
        }
        requireEgressProxy("http://kit-egress:3128")
    }
}
