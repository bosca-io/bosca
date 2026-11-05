@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ai.kit.harness.runtime

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatMessagePartInput
import bosca.ai.chat.model.ChatSessionInput
import bosca.ai.chat.repository.AiMigration
import bosca.ai.chat.repository.ChatHistoryRepositoryImpl
import bosca.ai.chat.service.ChatHistoryServiceImpl
import bosca.ai.kit.agents.session.KitSessionServiceImpl
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.configuration.KitMigration
import bosca.ai.kit.harness.*
import bosca.configuration.model.Configuration
import bosca.configuration.security.ConfigurationPermissionEvaluator
import bosca.configuration.service.ConfigurationService
import bosca.content.metadata.model.EmptyMetadata
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.*
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.di.asProvider
import bosca.security.encryption.EncryptionService
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.*
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.*
import kotlinx.serialization.json.*
import java.util.Base64

/** Real PostgreSQL, generated repositories, canonical chat history and context resolution. */
internal class KitDelegationFixture(val scopes: List<String> = listOf("ai:delegate", "content:view")) : AutoCloseable {
    private val postgres = SharedPostgreSQLContainer().also { it.start() }
    val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 4), key = "kit-delegation"))
    val json = BoscaApplication(ApplicationConfig.load("{}".byteInputStream()), ownsServices = false).json
    val runtimes = KitRuntimeRepositoryImpl()
    val repository = KitDelegationRepositoryImpl()
    val principalId = UUID.random()
    val sessionId = UUID.random()
    val group = Group(UUID.random(), "users", "", GroupType.PRINCIPAL)
    val identity = Principal(id = principalId, anonymous = false)
    var groups = listOf(group)
    val authority = KitRuntimeAuthority(scopes, listOf(group.id), scopes)
    val source = EmptyMetadata.copy(id = UUID.random(), name = "Explicit source")
    var sourceAllowed = true
    var sourceBody = "Selected input snapshot"
    val metadata = mockk<MetadataService> { coEvery { getById(any<UUID>()) } answers { source.takeIf { it.id == firstArg<UUID>() } } }
    val metadataPermissions = mockk<MetadataPermissionEvaluator> {
        coEvery { isAllowed(any<AuthenticationContext>(), source, PermissionAction.VIEW) } answers { sourceAllowed }
        coEvery { isContentAllowed(any<AuthenticationContext>(), source, PermissionAction.VIEW) } answers { sourceAllowed }
    }
    val security = mockk<SecurityService>(relaxed = true) {
        coEvery { getPrincipalById(principalId) } returns identity
        coEvery { getPrincipalGroups(principalId) } answers { groups }
    }
    var profile = KitHarnessConfiguration("kit", "http://127.0.0.1:1", "https://bosca.invalid/graphql", scopes = scopes,
        image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64), versions = RuntimeVersions("1", "1", "1", "1"))
    var childProfile: KitHarnessConfiguration? = null
    var limits = KitDelegationPolicy(listOf("kit", "claude"), maxDepth = 2, maxActive = 2, maxChildren = 4, maxRuntimeSeconds = 60, totalRuntimeSeconds = 120)
    private val configuration = Configuration(UUID.random(), "kit.harness", "Harness settings", false)
    private val configurations = mockk<ConfigurationService> {
        coEvery { getByKey("kit.harness") } returns configuration
        coEvery { getValue(configuration.id) } answers { json.encodeToJsonElement(KitHarnessPolicy.serializer(), KitHarnessPolicy("kit", listOf("kit", "claude"), delegation = limits)) }
        coEvery { getPermissions(configuration) } returns emptyList()
        coEvery { isParentAllowed(any(), any(), any()) } returns false
    }
    private val application = mockk<BoscaApplication>(relaxed = true)
    init {
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } answers {
            json.encodeToString(KitHarnessDeployment.serializer(), KitHarnessDeployment(listOf(profile, childProfile ?: profile.copy(profile = "claude"))))
        }
    }
    val policy = KitHarnessPolicyResolver(configurations, ConfigurationPermissionEvaluator(security, GroupEvaluator(security), configurations), security, application, json,
        KitMcpResolver(mockk(relaxed = true), mockk(relaxed = true), json))
    val history = ChatHistoryServiceImpl(ChatHistoryRepositoryImpl(), mockk(relaxed = true))
    val sessions = KitSessionServiceImpl(history, KitJson(json))
    private val documents = mockk<DocumentService> {
        coEvery { getDocument(source.id, source.version) } answers {
            bosca.content.metadata.model.Document(source.id, source.version, title = "Plan", content = bosca.documents.Content(bosca.documents.Document(content = listOf(bosca.documents.TextNode(text = sourceBody)))))
        }
    }
    val agents = mockk<bosca.ai.agents.service.AgentService> { coEvery { getByKey("kit") } returns null }
    val prompts = mockk<bosca.ai.prompts.service.PromptService>(relaxed = true)
    val repositories = mockk<bosca.git.service.RepositoryService>(relaxed = true)
    val browse = mockk<bosca.git.service.RepositoryBrowseService>(relaxed = true)
    val repositoryPermissions = mockk<bosca.git.service.RepositoryAccessEvaluator>(relaxed = true)
    val context = KitContextResolver(agents, prompts, history, metadata, documents, mockk(relaxed = true), metadataPermissions,
        mockk(relaxed = true), security, json, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), repositories.asProvider(), browse.asProvider(), repositoryPermissions.asProvider())
    val encryption = mockk<EncryptionService> {
        coEvery { encrypt(any(), any()) } answers { EncryptionService.Encrypted(byteArrayOf(1), firstArg()) }
        coEvery { decrypt(any(), any()) } answers { firstArg<EncryptionService.Encrypted>().data }
    }
    val tokens = mockk<ApiTokenService>(relaxed = true)
    val provider = mockk<KitRuntimeProvider>(relaxed = true)
    val telemetry = KitRuntimeTelemetry(mockk(relaxed = true), json)
    val harness = KitHarnessServiceImpl(runtimes, history, sessions, security, policy, context, json, tokens, provider, telemetry, repository, encryption)
    val service = KitDelegationServiceImpl(repository, runtimes, harness, history, sessions, policy, context, metadata, metadataPermissions, security, encryption, json)
    lateinit var resolution: KitHarnessResolution
    lateinit var turn: KitRuntimeTurn

    suspend fun initialize() {
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }; provides<Json> { json }
        provides<io.opentelemetry.api.trace.Tracer> { io.opentelemetry.api.GlobalOpenTelemetry.getTracer("KitDelegationIntegrationTest") }
        FlywayMigration(pool).migrate(listOf(CoreMigration(), AiMigration(), KitMigration()))
        mockkStatic("bosca.ai.kit.harness.runtime.KitRuntimeJobExecutorExecutorKt")
        coEvery { any<KitRuntimeJob>().enqueue() } returns mockk(relaxed = true)
        request {
            connection().useStatement("insert into principals(id) values (?)") { it.setObject(1, java.util.UUID.fromString(principalId.toString())); it.executeUpdate() }
            history.createSession(principalId, ChatSessionInput("kit", "Parent"), sessionId)
            resolution = policy.resolve(principalId, authority, null, "kit")
            val parent = KitRuntimeSession(sessionId, principalId, json.encodeToJsonElement(KitHarnessConfiguration.serializer(), profile),
                json.encodeToJsonElement(KitRuntimeAuthority.serializer(), authority), resolvedSettings = encodedResolution(), idleAt = OffsetDateTime.now())
            runtimes.create(parent)
            turn = sent(sessionId, 101)
            sessions.recordUserMessage(sessionId, ChatMessageInput("user", listOf(ChatMessagePartInput("text", "Parent private history must not be copied"))))
        }
    }

    fun auth(credentialId: Long = 101, tokenScopes: List<String> = scopes, tokenGroups: List<Group> = groups): AuthenticationContext {
        val principal = ScopedAuthenticatedPrincipal(identity, tokenGroups, tokenScopes, tokenGroups.map { it.id }.toSet(), credentialId)
        return object : AuthenticationContext(null, null) { override fun principal() = principal }
    }
    fun input(seconds: Long = 30) = KitDelegationInput(UUID.random(), "Inspect selected plan", "Summary and artifacts", "claude", runtimeSeconds = seconds)
    fun encodedResolution(value: KitHarnessResolution = resolution) = json.encodeToJsonElement(KitHarnessResolution.serializer(), value)
    suspend fun <T> request(block: suspend () -> T): T = withConnectionManager { block() }

    suspend fun sent(id: UUID, credential: Long): KitRuntimeTurn {
        val session = requireNotNull(runtimes.get(id))
        val resolved = requireNotNull(session.resolvedSettings)
        val queued = runtimes.next(id)
        val next = queued ?: KitRuntimeTurn(UUID.random(), id, message = json.encodeToJsonElement(ChatMessageInput.serializer(), ChatMessageInput("user", listOf(ChatMessagePartInput("text", "Work")))))
        if (queued == null) runtimes.add(next)
        check(runtimes.resolve(next.id, resolved) == 1); check(runtimes.send(next.id, OffsetDateTime.now().plusSeconds(300)) == 1)
        val environment = """{"BOSCA_RUNTIME_EXPIRES":"${java.time.Instant.now().epochSecond + 600}"}""".encodeToByteArray()
        val secret = KitRuntimeSecrets(UUID.random(), Base64.getEncoder().encodeToString(byteArrayOf(1)), Base64.getEncoder().encodeToString(environment), listOf("BOSCA_RUNTIME_EXPIRES"))
        check(runtimes.save(session.copy(state = "BUSY", activeTurn = next.id, credentialId = credential,
            secrets = json.encodeToJsonElement(KitRuntimeSecrets.serializer(), secret))) == 1)
        return requireNotNull(runtimes.turn(next.id))
    }
    override fun close() {
        unmockkStatic("bosca.ai.kit.harness.runtime.KitRuntimeJobExecutorExecutorKt")
        ProviderRegistry.clear(); kotlinx.coroutines.runBlocking { pool.close() }; postgres.stop()
    }
}
