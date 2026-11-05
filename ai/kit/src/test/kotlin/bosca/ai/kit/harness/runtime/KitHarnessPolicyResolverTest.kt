package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.*
import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationPermission
import bosca.configuration.security.ConfigurationPermissionEvaluator
import bosca.configuration.service.ConfigurationService
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.*
import bosca.server.BoscaApplication
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

class KitHarnessPolicyResolverTest {
    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
    } }
    private val group = Group(UUID.random(), "advanced", "", GroupType.PRINCIPAL)
    private val principal = Principal(id = UUID.random(), anonymous = false)
    private var groups = listOf(group)
    private val configuration = Configuration(UUID.random(), "kit.harness", "Harness policy", false)
    private var metadata = configuration
    private var policy = KitHarnessPolicy("managed", listOf("managed", "advanced"))
    private var grants: List<EntityPermission> = emptyList()
    private val managed = KitHarnessConfiguration("managed", "http://internal", "https://bosca.invalid", scopes = listOf("content:view"),
        image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64), versions = RuntimeVersions("test", "test", "test", "codex-0.160.1"))
    private val deployment = KitHarnessDeployment(listOf(managed, managed.copy(profile = "advanced", scopes = listOf("content:view", "content:edit"))))
    private val security = mockk<SecurityService> {
        coEvery { getPrincipalGroups(principal.id) } answers { groups }
        coEvery { getPrincipalById(principal.id) } returns principal
    }
    private val configurations = mockk<ConfigurationService> {
        coEvery { getByKey("kit.harness") } answers { metadata }
        coEvery { getValue(configuration.id) } answers { json.encodeToJsonElement(KitHarnessPolicy.serializer(), policy) }
        coEvery { getPermissions(any()) } answers { grants }
        coEvery { isParentAllowed(any(), any(), any()) } returns false
    }
    private val application = mockk<BoscaApplication>(relaxed = true) {
        every { environment.config.propertyOrNull("kit.harness")?.getString() } returns this@KitHarnessPolicyResolverTest.json.encodeToString(KitHarnessDeployment.serializer(), deployment)
    }
    private var catalog = KitMcpCatalog(emptyList(), emptyList(), emptyList())
    private val mcp = mockk<KitMcpResolver> { coEvery { catalog(any(), any()) } answers { this@KitHarnessPolicyResolverTest.catalog } }
    private val resolver = KitHarnessPolicyResolver(configurations, ConfigurationPermissionEvaluator(security, GroupEvaluator(security), configurations), security, application, json, mcp)
    private fun auth(scopes: List<String>? = null, allowed: Set<UUID>? = null): AuthenticationContext =
        object : AuthenticationContext(null, null) {
            override fun principal() = ScopedAuthenticatedPrincipal(principal, groups, scopes, allowed, 1)
        }
    private suspend fun session(authentication: AuthenticationContext = auth()): KitRuntimeSession {
        val authority = resolver.capture(authentication)
        val resolution = resolver.resolve(principal.id, authority, null, "kit")
        return KitRuntimeSession(UUID.random(), principal.id, json.encodeToJsonElement(KitHarnessConfiguration.serializer(), resolution.configuration),
            json.encodeToJsonElement(KitRuntimeAuthority.serializer(), authority),
            resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), resolution), idleAt = OffsetDateTime.now())
    }
    private fun allow(action: PermissionAction = PermissionAction.EXECUTE) {
        grants = listOf(ConfigurationPermission(configuration.id, action, group.id))
    }

    @Test fun `standard callers get a safe summary without a selectable deployment catalog`() = runTest {
        val settings = resolver.settings(auth(), session(), "kit")
        assertFalse(settings.canOverride)
        assertTrue(settings.choices.isEmpty())
        assertEquals("managed", settings.effectiveProfile)
        assertEquals("SYSTEM", settings.source)
        val public = json.encodeToString(KitHarnessSettings.serializer(), settings)
        assertFalse(public.contains("internal")); assertFalse(public.contains("bosca.invalid"))
        assertFailsWith<IllegalArgumentException> { resolver.verify(auth(), session(), KitHarnessChoice("advanced", settings.policyVersion), "kit") }
    }

    @Test fun `execute allows only current policy choices and manage alone does not grant execute`() = runTest {
        allow(PermissionAction.MANAGE)
        assertFalse(resolver.settings(auth(), session(), "kit").canOverride)
        allow()
        val session = session()
        val settings = resolver.settings(auth(), session, "kit")
        assertTrue(settings.canOverride)
        resolver.verify(auth(), session, KitHarnessChoice("advanced", settings.policyVersion), "kit")
        assertFailsWith<IllegalArgumentException> { resolver.verify(auth(), session, KitHarnessChoice("forged", settings.policyVersion), "kit") }
        assertFailsWith<IllegalArgumentException> { resolver.verify(auth(), session, KitHarnessChoice("advanced", "stale"), "kit") }
    }

    @Test fun `revoked group or execute permission falls back and cannot widen runtime authority`() = runTest {
        allow()
        val authority = resolver.capture(auth())
        val baseline = resolver.resolve(principal.id, authority, null, "kit")
        val choice = KitHarnessChoice("advanced", baseline.policyVersion)
        assertEquals("CHAT", resolver.resolve(principal.id, authority, choice, "kit").source)
        groups = emptyList()
        val revoked = resolver.resolve(principal.id, authority, choice, "kit")
        assertEquals("managed", revoked.configuration.profile)
        assertTrue(revoked.authority.groups.isEmpty())
        assertContains(assertNotNull(revoked.notice), "removed")
        assertEquals(choice, revoked.requested)
        groups = listOf(group, group.copy(id = UUID.random(), name = "new-membership"))
        val restored = resolver.resolve(principal.id, authority.copy(scopes = listOf("content:view")), choice, "kit")
        assertEquals(listOf("content:view"), restored.authority.scopes)
        assertEquals(listOf(group.id), restored.authority.groups)
        grants = emptyList()
        assertEquals("SYSTEM", resolver.resolve(principal.id, authority, choice, "kit").source)
    }

    @Test fun `policy revision and agent defaults apply without accepting stale overrides`() = runTest {
        allow()
        val authority = resolver.capture(auth())
        val first = resolver.resolve(principal.id, authority, null, "kit")
        policy = policy.copy(agentDefaults = mapOf("kit" to "advanced"))
        val changed = resolver.resolve(principal.id, authority, KitHarnessChoice("managed", first.policyVersion), "kit")
        assertNotEquals(first.policyVersion, changed.policyVersion)
        assertEquals("advanced", changed.configuration.profile)
        assertEquals("AGENT", changed.source)
        assertContains(assertNotNull(changed.notice), "policy changed")
        policy = policy.copy(defaultProfile = "unconfigured")
        assertFailsWith<IllegalArgumentException> { resolver.resolve(principal.id, authority, null, "kit") }
    }

    @Test fun `native approval requires the original policy and current caller membership`() = runTest {
        val authority = resolver.capture(auth())
        val resolution = resolver.resolve(principal.id, authority, null, "kit")
        assertTrue(resolver.isApprovalCurrent(principal.id, resolution))
        policy = policy.copy(agentDefaults = mapOf("kit" to "managed"))
        assertFalse(resolver.isApprovalCurrent(principal.id, resolution))
        policy = policy.copy(agentDefaults = emptyMap())
        groups = emptyList()
        assertFalse(resolver.isApprovalCurrent(principal.id, resolution))
        groups = listOf(group)
        coEvery { security.getPrincipalById(principal.id) } returns principal.copy(deletedAt = OffsetDateTime.now())
        assertFalse(resolver.isApprovalCurrent(principal.id, resolution))
    }

    @Test fun `native approval cannot retain a revoked configuration override grant`() = runTest {
        allow()
        val authority = resolver.capture(auth())
        val baseline = resolver.resolve(principal.id, authority, null, "kit")
        val chosen = resolver.resolve(principal.id, authority, KitHarnessChoice("advanced", baseline.policyVersion), "kit")
        assertTrue(resolver.isApprovalCurrent(principal.id, chosen))
        grants = emptyList()
        assertFalse(resolver.isApprovalCurrent(principal.id, chosen))
        assertTrue(resolver.isApprovalCurrent(principal.id, baseline), "Managed access does not require an override grant")
    }

    @Test fun `scoped credentials and group restrictions remain authoritative for explicit overrides`() = runTest {
        allow()
        val restricted = auth(listOf("content:view"))
        assertFalse(resolver.settings(restricted, session(restricted), "kit").canOverride)
        val management = auth(listOf("content:view", "security:manage"))
        assertTrue(resolver.settings(management, session(management), "kit").canOverride)
        val excluded = auth(listOf("content:view", "security:manage"), emptySet())
        assertFalse(resolver.settings(excluded, session(excluded), "kit").canOverride)
        val browserSession = session(auth())
        assertFalse(resolver.settings(restricted, browserSession, "kit").canOverride)
    }

    @Test fun `chat selections retain required MCP registrations and revoked assignments fall back`() = runTest {
        catalog = KitMcpCatalog(listOf(
            KitMcpConnection("required", "Required", bosca.ai.agents.model.McpTransportType.STDIO, true, command = listOf("/opt/required")),
            KitMcpConnection("optional", "Optional", bosca.ai.agents.model.McpTransportType.STDIO, false, command = listOf("/opt/optional"))),
            listOf(KitMcpOption("required", "Required", true), KitMcpOption("optional", "Optional", false)), emptyList())
        allow()
        val session = session()
        val settings = resolver.settings(auth(), session, "kit")
        val choice = KitHarnessChoice("managed", settings.policyVersion, listOf("required"))
        resolver.verify(auth(), session, choice, "kit")
        assertFailsWith<IllegalArgumentException> { resolver.verify(auth(), session, choice.copy(mcpServers = emptyList()), "kit") }
        assertFailsWith<IllegalArgumentException> { resolver.verify(auth(), session, choice.copy(mcpServers = listOf("required", "foreign")), "kit") }
        val authority = resolver.capture(auth())
        assertEquals(listOf("required"), resolver.resolve(principal.id, authority, choice, "kit").mcpConnections.map { it.key })
        catalog = KitMcpCatalog(emptyList(), emptyList(), emptyList())
        val revoked = resolver.resolve(principal.id, authority, choice, "kit")
        assertTrue(revoked.mcpConnections.isEmpty())
        assertContains(assertNotNull(revoked.notice), "MCP connections changed")
    }

    @Test fun `script management is limited to explicitly configured profiles and caller scopes`() = runTest {
        assertFalse("scripts:manage" in resolver.capture(auth()).scopes)
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(listOf(managed.copy(scopes = listOf("scripts:view", "scripts:manage")))))
        assertEquals(listOf("scripts:manage", "scripts:view"), resolver.capture(auth()).scopes)
        assertEquals(listOf("scripts:view"), resolver.capture(auth(listOf("scripts:view"))).scopes)
    }

    @Test fun `runtime profiles cannot grant recursive execution or administration`() = runTest {
        for (scope in listOf("ai:execute", "mcp:execute", "security:manage", "artifacts:admin", "content:manage")) {
            every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
                json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(listOf(managed.copy(scopes = listOf(scope)))))
            assertContains(assertFailsWith<IllegalArgumentException> { resolver.capture(auth()) }.message.orEmpty(), "recursive")
        }
    }

    @Test fun `public policy and deleted principal fail closed`() = runTest {
        val authority = resolver.capture(auth())
        metadata = metadata.copy(public = true)
        assertFailsWith<IllegalArgumentException> { resolver.resolve(principal.id, authority, null, "kit") }
        metadata = configuration
        coEvery { security.getPrincipalById(principal.id) } returns principal.copy(deletedAt = OffsetDateTime.now())
        assertFailsWith<IllegalArgumentException> { resolver.resolve(principal.id, authority, null, "kit") }
    }

    @Test fun `deployment defaults work without creating a private policy or granting overrides`() = runTest {
        coEvery { configurations.getByKey("kit.harness") } returns null
        val authority = resolver.capture(auth(listOf("content:view")))
        val resolution = resolver.resolve(principal.id, authority, null, "kit")
        assertNull(resolution.configurationId)
        assertEquals("managed", resolution.configuration.profile)
        assertEquals(listOf("content:view"), resolution.authority.scopes)
        assertTrue(resolver.isApprovalCurrent(principal.id, resolution))
        val runtime = session()
        val settings = resolver.settings(auth(), runtime, "kit")
        assertFalse(settings.canOverride)
        assertTrue(settings.choices.isEmpty())
        val attempted = KitHarnessChoice("advanced", settings.policyVersion)
        assertFailsWith<IllegalArgumentException> { resolver.verify(auth(), runtime, attempted, "kit") }
        assertEquals("managed", resolver.resolve(principal.id, authority, attempted, "kit").configuration.profile)
        coVerify(exactly = 0) { configurations.getValue(any()) }
    }

    @Test fun `image upgrades preserve approved conversation choices while scope changes revoke them`() = runTest {
        allow()
        val authority = resolver.capture(auth())
        val original = resolver.resolve(principal.id, authority, null, "kit")
        val choice = KitHarnessChoice("advanced", original.policyVersion)
        val upgraded = deployment.copy(profiles = deployment.profiles.map { it.copy(image = "runtime@sha256:" + "c".repeat(64),
            imageId = "sha256:" + "d".repeat(64), versions = it.versions.copy(harness = "new-native-version"), idleSeconds = 120, maxJournalTurns = 512) })
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), upgraded)
        val retained = resolver.resolve(principal.id, authority, choice, "kit")
        assertEquals(original.policyVersion, retained.policyVersion)
        assertEquals("advanced", retained.configuration.profile)
        assertEquals("new-native-version", retained.configuration.versions.harness)
        assertNull(retained.notice)
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), upgraded.copy(profiles = upgraded.profiles.map { it.copy(scopes = listOf("content:view")) }))
        val revoked = resolver.resolve(principal.id, authority, choice, "kit")
        assertNotEquals(original.policyVersion, revoked.policyVersion)
        assertEquals("managed", revoked.configuration.profile)
        assertNotNull(revoked.notice)
    }

    @Test fun `startup budget must be positive and end before the generation credential expires`() = runTest {
        for (seconds in listOf(0L, -1L, managed.credentialLifetimeSeconds)) {
            every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
                json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(listOf(managed.copy(startupSeconds = seconds))))
            assertContains(assertFailsWith<IllegalArgumentException> { resolver.capture(auth()) }.message.orEmpty(), "profile limits")
        }
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(deployment.profiles.map { it.copy(startupSeconds = 120) }))
        assertEquals(120L, resolver.resolve(principal.id, resolver.capture(auth()), null, "kit").configuration.startupSeconds)
    }

    @Test fun `archive storage limits are positive and quota changes retain approval authority`() = runTest {
        val authority = resolver.capture(auth())
        val baseline = resolver.resolve(principal.id, authority, null, "kit")
        for (limit in listOf(0L, -1L)) {
            every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
                json.encodeToString(KitHarnessDeployment.serializer(), deployment.copy(maxArchiveBytesPerPrincipal = limit))
            assertContains(assertFailsWith<IllegalArgumentException> { resolver.capture(auth()) }.message.orEmpty(), "archive storage limit")
        }
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), deployment.copy(maxArchiveBytesPerPrincipal = 1024))
        assertEquals(1024, resolver.deployment().maxArchiveBytesPerPrincipal)
        assertEquals(baseline, resolver.resolve(principal.id, authority, null, "kit"))
        assertTrue(resolver.isApprovalCurrent(principal.id, baseline))
        for (profile in listOf(managed.copy(maxArchiveAgeSeconds = 0), managed.copy(maxArchiveAgeSeconds = -1),
            managed.copy(maxArchiveAgeSeconds = Long.MAX_VALUE), managed.copy(archiveSeconds = 0), managed.copy(archiveSeconds = 86401))) {
            every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
                json.encodeToString(KitHarnessDeployment.serializer(), deployment.copy(profiles = listOf(profile)))
            assertContains(assertFailsWith<IllegalArgumentException> { resolver.capture(auth()) }.message.orEmpty(), "profile limits")
        }
    }

    @Test fun `admission limits are positive and do not invalidate active policy or approvals`() = runTest {
        val authority = resolver.capture(auth())
        val baseline = resolver.resolve(principal.id, authority, null, "kit")
        for (limits in listOf(deployment.copy(maxRuntimes = 0), deployment.copy(maxRuntimesPerPrincipal = 0),
            deployment.copy(maxRuntimes = -1), deployment.copy(maxRuntimesPerPrincipal = -1))) {
            every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
                json.encodeToString(KitHarnessDeployment.serializer(), limits)
            assertContains(assertFailsWith<IllegalArgumentException> { resolver.capture(auth()) }.message.orEmpty(), "admission limits")
        }
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), deployment.copy(maxRuntimes = 1, maxRuntimesPerPrincipal = 1))
        assertEquals(1, resolver.deployment().maxRuntimes)
        assertEquals(1, resolver.deployment().maxRuntimesPerPrincipal)
        assertEquals(baseline.policyVersion, resolver.resolve(principal.id, authority, null, "kit").policyVersion)
        assertTrue(resolver.isApprovalCurrent(principal.id, baseline))
    }

    @Test fun `runtime identity requires immutable images and supported protocols without changing approved policy`() = runTest {
        val original = resolver.resolve(principal.id, resolver.capture(auth()), null, "kit")
        for (invalid in listOf(managed.copy(image = "runtime:latest"), managed.copy(imageId = "latest"), managed.copy(infrastructure = KitInfrastructure.SWARM),
            managed.copy(versions = managed.versions.copy(cli = "")), managed.copy(versions = managed.versions.copy(acp = 2)))) {
            every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
                json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(listOf(invalid)))
            assertContains(assertFailsWith<IllegalArgumentException> { resolver.capture(auth()) }.message.orEmpty(), "runtime identity")
        }
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), deployment.copy(profiles = deployment.profiles.map {
                it.copy(imageId = "docker-pullable://registry/runtime@sha256:" + "c".repeat(64), versions = it.versions.copy(cli = "new"))
            }))
        assertEquals(original.policyVersion, resolver.resolve(principal.id, resolver.capture(auth()), null, "kit").policyVersion)
        assertTrue(resolver.isApprovalCurrent(principal.id, original))
    }

    @Test fun `Swarm profiles pin the task image digest independently of harness family`() = runTest {
        val swarm = managed.copy(infrastructure = KitInfrastructure.SWARM, imageId = managed.image.substringAfter('@'))
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), deployment.copy(profiles = deployment.profiles.map { it.copy(infrastructure = KitInfrastructure.SWARM, imageId = it.image.substringAfter('@')) }))
        val selected = resolver.resolve(principal.id, resolver.capture(auth()), null, "kit")
        assertEquals(KitInfrastructure.SWARM, selected.configuration.infrastructure)
        assertEquals(swarm.imageId, selected.configuration.imageId)
    }
    @Test fun `journal budgets reject unbounded profiles and retain configured limits`() = runTest {
        val invalid = listOf(managed.copy(maxJournalBytes = 0), managed.copy(maxJournalBytes = 1023),
            managed.copy(maxJournalBytes = 256L * 1024 * 1024 + 1), managed.copy(maxJournalTurns = 0), managed.copy(maxJournalTurns = 10001))
        for (profile in invalid) {
            every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
                json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(listOf(profile)))
            assertContains(assertFailsWith<IllegalArgumentException> { resolver.capture(auth()) }.message.orEmpty(), "profile limits")
        }
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(deployment.profiles.map { it.copy(maxJournalBytes = 1024, maxJournalTurns = 1) }))
        val profile = resolver.resolve(principal.id, resolver.capture(auth()), null, "kit").configuration
        assertEquals(1024L, profile.maxJournalBytes)
        assertEquals(1, profile.maxJournalTurns)
    }

    @Test fun `runtime phase budgets must be positive and remain independently configurable`() = runTest {
        for (profile in listOf(managed.copy(connectionSeconds = 0), managed.copy(readinessSeconds = 0),
            managed.copy(restoreSeconds = 0), managed.copy(approvalSeconds = 0), managed.copy(connectionSeconds = 86401),
            managed.copy(readinessSeconds = 86401), managed.copy(restoreSeconds = 86401), managed.copy(approvalSeconds = 604801),
            managed.copy(promptSeconds = 0), managed.copy(hardRuntimeSeconds = 0), managed.copy(hardRuntimeSeconds = managed.credentialLifetimeSeconds + 1))) {
            every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
                json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(listOf(profile)))
            assertContains(assertFailsWith<IllegalArgumentException> { resolver.capture(auth()) }.message.orEmpty(), "profile limits")
        }
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } returns
            json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(deployment.profiles.map {
                it.copy(connectionSeconds = 2, readinessSeconds = 3, restoreSeconds = 4, approvalSeconds = 5, promptSeconds = 6, hardRuntimeSeconds = 7)
            }))
        val profile = resolver.resolve(principal.id, resolver.capture(auth()), null, "kit").configuration
        assertEquals(listOf(2L, 3L, 4L, 5L), listOf(profile.connectionSeconds, profile.readinessSeconds, profile.restoreSeconds, profile.approvalSeconds))
        assertEquals(6L, profile.promptSeconds)
        assertEquals(7L, profile.hardRuntimeSeconds)
    }

    @Test fun `model choices require both policy approval and session capabilities`() = runTest {
        policy = policy.copy(models = mapOf("managed" to KitModelPolicy(KitModelSelection("small", "medium"),
            listOf("small", "large"), listOf("medium", "high"), mapOf("kit" to KitModelSelection(reasoning = "high")))))
        allow()
        val original = session()
        val effective = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(original.resolvedSettings))
        assertEquals(KitModelSelection("small", "high"), effective.modelSettings)
        assertEquals("AGENT", effective.reasoningSource)
        val capabilities = KitModelCapabilities("caps", listOf(KitModelOption("small", "Small"), KitModelOption("unapproved", "Other")),
            listOf(KitModelOption("medium", "Medium")), KitModelSelection("small", "medium"))
        val session = original.copy(resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), effective.copy(modelCapabilities = capabilities)))
        val settings = resolver.settings(auth(), session, "kit")
        assertEquals(listOf("small"), settings.models.map { it.value })
        assertEquals(listOf("medium"), settings.reasoningOptions.map { it.value })
        val choice = KitHarnessChoice("managed", settings.policyVersion, model = "small", reasoning = "medium", capabilityVersion = "caps")
        resolver.verify(auth(), session, choice, "kit")
        for (invalid in listOf(choice.copy(model = "large"), choice.copy(model = "unapproved"), choice.copy(reasoning = "high"),
            choice.copy(capabilityVersion = "stale"), choice.copy(profile = "advanced"))) {
            assertFailsWith<IllegalArgumentException> { resolver.verify(auth(), session, invalid, "kit") }
        }
        assertFailsWith<IllegalArgumentException> { resolver.verify(auth(), original, choice, "kit") }
        val chosen = resolver.resolve(principal.id, resolver.capture(auth()), choice, "kit")
        assertEquals("CHAT", chosen.modelSource)
        grants = emptyList()
        val revoked = resolver.resolve(principal.id, resolver.capture(auth()), choice, "kit")
        assertEquals(KitModelSelection("small", "high"), revoked.modelSettings)
        assertEquals("SYSTEM", revoked.modelSource)
        assertTrue(resolver.settings(auth(), session, "kit").models.isEmpty())
        assertEquals("small", resolver.settings(auth(), session, "kit").model)
    }

    @Test fun `model policies require approved managed defaults and reject forged saved choices`() = runTest {
        policy = policy.copy(models = mapOf("managed" to KitModelPolicy(allowedModels = listOf("small"))))
        assertFailsWith<IllegalArgumentException> { resolver.capture(auth()).let { resolver.resolve(principal.id, it, null, "kit") } }
        policy = policy.copy(models = mapOf("managed" to KitModelPolicy(KitModelSelection("small"), listOf("small"))))
        allow()
        val ceiling = resolver.capture(auth())
        val initial = resolver.resolve(principal.id, ceiling, null, "kit")
        val rejected = resolver.resolve(principal.id, ceiling, KitHarnessChoice("managed", initial.policyVersion, model = "forged"), "kit")
        assertEquals(KitModelSelection("small"), rejected.modelSettings)
        assertContains(assertNotNull(rejected.notice), "Approved model settings changed")
    }

    @Test fun `delegation is opt in and child authority intersects approved profile and parent tools`() = runTest {
        val initial = resolver.resolve(principal.id, resolver.capture(auth()), null, "kit")
        assertFailsWith<IllegalArgumentException> { resolver.delegation(initial) }
        policy = policy.copy(delegation = KitDelegationPolicy(listOf("advanced")))
        val parent = resolver.resolve(principal.id, resolver.capture(auth()), null, "kit")
        val child = resolver.resolveChild(principal.id, parent, parent.authority, "advanced", 30, "kit")
        assertEquals("DELEGATION", child.source)
        assertEquals(listOf("content:view"), child.authority.scopes)
        assertEquals(parent.authority.groups, child.authority.groups)
        assertEquals(30, child.configuration.hardRuntimeSeconds)
        assertEquals(30, child.configuration.startupSeconds)
        assertEquals(30, child.configuration.promptSeconds)
        assertFailsWith<IllegalArgumentException> { resolver.resolveChild(principal.id, parent, parent.authority, "managed", 30, "kit") }
        val outside = KitMcpConnection("outside", "Outside", bosca.ai.agents.model.McpTransportType.STDIO, true, command = listOf("outside"))
        catalog = KitMcpCatalog(listOf(outside), emptyList(), emptyList())
        assertFailsWith<IllegalArgumentException> { resolver.resolveChild(principal.id, parent, parent.authority, "advanced", 30, "kit") }
        catalog = KitMcpCatalog(listOf(outside.copy(required = false)), emptyList(), emptyList())
        assertTrue(resolver.resolveChild(principal.id, parent, parent.authority, "advanced", 30, "kit").mcpConnections.isEmpty())
        groups = emptyList()
        assertFailsWith<IllegalArgumentException> { resolver.resolveChild(principal.id, parent, parent.authority, "advanced", 30, "kit") }
    }

    @Test fun `delegation policies reject unbounded depth budgets and unavailable profiles`() = runTest {
        for (limits in listOf(KitDelegationPolicy(listOf("unknown")), KitDelegationPolicy(listOf("advanced"), maxDepth = 0),
            KitDelegationPolicy(listOf("advanced"), maxActive = 5, maxChildren = 4), KitDelegationPolicy(listOf("advanced"), totalRuntimeSeconds = 1))) {
            policy = policy.copy(delegation = limits)
            assertFailsWith<IllegalArgumentException> { resolver.resolve(principal.id, resolver.capture(auth()), null, "kit") }
        }
    }
}
