@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ai.kit.harness.runtime

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatMessagePartInput
import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitMigration
import bosca.ai.kit.harness.*
import bosca.db.*
import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationPermission
import bosca.configuration.security.ConfigurationPermissionEvaluator
import bosca.configuration.service.ConfigurationService
import bosca.db.migrations.FlywayMigration
import bosca.di.*
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.jobs.KubernetesJobSecrets
import bosca.kubernetes.model.*
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.security.encryption.EncryptionService
import bosca.security.model.*
import bosca.security.service.*
import bosca.serialization.*
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.service.ObjectStorageService
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Runs the installed Kotlin CLI runtime, generated repositories and a native subprocess fixture. */
class KitRuntimeLifecycleIntegrationTest {
    @Test
    fun `runner reuses warm native session archives only on idle and restores queued turn after removal`() = lifecycle("codex")

    @Test
    fun `Claude descriptor follows the same complete lifecycle with its own native state roots`() = lifecycle("claude")

    @Test
    fun `Codex archive retention preserves quota cleanup and deadline boundaries`() = lifecycle("codex", archiveRetentionOnly = true)

    @Test
    fun `Claude archive retention preserves quota cleanup and deadline boundaries`() = lifecycle("claude", archiveRetentionOnly = true)

    @Test
    fun `Codex process replacement retires its credential and never replays uncertain work`() = lifecycle("codex", instanceFencingOnly = true)

    @Test
    fun `Claude process replacement retires its credential and never replays uncertain work`() = lifecycle("claude", instanceFencingOnly = true)

    @Test fun `Codex image upgrade reopens its operational directory and preserves native continuity`() = lifecycle("codex", upgradeOnly = true)

    @Test fun `Claude image upgrade reopens its operational directory and preserves native continuity`() = lifecycle("claude", upgradeOnly = true)

    private fun runtimePort() = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private suspend fun runtimeReady(endpoint: String) = runInterruptible(Dispatchers.IO) {
        try {
            java.net.URI("$endpoint/healthz").toURL().openConnection().apply {
                connectTimeout = 500
                readTimeout = 500
            }.getInputStream().use { true }
        } catch (_: java.io.IOException) { false }
    }

    private fun lifecycle(family: String, archiveRetentionOnly: Boolean = false, instanceFencingOnly: Boolean = false, upgradeOnly: Boolean = false): Unit = runBlocking {
        val runtimeCli = Path.of(System.getenv("KIT_RUNTIME_CLI") ?: "../../cli/build/install/bosca/bin/bosca").toAbsolutePath().normalize()
        val node = System.getenv("PATH").split(java.io.File.pathSeparator).map { Path.of(it, "node") }.first { Files.isExecutable(it) }.toString()
        val root = Files.createTempDirectory("kit-lifecycle-test-")
        val descriptorPath = Path.of(if (family == "claude") "runtime/claude/compatibility.json" else "runtime/compatibility.json")
        Files.copy(descriptorPath, root.resolve("compatibility.json"))
        val descriptor = Json.parseToJsonElement(Files.readString(descriptorPath)).jsonObject
        val nativeState = if (family == "claude") "projects/fixture/test.jsonl" else "sessions/test.json"
        val cliVersion = ProcessBuilder(runtimeCli.toString(), "--version").start().let {
            val version = it.inputStream.bufferedReader().readText().trim().substringAfterLast(' ')
            check(it.waitFor() == 0)
            version
        }
        for (reference in listOf("adapter", "harness").map { descriptor.getValue(it).jsonPrimitive.content }) {
            val name = reference.substringBeforeLast('@')
            val version = reference.substringAfterLast('@')
            val metadata = root.resolve("node_modules/$name/package.json")
            Files.createDirectories(metadata.parent)
            Files.writeString(metadata, """{"name":"$name","version":"$version"}""")
        }
        val fixture = Path.of("runtime/tests/fake_adapter.mjs").toAbsolutePath()
        val postgres = SharedPostgreSQLContainer().withReuse(true)
        postgres.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(postgres.jdbcUrl, postgres.username,
            postgres.password, maxConnections = 4), key = "kit-lifecycle"))
        val json = BoscaApplication(ApplicationConfig.load("{}".byteInputStream()), ownsServices = false).json
        val repository = KitRuntimeRepositoryImpl()
        ProviderRegistry.clear()
        provides<ConnectionPool> { pool }; provides<Json> { json }
        FlywayMigration(pool).migrate(listOf(KitMigration()))
        mockkStatic("bosca.ai.kit.harness.runtime.KitRuntimeJobExecutorExecutorKt")
        coEvery { any<KitRuntimeJob>().enqueue() } returns mockk(relaxed = true)
        val sessionId = UUID.random()
        val principalId = UUID.random()
        val config = KitHarnessConfiguration("kit", "http://127.0.0.1:{job}", "https://bosca.invalid/graphql", authenticationMethod = null, idleSeconds = 900, scopes = listOf("content:view", "git:read"),
            image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64), versions = RuntimeVersions(cliVersion, descriptor.getValue("adapter").jsonPrimitive.content.substringAfterLast('@'),
                descriptor.getValue("harness").jsonPrimitive.content.substringAfterLast('@'), descriptor.getValue("statePolicy").jsonPrimitive.content),
            mcpExecutables = mapOf("fixture" to listOf(node, Path.of("runtime/tests/fake_mcp.mjs").toAbsolutePath().toString())),
            mcpSecretEnvironment = listOf("MCP_TEST_ACCESS"))
        val application = mockk<BoscaApplication>(relaxed = true)
        var deployment = KitHarnessDeployment(listOf(config, config.copy(profile = "kit-alternate")))
        every { application.environment.config.propertyOrNull("kit.harness")?.getString() } answers { json.encodeToString(KitHarnessDeployment.serializer(), deployment) }
        val group = Group(UUID.random(), "harness-users", "", GroupType.PRINCIPAL)
        val principal = AuthenticatedPrincipal(Principal(id = principalId, anonymous = false), listOf(group))
        val auth = mockk<AuthenticationContext> { every { principal() } returns principal }
        val security = mockk<SecurityService> {
            coEvery { getPrincipalGroups(principalId) } returns listOf(group)
            coEvery { getPrincipalById(principalId) } returns principal.asPrincipal()
        }
        val configuration = Configuration(UUID.random(), "kit.harness", "Harness settings", false)
        var grants: List<EntityPermission> = emptyList()
        var policyValue = KitHarnessPolicy("kit", listOf("kit", "kit-alternate"))
        val configurations = mockk<ConfigurationService> {
            coEvery { getByKey("kit.harness") } returns configuration
            coEvery { getValue(configuration.id) } answers { json.encodeToJsonElement(KitHarnessPolicy.serializer(), policyValue) }
            coEvery { getPermissions(configuration) } answers { grants }
            coEvery { isParentAllowed(any(), any(), any()) } returns false
        }
        var mcpRegistration: bosca.ai.agents.model.McpServerRegistration? = null
        var mcpRequired = true
        val mcpAgent = bosca.ai.agents.model.Agent(UUID.random(), "kit", "Kit", "", UUID.random(), UUID.random())
        val mcpAgents = mockk<bosca.ai.agents.service.AgentService> {
            coEvery { getByKey("kit") } answers { mcpRegistration?.let { mcpAgent.copy(configuration = buildJsonObject {
                putJsonArray("harnessMcp") { add(buildJsonObject { put("key", "fixture"); put("required", mcpRequired) }) }
            }) } }
            coEvery { getTools(mcpAgent.id) } answers { listOf(bosca.ai.agents.model.AgentTool(key = "fixture-tool", name = "Fixture", description = "", mcpServerId = requireNotNull(mcpRegistration).id)) }
        }
        val mcpRegistry = mockk<bosca.ai.agents.service.McpServerRegistrationService> {
            coEvery { get(any<UUID>()) } answers { mcpRegistration }
        }
        val policy = KitHarnessPolicyResolver(configurations, ConfigurationPermissionEvaluator(security, GroupEvaluator(security), configurations), security, application, json, KitMcpResolver(mcpAgents, mcpRegistry, json))
        val history = mockk<ChatHistoryService>(relaxed = true)
        val displayMessages = mutableMapOf<UUID, ChatHistoryMessage>()
        coEvery { history.upsertMessage(any()) } answers {
            val message = firstArg<ChatHistoryMessage>()
            displayMessages[message.id] = message
            message
        }
        var chat = bosca.ai.chat.model.ChatSession(sessionId, principalId, agentKey = "kit")
        coEvery { history.getSession(sessionId) } answers { chat }
        coEvery { history.getSessionForUpdate(sessionId) } answers { chat }
        coEvery { history.updateSessionState(sessionId, any()) } answers { chat = chat.copy(state = secondArg()); chat }
        var contextAgent: bosca.ai.agents.model.Agent? = null
        var instruction = "Initial writing instructions"
        var sourceAvailable = true
        val source = bosca.content.metadata.model.EmptyMetadata.copy(id = UUID.random(), name = "Selected plan")
        val contextAgents = mockk<bosca.ai.agents.service.AgentService>()
        coEvery { contextAgents.getByKey("kit") } answers { contextAgent }
        val contextPrompts = mockk<bosca.ai.prompts.service.PromptService>()
        coEvery { contextPrompts.get(any<UUID>()) } answers {
            bosca.ai.prompts.model.Prompt(firstArg(), "kit", "Kit", "", instruction, "", "text", "text", null)
        }
        val contextMetadata = mockk<bosca.content.metadata.service.MetadataService>()
        coEvery { contextMetadata.getById(source.id) } returns source
        val contextDocuments = mockk<bosca.content.metadata.service.DocumentService>()
        coEvery { contextDocuments.getDocument(source.id, source.version) } returns bosca.content.metadata.model.Document(source.id, source.version, title = "Plan",
            content = bosca.documents.Content(bosca.documents.Document(content = listOf(bosca.documents.TextNode(text = "private source body")))))
        val contextPermissions = mockk<bosca.content.security.MetadataPermissionEvaluator>()
        coEvery { contextPermissions.isAllowed(any<AuthenticationContext>(), source, PermissionAction.VIEW) } answers { sourceAvailable }
        coEvery { contextPermissions.isContentAllowed(any<AuthenticationContext>(), source, PermissionAction.VIEW) } answers { sourceAvailable }
        val contextMemories = mockk<bosca.ai.memory.MemoryService>(relaxed = true)
        var selectedMemory: bosca.ai.memory.Memory? = null
        var toolMemory: bosca.ai.memory.Memory? = null
        var memoryReads = emptyList<bosca.ai.memory.MemoryRead>()
        coEvery { contextMemories.search(any(), any()) } answers { listOfNotNull(selectedMemory) }
        coEvery { contextMemories.get(any(), any()) } answers { listOfNotNull(selectedMemory, toolMemory).firstOrNull { it.id == secondArg<UUID>() } }
        coEvery { contextMemories.reads(any(), sessionId) } answers { memoryReads }
        coEvery { contextMemories.clearReads(sessionId) } answers { memoryReads = emptyList() }
        var domainAllowed = true
        var gitCommit = "a".repeat(40)
        val specification = bosca.workops.model.spec.Spec(id = UUID.random(), key = "CTX-SPEC-1", metadataId = source.id,
            statusId = UUID.random(), workflowId = UUID.random(), ownerProfileId = UUID.random(), createdByPrincipalId = principalId, modifiedByPrincipalId = principalId)
        val contextSpecs = mockk<bosca.workops.service.SpecService> {
            coEvery { getById(any()) } answers { specification.takeIf { it.id == firstArg<UUID>() } }
            coEvery { getPermissions(any()) } answers { if (domainAllowed) listOf(bosca.workops.model.permission.SpecPermission(specification.id, group.id, PermissionAction.VIEW)) else emptyList() }
            coEvery { isParentAllowed(any(), any(), any()) } returns false
        }
        val gitRepository = bosca.git.model.Repository(id = UUID.random(), slug = "context", name = "Context repository", ownerId = UUID.random())
        val contextRepositories = mockk<bosca.git.service.RepositoryService> {
            coEvery { findById(gitRepository.id) } returns gitRepository
            coEvery { getPermissions(any()) } answers { if (domainAllowed) listOf(mockk<EntityPermission> {
                every { groupId } returns group.id; every { action } returns PermissionAction.VIEW
            }) else emptyList() }
            coEvery { isParentAllowed(any(), any(), any()) } returns false
        }
        val contextBrowse = mockk<bosca.git.service.RepositoryBrowseService> {
            coEvery { resolveRef(gitRepository.id, "main") } answers { gitCommit }
            coEvery { readBlob(gitRepository.id, any(), "docs/plan.md") } answers { bosca.git.model.Blob("Domain Git source at $gitCommit", 100, gitCommit) }
        }
        val context = KitContextResolver(contextAgents, contextPrompts, history, contextMetadata, contextDocuments, mockk(), contextPermissions, mockk(), security, json, contextMemories,
            mockk(relaxed = true), contextSpecs.asProvider(), mockk(relaxed = true), mockk(relaxed = true),
            bosca.workops.service.SpecPermissionEvaluator(contextSpecs, security, GroupEvaluator(security)).asProvider(), mockk(relaxed = true),
            contextRepositories.asProvider(), contextBrowse.asProvider(),
            (bosca.git.security.RepositoryPermissionEvaluator(contextRepositories, security, GroupEvaluator(security)) as bosca.git.service.RepositoryAccessEvaluator).asProvider())
        val sessions = mockk<KitSessionService>(relaxed = true)
        val messages = mutableListOf(ChatHistoryMessage(sessionId = sessionId, author = "User",
            event = JsonPrimitive("earlier-conversation"), created = OffsetDateTime.now().minusDays(1)))
        coEvery { history.getMessages(sessionId) } answers { messages.toList() }
        coEvery { history.getRecentMessages(sessionId, any(), any()) } answers {
            messages.filter { it.created.isBefore(secondArg()) }.sortedByDescending { it.created }.take(thirdArg())
        }
        coEvery { sessions.recordUserMessage(sessionId, any()) } answers {
            messages.add(ChatHistoryMessage(sessionId = sessionId, author = "User",
                event = JsonPrimitive(secondArg<ChatMessageInput>().parts.single().text)))
            Unit
        }
        val tokens = mockk<ApiTokenService>(relaxed = true)
        var issued = 0L
        var failRevocation = false
        val revoked = mutableSetOf<Long>()
        val stored = mutableMapOf<Long, PrincipalCredential>()
        coEvery { tokens.revokeToken(any(), principalId) } answers {
            if (failRevocation) error("Injected revocation failure")
            revoked.add(firstArg())
            Unit
        }
        coEvery { tokens.getTokenById(any()) } answers { stored[firstArg()] }
        coEvery { tokens.deleteToken(any(), principalId) } answers {
            check(firstArg<Long>() in revoked) { "only revoked API tokens can be deleted" }
            stored.remove(firstArg())
            Unit
        }
        coEvery { tokens.createEphemeralToken(principalId, any(), principalId, true) } answers {
            issued++
            assertEquals(listOf("content:view", "git:read"), secondArg<ApiTokenInput>().scopes)
            val credential = PrincipalCredential(issued, principalId, CredentialType.API_TOKEN, JsonObject(emptyMap()))
            stored[issued] = credential
            ApiTokenCreationResult(credential, "test-scoped-$issued")
        }
        val encryption = mockk<EncryptionService> {
            coEvery { encrypt(any(), any()) } answers { EncryptionService.Encrypted(byteArrayOf(1), firstArg()) }
            coEvery { decrypt(any(), any()) } answers { firstArg<EncryptionService.Encrypted>().data }
        }
        val archives = mutableMapOf<String, ByteArray>()
        var failUpload = false
        var corruptRead = false
        var failArchiveDelete = false
        var stallArchiveRead = false
        var stallArchiveWrite = false
        var uploads = 0
        var archiveReads = 0
        val storage = mockk<ObjectStorageService> {
            coEvery { setInputStream(any(), any(), any()) } coAnswers {
                if (failUpload) error("Injected upload failure")
                if (stallArchiveWrite) delay(60_000)
                val bytes = secondArg<java.io.InputStream>().readAllBytes()
                archives[firstArg<Any>().toString()] = bytes
                uploads++
                bytes.size.toLong()
            }
            coEvery { delete(any()) } answers {
                if (failArchiveDelete) error("Injected archive deletion failure")
                archives.remove(firstArg<Any>().toString())
                Unit
            }
            coEvery { getInputStream(any()) } answers {
                archiveReads++
                if (stallArchiveRead) object : java.io.InputStream() {
                    override fun read(): Int {
                        try { java.util.concurrent.CountDownLatch(1).await() }
                        catch (failure: InterruptedException) { throw java.io.IOException(failure) }
                        return -1
                    }
                } else archives.getValue(firstArg<Any>().toString()).copyOf().also {
                    if (corruptRead) it[0] = (it[0].toInt() xor 1).toByte()
                }.inputStream()
            }
        }
        val jobs = mockk<KubernetesJobDispatchService>()
        var process: Process? = null
        var lastBuilder: ProcessBuilder? = null
        val replacements = mutableListOf<Process>()
        val executions = mutableMapOf<UUID, KubernetesJobExecution>()
        var launches = 0
        var exposeExecution = true
        var failRemoval = false
        var delayRemoval = false
        var startupRemainingSeconds: Long? = null
        var observedImage: String? = config.image
        var observedImageId: String? = config.imageId
        coEvery { jobs.dispatch(any()) } coAnswers {
            val request = firstArg<KubernetesJobRequest>()
            val env = json.parseToJsonElement(Base64.getDecoder().decode(requireNotNull(request.secrets).ciphertext).decodeToString()).jsonObject
            launches++
            val port = runtimePort()
            val builder = ProcessBuilder(runtimeCli.toString(), "harness", "serve", "--host", "127.0.0.1", "--port", port.toString(), "--root", root.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.INHERIT)
            builder.environment().putAll(request.environment)
            builder.environment().putAll(env.mapValues { it.value.jsonPrimitive.content })
            builder.environment()["BOSCA_ACP_COMMAND"] = JsonArray(listOf(node, fixture.toString()).map(::JsonPrimitive)).toString()
            builder.environment()["TEST_HARNESS_HOME"] = root.resolve("harness").toString()
            builder.environment()["BOSCA_NO_UPDATE_CHECK"] = "1"
            builder.environment()["BOSCA_RUNTIME_HOME"] = root.toString()
            builder.environment()["TEST_HARNESS_STATE_PATH"] = nativeState
            builder.environment()["MCP_TEST_ACCESS"] = "fixture-external-secret"
            lastBuilder = builder
            process = builder.start()
            withTimeout(20_000) {
                while (true) {
                    check(requireNotNull(process).isAlive) { "Kotlin harness runtime exited during startup" }
                    if (runtimeReady("http://127.0.0.1:$port")) break
                    delay(50)
                }
            }
            val id = UUID.random()
            executions[id] = KubernetesJobExecution(id, request.profile, request.idempotencyKey,
                status = KubernetesJobExecutionStatus.RUNNING, namespace = "kit", jobName = port.toString(),
                image = observedImage, imageId = observedImageId,
                createdAt = OffsetDateTime.now().minusSeconds(startupRemainingSeconds?.let { config.startupSeconds - it } ?: 0))
            id
        }
        coEvery { jobs.getExecution(any()) } answers { executions[firstArg()]?.let { if (exposeExecution) it else it.copy(jobName = null) } }
        coEvery { jobs.getResult(any()) } answers { executions[firstArg()]?.toResultOrNull() }
        coEvery { jobs.cancel(any()) } coAnswers {
            val current = assertNotNull(repository.get(sessionId))
            if (instanceFencingOnly) assertTrue(current.credentialId in revoked, "Revoke access before removing any incarnation")
            if (current.state == "STOPPING") {
                assertEquals("kit/runtime/$sessionId/${current.generation}.tar.gz", current.archiveKey)
                assertTrue(archives.isNotEmpty(), "Never delete the runtime before verified object storage commitment")
                assertTrue(current.credentialId in revoked, "Revoke the generation credential before workload removal")
            }
            if (failRemoval) error("Injected workload removal failure")
            if (delayRemoval) return@coAnswers Unit
            if (executions.getValue(firstArg()).status.isTerminal) return@coAnswers Unit
            requireNotNull(process).destroy()
            withContext(Dispatchers.IO) { requireNotNull(process).let { if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly().waitFor() } }
            process = null
            for (replacement in replacements) {
                replacement.destroy()
                withContext(Dispatchers.IO) { if (!replacement.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) replacement.destroyForcibly().waitFor() }
            }
            replacements.clear()
            val id = firstArg<UUID>()
            executions[id] = executions.getValue(id).copy(status = KubernetesJobExecutionStatus.CANCELLED, finishedAt = OffsetDateTime.now())
            for (name in listOf("workspace", "harness", "scratch", "control")) root.resolve(name).toFile().deleteRecursively()
        }
        val measurements = java.util.concurrent.ConcurrentHashMap<String, Long>()
        var metricsUnavailable = false
        val counter = mockk<bosca.counter.Counter> {
            coEvery { increment(any(), any()) } answers {
                if (metricsUnavailable) error("Injected telemetry failure")
                measurements.merge(firstArg(), secondArg(), Long::plus) ?: 0L
            }
        }
        val telemetry = KitRuntimeConfiguration().telemetry(counter, json)
        fun measurement(id: String) = measurements.entries.filter { it.key.startsWith("kit.runtime.$id.") }.sumOf { it.value }
        val runtimeProvider = KubernetesKitRuntimeProvider(jobs.asProvider())
        val delegationRepository = KitDelegationRepositoryImpl()
        val service = KitHarnessServiceImpl(repository, history, sessions, security, policy, context, json, tokens, runtimeProvider, telemetry, delegationRepository, encryption)
        val delegations = KitDelegationServiceImpl(delegationRepository, repository, service, history, sessions, policy, context,
            contextMetadata, contextPermissions, security, encryption, json)
        val driver = KitRuntimeDriver(repository, runtimeProvider, tokens, encryption, storage, history, sessions, mockk(), json, policy, context, telemetry, delegations)
        suspend fun drive() = withConnectionManager { driver.reconcile(sessionId) }
        suspend fun send(text: String) = withConnectionManager {
            service.send(auth, sessionId, ChatMessageInput("user", listOf(ChatMessagePartInput("text", text))))
        }
        suspend fun checkArchiveRetention() {
            suspend fun settle() = withTimeout(20_000) {
                while (true) {
                    drive()
                    val waiting = withConnectionManager { repository.next(sessionId) != null || repository.get(sessionId)?.state in setOf("STARTING", "STOPPING") }
                    if (!waiting) break
                }
            }
            val originalDeployment = deployment
            withConnectionManager { service.retire(sessionId) }; drive()
            val previous = withConnectionManager { assertNotNull(repository.get(sessionId)) }
            val previousKey = assertNotNull(previous.archiveKey)
            val previousBytes = withConnectionManager { repository.archiveBytes(principalId) }
            assertEquals(1, archives.size, "Successful retirement collects superseded generations")
            send("archive-quota-turn"); settle()
            withConnectionManager { service.retire(sessionId) }
            deployment = deployment.copy(maxArchiveBytesPerPrincipal = previousBytes + 1)
            val uploadsBeforeQuota = uploads
            assertFailsWith<KitArchiveQuotaException> { drive() }
            withConnectionManager {
                assertEquals("STOPPING", repository.get(sessionId)?.state)
                assertEquals(previousKey, repository.get(sessionId)?.archiveKey)
                assertEquals(previousBytes, repository.archiveBytes(principalId), "Rejected reservations do not consume quota")
                assertContains(assertNotNull(service.state(sessionId)?.failure), "storage is full")
            }
            assertEquals(uploadsBeforeQuota, uploads, "Reject quota before object upload")
            assertTrue(requireNotNull(process).isAlive)
            assertTrue(previousKey in archives)
            send("after-archive-quota")
            deployment = originalDeployment
            settle()
            assertEquals("after-archive-quota", Files.readString(root.resolve("harness/$nativeState")))
            assertFalse(previousKey in archives)
            assertEquals(1, archives.size)

            withConnectionManager { service.retire(sessionId) }
            failArchiveDelete = true
            assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "archive deletion failure")
            val afterCommit = withConnectionManager {
                val current = assertNotNull(repository.get(sessionId))
                assertEquals("STOPPED", current.state)
                assertNull(current.dispatchId, "Object collection does not retain a removed workload's capacity")
                assertEquals(2, repository.archives(sessionId).size)
                assertTrue(repository.pending(100, 0, 0).any { it.sessionId == sessionId })
                assertContains(assertNotNull(service.state(sessionId)?.failure), "cleanup failed")
                current
            }
            val uploadsBeforeCleanupRetry = uploads
            send("queued-during-archive-cleanup")
            assertFailsWith<IllegalStateException> { drive() }
            withConnectionManager { assertEquals("QUEUED", repository.next(sessionId)?.status) }
            failArchiveDelete = false
            settle()
            assertEquals(uploadsBeforeCleanupRetry, uploads, "Cleanup never recreates the committed archive")
            assertEquals(1, archives.size)
            assertTrue(assertNotNull(afterCommit.archiveKey) in archives)
            withConnectionManager { assertNull(service.state(sessionId)?.failure) }
            assertEquals("queued-during-archive-cleanup", Files.readString(root.resolve("harness/$nativeState")))

            for (unknownAge in listOf(false, true)) {
                Files.writeString(root.resolve("workspace/expiration-private.txt"), "expired workspace")
                withConnectionManager { service.retire(sessionId) }; drive()
                deployment = deployment.copy(profiles = deployment.profiles.map { it.copy(maxArchiveAgeSeconds = 60) })
                val expiredKey = withConnectionManager {
                    val current = assertNotNull(repository.get(sessionId))
                    val selected = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), current.configuration)
                    assertEquals(1, repository.save(current.copy(
                        configuration = json.encodeToJsonElement(KitHarnessConfiguration.serializer(), selected.copy(maxArchiveAgeSeconds = 60)),
                        archiveCreated = if (unknownAge) null else OffsetDateTime.now().minusSeconds(120))))
                    assertTrue(repository.pending(100, 0, 0).any { it.sessionId == sessionId }, "Cold expiry is recovered without a user message")
                    assertNotNull(current.archiveKey)
                }
                drive()
                withConnectionManager {
                    assertNull(repository.get(sessionId)?.archiveKey)
                    assertNull(repository.get(sessionId)?.nativeSessionId)
                    assertEquals(0L, repository.archiveBytes(principalId))
                }
                assertFalse(expiredKey in archives)
                val readsBeforeFreshActivation = archiveReads
                withConnectionManager { service.activate(auth, sessionId) }; settle()
                assertEquals(readsBeforeFreshActivation, archiveReads, "Expired native state is never hydrated")
                assertFalse(Files.exists(root.resolve("workspace/expiration-private.txt")))
                send("after-expiration-$unknownAge"); settle()
                coVerify { sessions.recordResponse(sessionId, match { it is bosca.ai.kit.agents.KitResponse.Text && it.text.contains("retention policy") }) }
            }

            withConnectionManager { service.retire(sessionId) }; drive()
            deployment = originalDeployment
            val retainedKey = withConnectionManager {
                val current = assertNotNull(repository.get(sessionId))
                val selected = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), current.configuration)
                assertEquals(1, repository.save(current.copy(
                    configuration = json.encodeToJsonElement(KitHarnessConfiguration.serializer(), selected.copy(maxArchiveAgeSeconds = null)),
                    archiveCreated = OffsetDateTime.now().minusYears(10))))
                assertNotNull(current.archiveKey)
            }
            drive()
            withConnectionManager { assertEquals(retainedKey, repository.get(sessionId)?.archiveKey, "Unset retention preserves intentionally long returns") }
            assertTrue(retainedKey in archives)
            send("archive-timeout-turn"); settle()
            deployment = deployment.copy(profiles = deployment.profiles.map { it.copy(archiveSeconds = 1) })
            val budgetCheckpoint = withConnectionManager {
                service.retire(sessionId)
                val current = assertNotNull(repository.get(sessionId))
                assertEquals("READY", current.state)
                assertTrue(!current.idleAt.isAfter(OffsetDateTime.now()), "Runner claims retirement after the requested idle expiry")
                assertNull(repository.next(sessionId))
                assertNotNull(current.dispatchId)
                val selected = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), current.configuration)
                assertEquals(1, repository.save(current.copy(configuration = json.encodeToJsonElement(KitHarnessConfiguration.serializer(), selected.copy(archiveSeconds = 1)))))
                assertEquals(1, json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), assertNotNull(repository.get(sessionId)).configuration).archiveSeconds)
                assertNotNull(current.archiveKey)
            }
            stallArchiveWrite = true
            withTimeout(10_000) {
                assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "archive deadline")
            }
            withConnectionManager {
                assertEquals(budgetCheckpoint, repository.get(sessionId)?.archiveKey)
                assertEquals(2, repository.archives(sessionId).size, "A timed out upload retains its reservation for reconciliation")
                assertContains(assertNotNull(service.state(sessionId)?.failure), "Archiving failed")
            }
            assertTrue(budgetCheckpoint in archives)
            stallArchiveWrite = false
            drive()
            assertEquals(1, archives.size)
            assertFalse(budgetCheckpoint in archives)
            withConnectionManager { assertNull(service.state(sessionId)?.failure) }
            deployment = originalDeployment
            send("after-archive-timeout"); settle()
        }

        suspend fun checkInstanceFencing() {
            suspend fun replacement(): Pair<String, Path> {
                val replacementRoot = root.resolve("replacement-${replacements.size}")
                replacementRoot.toFile().deleteRecursively()
                val replacementPort = runtimePort()
                val previous = assertNotNull(lastBuilder)
                val command = previous.command().toMutableList()
                command[command.indexOf("--port") + 1] = replacementPort.toString()
                command[command.indexOf("--root") + 1] = replacementRoot.toString()
                val builder = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.INHERIT)
                builder.environment().putAll(previous.environment())
                builder.environment()["TEST_HARNESS_HOME"] = replacementRoot.resolve("harness").toString()
                val child = builder.start()
                replacements += child
                val endpoint = "http://127.0.0.1:$replacementPort"
                withTimeout(20_000) {
                    while (true) {
                        check(child.isAlive)
                        if (runtimeReady(endpoint)) break
                        delay(20)
                    }
                }
                return endpoint to replacementRoot
            }
            send("saved-before-replacement"); drive()
            withConnectionManager { service.retire(sessionId) }; drive()
            val checkpoint = withConnectionManager { assertNotNull(repository.get(sessionId)?.archiveKey) }
            for (active in listOf(false, true)) {
                send("warm-before-replacement"); drive()
                val original = withConnectionManager { assertNotNull(repository.get(sessionId)) }
                assertNotNull(original.runtimeInstanceId, "The admitted process identity survives repository mapping")
                val sent = if (active) {
                    send("wait")
                    withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                } else null
                val running = if (active) async { drive() } else null
                if (active) withTimeout(10_000) { while (!Files.exists(root.resolve("harness/waiting-pid"))) delay(20) }
                send("queued-after-replacement")
                val queued = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                val (endpoint, replacementRoot) = replacement()
                withConnectionManager {
                    val current = assertNotNull(repository.get(sessionId))
                    assertEquals(1, repository.save(current.copy(endpoint = endpoint)))
                }
                val uploadsBeforeFailure = archives.size
                if (running != null) withTimeout(25_000) { running.await() } else drive()
                assertFalse(Files.exists(replacementRoot.resolve("native-started")), "A replacement must not execute native work with the old generation's credential")
                withConnectionManager {
                    val failed = assertNotNull(repository.get(sessionId))
                    assertEquals("FAILED", failed.state)
                    assertContains(assertNotNull(failed.failure), "process was replaced")
                    assertEquals(original.runtimeInstanceId, failed.runtimeInstanceId)
                    assertEquals(checkpoint, failed.archiveKey)
                    assertEquals("QUEUED", repository.turn(queued)?.status)
                    assertNull(repository.turn(queued)?.deadline)
                    if (sent != null) assertEquals("INTERRUPTED", repository.turn(sent)?.status)
                    assertTrue(original.credentialId in revoked)
                    assertEquals(uploadsBeforeFailure, archives.size, "A replacement cannot overwrite the saved checkpoint")
                    service.activate(auth, sessionId)
                }
                drive()
                withConnectionManager {
                    val recovered = assertNotNull(repository.get(sessionId))
                    assertEquals(original.generation + 1, recovered.generation)
                    assertNotEquals(original.runtimeInstanceId, recovered.runtimeInstanceId)
                    assertEquals("COMPLETED", repository.turn(queued)?.status)
                    if (sent != null) assertEquals("INTERRUPTED", repository.turn(sent)?.status)
                }
                assertEquals("queued-after-replacement", Files.readString(root.resolve("harness/$nativeState")))
                Files.deleteIfExists(root.resolve("harness/waiting-pid"))
            }
            withConnectionManager { service.delete(sessionId) }; drive()
            withConnectionManager { assertNull(repository.get(sessionId)) }
            return
        }

        suspend fun checkUpgrade() {
            coEvery { configurations.getByKey("kit.harness") } returns null
            send("before-image-upgrade"); drive()
            val before = withConnectionManager { assertNotNull(repository.get(sessionId)) }
            val native = assertNotNull(before.nativeSessionId)
            val extra = root.resolve("harness/new-native-feature/preferences.bin")
            Files.createDirectories(extra.parent); Files.write(extra, byteArrayOf(9, 8, 7))
            Files.writeString(root.resolve("workspace/artifact.txt"), "saved work")
            withConnectionManager { service.retire(sessionId) }; drive()
            val upgradedVersions = config.versions.copy(harness = "next-native-version", adapter = "next-adapter-version")
            deployment = deployment.copy(profiles = deployment.profiles.map { it.copy(image = "runtime@sha256:" + "c".repeat(64),
                imageId = "sha256:" + "d".repeat(64), versions = upgradedVersions, idleSeconds = 120) })
            observedImage = deployment.profiles.first().image
            // Kubelet may report a pull-manifest digest rather than Docker's configuration digest.
            observedImageId = "docker-pullable://" + observedImage
            val upgradedDescriptor = JsonObject(descriptor + mapOf(
                "adapter" to JsonPrimitive(descriptor.getValue("adapter").jsonPrimitive.content.substringBeforeLast('@') + "@next-adapter-version"),
                "harness" to JsonPrimitive(descriptor.getValue("harness").jsonPrimitive.content.substringBeforeLast('@') + "@next-native-version")))
            Files.writeString(root.resolve("compatibility.json"), upgradedDescriptor.toString())
            for (key in listOf("adapter", "harness")) {
                val reference = upgradedDescriptor.getValue(key).jsonPrimitive.content
                Files.writeString(root.resolve("node_modules/${reference.substringBeforeLast('@')}/package.json"), """{"name":"${reference.substringBeforeLast('@')}","version":"${reference.substringAfterLast('@')}"}""")
            }
            send("after-image-upgrade"); drive()
            val after = withConnectionManager { assertNotNull(repository.get(sessionId)) }
            assertEquals(native, after.nativeSessionId)
            assertEquals(before.generation + 1, after.generation)
            assertFalse(after.restoreWorkspaceOnly)
            assertContentEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(extra))
            assertEquals("saved work", Files.readString(root.resolve("workspace/artifact.txt")))
            val setup = json.parseToJsonElement(Files.readString(root.resolve("harness/last-setup.json"))).jsonObject
            assertEquals(native, setup["sessionId"]?.jsonPrimitive?.content, "Use native load rather than creating a new conversation")
            assertEquals("after-image-upgrade", Files.readString(root.resolve("harness/$nativeState")))
            withConnectionManager { service.delete(sessionId) }; drive()
            assertTrue(archives.isEmpty())
        }

        suspend fun exercise() {
            if (upgradeOnly) { checkUpgrade(); return }
            if (instanceFencingOnly) { checkInstanceFencing(); return }
            if (archiveRetentionOnly) {
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                send("retention-first"); drive()
                checkArchiveRetention()
                withConnectionManager { service.delete(sessionId) }; drive()
                withConnectionManager { assertNull(repository.get(sessionId)) }
                assertTrue(archives.isEmpty())
                return
            }
            suspend fun checkArchival() {
                withConnectionManager { service.activate(auth, sessionId) }
                drive()
                assertEquals(1, launches)
                withConnectionManager { assertFalse(repository.hasNativeTurns(sessionId)); service.retire(sessionId) }
                drive()
                withConnectionManager { assertNull(repository.get(sessionId)?.nativeSessionId) }
                send("first"); send("later-queued-message"); drive()
                val prompt = json.parseToJsonElement(Files.readString(root.resolve("harness/last-prompt.json"))).jsonArray
                val context = prompt.first().jsonObject.getValue("text").jsonPrimitive.content
                assertContains(context, "earlier-conversation")
                assertFalse(context.contains("first"), "Current message is supplied once, outside reconstructed context")
                assertFalse(context.contains("later-queued-message"), "Future queued messages are not context for this turn")
                drive()
                send("permission")
                val waiting = async { drive() }
                val approval = withTimeout(10_000) {
                    var found: KitRuntimeApproval? = null
                    while (found == null) {
                        found = withConnectionManager { service.approvals(sessionId).firstOrNull() }
                        if (found == null) delay(10)
                    }
                    found
                }
                val released = withTimeoutOrNull(2_000) {
                    while (pool.activeConnections != 0) delay(10)
                    true
                }
                assertEquals(true, released, "Approval waits must release database connections between polls")
                withConnectionManager {
                    assertEquals("WAITING_FOR_APPROVAL", repository.get(sessionId)?.state)
                    assertFailsWith<IllegalArgumentException> { service.retire(sessionId) }
                    service.decide(auth, sessionId, approval.id, "allow")
                }
                waiting.await()
                send("second"); drive()
                assertEquals(2, launches)
                assertEquals(1, archives.size)
                assertEquals("second", Files.readString(root.resolve("harness/$nativeState")))
                withConnectionManager { service.retire(sessionId) }
                failUpload = true
                assertFailsWith<IllegalStateException> { drive() }
                assertTrue(requireNotNull(process).isAlive)
                withConnectionManager { assertEquals("STOPPING", repository.get(sessionId)?.state); assertEquals("kit/runtime/$sessionId/1.tar.gz", repository.get(sessionId)?.archiveKey) }
                send("third") // Message accepted after the archival claim must stay queued.
                failUpload = false
                corruptRead = true
                assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "checksum mismatch")
                assertTrue(requireNotNull(process).isAlive)
                assertFalse(2L in revoked)
                withConnectionManager { assertEquals("kit/runtime/$sessionId/1.tar.gz", repository.get(sessionId)?.archiveKey) }

                corruptRead = false
                failRevocation = true
                assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "revocation failure")
                assertTrue(requireNotNull(process).isAlive)
                assertFalse(2L in revoked)
                val committed = withConnectionManager { assertNotNull(repository.get(sessionId)) }
                assertEquals("STOPPING", committed.state)
                assertEquals("kit/runtime/$sessionId/2.tar.gz", committed.archiveKey)
                val committedBytes = archives.getValue(assertNotNull(committed.archiveKey)).copyOf()
                val uploadsAtCommit = uploads
                // Cleanup retries must use the committed object even when the local export is gone.
                Files.delete(root.resolve("control/archive.tar.gz"))
                failRevocation = false
                failRemoval = true
                assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "removal failure")
                assertTrue(2L in revoked)
                assertTrue(requireNotNull(process).isAlive)
                failRemoval = false
                delayRemoval = true
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                withConnectionManager {
                    assertEquals("STOPPING", repository.get(sessionId)?.state)
                    assertEquals("QUEUED", repository.next(sessionId)?.status)
                }
                assertEquals(2, launches, "Do not launch a replacement before removal is confirmed")
                delayRemoval = false
                drive()
                withConnectionManager { assertEquals("STOPPED", repository.get(sessionId)?.state); assertNotNull(repository.next(sessionId)) }
                assertEquals(uploadsAtCommit, uploads, "Cleanup retries must not export or upload again")
                assertContentEquals(committedBytes, archives.getValue(assertNotNull(committed.archiveKey)))

                corruptRead = true
                assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "Stored archive verification failed")
                withConnectionManager {
                    assertEquals("STARTING", repository.get(sessionId)?.state)
                    assertEquals(committed.archiveKey, repository.get(sessionId)?.archiveKey)
                    assertEquals("QUEUED", repository.next(sessionId)?.status)
                }
                assertFalse(Files.exists(root.resolve("harness/$nativeState")), "Do not hydrate corrupt state or submit a queued prompt")
                corruptRead = false
                drive()
                assertEquals(3, launches)
                assertEquals(3L, issued)
                assertEquals("third", Files.readString(root.resolve("harness/$nativeState")))
                withConnectionManager {
                    assertEquals("READY", repository.get(sessionId)?.state)
                    assertEquals(3L, repository.get(sessionId)?.generation)
                    assertEquals("native-test", repository.get(sessionId)?.nativeSessionId)
                    assertNull(repository.next(sessionId))
                }
            }
            checkArchival()
            suspend fun pendingApproval(): KitRuntimeApproval = withTimeout(10_000) {
                while (true) {
                    val pending = withConnectionManager { service.approvals(sessionId).firstOrNull() }
                    if (pending != null) return@withTimeout pending
                    delay(10)
                }
                error("unreachable")
            }
            suspend fun checkRecovery() {
                send("permission")
                val denied = async { drive() }
                val denial = pendingApproval()
                withConnectionManager { service.decide(auth, sessionId, denial.id, "deny") }
                denied.await()
                withConnectionManager {
                    assertFailsWith<IllegalStateException> { service.decide(auth, sessionId, denial.id, "allow") }
                }
                assertEquals("third", Files.readString(root.resolve("harness/$nativeState")), "Denied work cannot write")

                send("wait")
                val running = async { drive() }
                val active = withTimeout(10_000) {
                    var turn: UUID? = null
                    while (turn == null) {
                        turn = withConnectionManager { repository.get(sessionId)?.activeTurn?.takeIf { repository.turn(it)?.status == "SENT" } }
                        if (turn == null) delay(10)
                    }
                    turn
                }
                send("queued-before-cancel")
                val queued = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                withConnectionManager { service.cancel(sessionId) }
                send("after-cancel")
                running.await()
                withConnectionManager {
                    assertEquals("CANCELLED", repository.turn(active)?.status)
                    assertEquals("CANCELLED", repository.turn(queued)?.status)
                    assertNotNull(repository.next(sessionId), "Messages accepted after cancellation remain queued")
                    assertFalse(assertNotNull(service.state(sessionId)).cancellationRequested)
                }
                drive()
                assertEquals("after-cancel", Files.readString(root.resolve("harness/$nativeState")))

                send("permission")
                val cancellingApproval = async { drive() }
                val cancelledApproval = pendingApproval()
                withConnectionManager {
                    service.cancel(sessionId)
                    assertFailsWith<IllegalStateException> { service.decide(auth, sessionId, cancelledApproval.id, "allow") }
                }
                cancellingApproval.await()
                withConnectionManager { assertTrue(service.approvals(sessionId).isEmpty()) }

                // A replacement Runner answers the same durable request without submitting another prompt.
                send("permission")
                val disconnected = launch { drive() }
                val durableApproval = pendingApproval()
                disconnected.cancelAndJoin()
                val generation = withConnectionManager { assertNotNull(repository.get(sessionId)).generation }
                val journal = root.resolve("control/${durableApproval.turnId}.jsonl")
                val originalReceipt = Files.readString(journal)
                Files.writeString(journal, originalReceipt.lines().filter(String::isNotBlank).joinToString("\n", postfix = "\n") { line ->
                    val message = json.parseToJsonElement(line).jsonObject
                    if (message["method"]?.jsonPrimitive?.content != "session/request_permission") line else {
                        val params = message.getValue("params").jsonObject
                        val meta = params.getValue("_meta").jsonObject
                        JsonObject(message + ("params" to JsonObject(params + ("_meta" to JsonObject(meta +
                            ("boscaOperationDigest" to JsonPrimitive("changed-arguments"))))))).toString()
                    }
                })
                assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "Permission operation changed")
                Files.writeString(journal, originalReceipt)
                withConnectionManager {
                    val intruder = mockk<AuthenticationContext> {
                        every { principal() } returns mockk<AuthenticatedPrincipal> { every { id } returns UUID.random() }
                    }
                    assertFailsWith<IllegalArgumentException> { service.decide(intruder, sessionId, durableApproval.id, "allow") }
                    assertFailsWith<IllegalArgumentException> { service.decide(auth, sessionId, durableApproval.id, "not-offered") }
                    transaction {
                        val current = assertNotNull(repository.lock(sessionId))
                        assertEquals(1, repository.save(current.copy(generation = current.generation + 1)))
                    }
                    assertFailsWith<IllegalArgumentException> { service.decide(auth, sessionId, durableApproval.id, "allow") }
                    transaction {
                        val current = assertNotNull(repository.lock(sessionId))
                        assertEquals(1, repository.save(current.copy(generation = current.generation - 1)))
                    }
                    service.decide(auth, sessionId, durableApproval.id, "allow")
                }
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                withTimeout(10_000) {
                    while (Files.readString(root.resolve("harness/$nativeState")) != "permission") delay(10)
                }
                drive()
                withConnectionManager { assertEquals("COMPLETED", repository.turn(durableApproval.turnId)?.status) }

                send("approval-sentinel"); drive()
                send("permission")
                val policyWait = async { drive() }
                val stalePolicy = pendingApproval()
                withConnectionManager {
                    val settings = json.decodeFromJsonElement(KitHarnessResolution.serializer(),
                        assertNotNull(repository.turn(stalePolicy.turnId)?.resolvedSettings))
                    assertEquals(settings.policyVersion, stalePolicy.request.jsonObject.getValue("policyVersion").jsonPrimitive.content)
                }
                val originalPolicy = policyValue
                policyValue = policyValue.copy(agentDefaults = mapOf("kit" to "kit"))
                policyWait.await()
                withConnectionManager {
                    assertEquals("CANCELLED", repository.turn(stalePolicy.turnId)?.status)
                    assertEquals("cancelled", repository.approval(stalePolicy.turnId, stalePolicy.requestId)?.decision)
                }
                assertEquals("approval-sentinel", Files.readString(root.resolve("harness/$nativeState")), "Changed policy cannot authorize the native write")
                policyValue = originalPolicy

                // Consent recorded before Runner loss must be checked again at delivery.
                for (change in listOf("policy", "membership")) {
                    send("permission")
                    val lostOwner = launch { drive() }
                    val approved = pendingApproval()
                    lostOwner.cancelAndJoin()
                    policyValue = policyValue.copy(agentDefaults = mapOf("kit" to "kit"))
                    withConnectionManager {
                        assertContains(assertFailsWith<IllegalArgumentException> {
                            service.decide(auth, sessionId, approved.id, "allow")
                        }.message.orEmpty(), "policy or authority changed")
                    }
                    policyValue = originalPolicy
                    withConnectionManager { service.decide(auth, sessionId, approved.id, "allow") }
                    if (change == "policy") policyValue = policyValue.copy(agentDefaults = mapOf("kit" to "kit"))
                    else coEvery { security.getPrincipalGroups(principalId) } returns emptyList()
                    withTimeout(10_000) {
                        while (withConnectionManager { repository.turn(approved.turnId)?.status } == "SENT") {
                            try { drive() } catch (_: bosca.sharedqueue.jobs.DelayException) { delay(25) }
                        }
                    }
                    withConnectionManager {
                        assertEquals("CANCELLED", repository.turn(approved.turnId)?.status)
                        assertEquals("allow", repository.approval(approved.turnId, approved.requestId)?.decision, "Retain the user's original decision for audit")
                    }
                    assertEquals("approval-sentinel", Files.readString(root.resolve("harness/$nativeState")), "Invalidated consent cannot reach the native write")
                    policyValue = originalPolicy
                    coEvery { security.getPrincipalGroups(principalId) } returns listOf(group)
                }

                withConnectionManager { service.retire(sessionId) }
                drive()
                val beforeStartupCancel = launches
                send("cancel-during-startup")
                withConnectionManager { service.cancel(sessionId) }
                drive()
                withConnectionManager { assertEquals("STOPPED", repository.get(sessionId)?.state) }
                assertEquals(beforeStartupCancel, launches, "Cancelled startup cannot launch a workload")
                send("after-startup-cancel"); drive()
                val uncertain = UUID.random()
                withConnectionManager { transaction {
                    val current = assertNotNull(repository.lock(sessionId))
                    repository.add(KitRuntimeTurn(uncertain, sessionId, message = JsonObject(emptyMap())))
                    repository.send(uncertain, OffsetDateTime.now().plusSeconds(config.promptSeconds))
                    assertEquals(1, repository.save(current.copy(state = "BUSY", activeTurn = uncertain)))
                } }
                drive()
                withConnectionManager { assertEquals("INTERRUPTED", repository.turn(uncertain)?.status) }
                assertEquals("after-startup-cancel", Files.readString(root.resolve("harness/$nativeState")), "Uncertain prompt must never be replayed")
                send("ignore-cancel")
                val unresponsive = launch { drive() }
                val unconfirmed = withTimeout(10_000) {
                    while (true) {
                        val id = withConnectionManager { repository.get(sessionId)?.activeTurn }
                        if (id != null && Files.readString(root.resolve("harness/last-prompt.json")).contains("ignore-cancel")) return@withTimeout id
                        delay(10)
                    }
                    error("unreachable")
                }
                unresponsive.cancelAndJoin()
                withConnectionManager {
                    service.cancel(sessionId)
                    transaction {
                        val current = assertNotNull(repository.lock(sessionId))
                        assertEquals(1, repository.save(current.copy(cancelRequestedAt = OffsetDateTime.now().minusMinutes(1))))
                    }
                }
                assertFailsWith<IllegalStateException> { drive() }
                withConnectionManager {
                    assertEquals("INTERRUPTED", repository.turn(unconfirmed)?.status)
                    assertEquals("FAILED", repository.get(sessionId)?.state)
                    assertContains(assertNotNull(repository.get(sessionId)?.failure), "did not confirm cancellation")
                }
                send("recover-after-timeout"); drive()
                assertEquals("recover-after-timeout", Files.readString(root.resolve("harness/$nativeState")))

            }
            checkRecovery()
            suspend fun checkPolicyChanges() = coroutineScope {
                // A pending profile change cannot rewrite an active turn's snapshot.
                grants = listOf(ConfigurationPermission(configuration.id, PermissionAction.EXECUTE, group.id))
                send("permission")
                val immutable = async { drive() }
                val changing = pendingApproval()
                withConnectionManager {
                    val settings = assertNotNull(service.settings(auth, sessionId))
                    val choice = KitHarnessChoice("kit-alternate", settings.policyVersion)
                    service.verifySettings(auth, sessionId, choice)
                    service.setSettings(sessionId, choice)
                    val snapshot = json.decodeFromJsonElement(KitHarnessResolution.serializer(), assertNotNull(repository.turn(changing.turnId)?.resolvedSettings))
                    assertEquals("kit", snapshot.configuration.profile)
                    service.decide(auth, sessionId, changing.id, "allow")
                }
                immutable.await()
                Files.writeString(root.resolve("workspace/keep.txt"), "uncommitted-work")
                val oldNativeContext = root.resolve("harness/$nativeState").resolveSibling("old.json")
                Files.writeString(oldNativeContext, "old-native-context")
                send("new-profile")
                val switchedTurn = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                drive() // Drain the old generation before applying policy.
                withConnectionManager { assertEquals("STOPPED", repository.get(sessionId)?.state) }
                drive()
                assertEquals("uncommitted-work", Files.readString(root.resolve("workspace/keep.txt")))
                assertFalse(Files.exists(oldNativeContext))
                assertContains(Files.readString(root.resolve("harness/last-prompt.json")), "earlier-conversation")
                withConnectionManager {
                    val snapshot = json.decodeFromJsonElement(KitHarnessResolution.serializer(), assertNotNull(repository.turn(switchedTurn)?.resolvedSettings))
                    assertEquals("kit-alternate", snapshot.configuration.profile)
                    assertEquals("CHAT", snapshot.source)
                }
                grants = emptyList()
                send("managed-after-revocation")
                val revokedTurn = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                drive(); drive()
                withConnectionManager {
                    val current = assertNotNull(repository.get(sessionId))
                    assertNull(current.requestedSettings)
                    val settings = assertNotNull(service.settings(auth, sessionId))
                    assertFalse(settings.canOverride)
                    assertTrue(settings.choices.isEmpty())
                    assertEquals("kit", settings.effectiveProfile)
                    assertContains(assertNotNull(settings.notice), "removed")
                    val snapshot = json.decodeFromJsonElement(KitHarnessResolution.serializer(), assertNotNull(repository.turn(revokedTurn)?.resolvedSettings))
                    assertEquals("kit-alternate", snapshot.requested?.profile)
                }
                withConnectionManager { service.retire(sessionId) }; drive()
                exposeExecution = false
                send("policy-changed-during-startup")
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                grants = listOf(ConfigurationPermission(configuration.id, PermissionAction.EXECUTE, group.id))
                withConnectionManager {
                    val choice = KitHarnessChoice("kit-alternate", assertNotNull(service.settings(auth, sessionId)).policyVersion)
                    service.verifySettings(auth, sessionId, choice); service.setSettings(sessionId, choice)
                }
                exposeExecution = true
                delayRemoval = true
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                val cancelledDispatch = withConnectionManager {
                    val pending = assertNotNull(repository.get(sessionId))
                    assertNotNull(pending.cancelRequestedAt, "Startup replacement must persist cancellation before controller removal")
                    assertNotNull(pending.dispatchId)
                }
                delayRemoval = false
                withConnectionManager { jobs.cancel(cancelledDispatch) }
                drive() // Redelivery finishes the persisted cancellation after the controller removes the workload.
                withConnectionManager { assertEquals("STOPPED", repository.get(sessionId)?.state); assertNotNull(repository.next(sessionId)) }
                drive()
                assertEquals("policy-changed-during-startup", Files.readString(root.resolve("harness/$nativeState")))
                send("held-for-policy-repair")
                policyValue = policyValue.copy(defaultProfile = "unavailable")
                assertFailsWith<IllegalStateException> { drive() }
                withConnectionManager {
                    val failed = assertNotNull(repository.get(sessionId))
                    assertEquals("FAILED", failed.state)
                    assertNull(failed.dispatchId)
                    assertNotNull(failed.archiveKey)
                    assertNotNull(repository.next(sessionId))
                    assertFalse(assertNotNull(service.settings(auth, sessionId)).canOverride)
                    assertContains(assertNotNull(service.state(sessionId)?.failure), "policy is unavailable")
                }
                policyValue = policyValue.copy(defaultProfile = "kit")
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                assertEquals("held-for-policy-repair", Files.readString(root.resolve("harness/$nativeState")))
            }
            checkPolicyChanges()
            suspend fun checkContextChanges() {
                chat = chat.copy(state = buildJsonObject { putJsonArray("attachments") { add(source.id.toString()) } })
                contextAgent = bosca.ai.agents.model.Agent(UUID.random(), "kit", "Kit", "", UUID.random(), UUID.random())
                send("with-selected-context")
                val selectedTurn = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                drive(); drive()
                val selectedManifest = withConnectionManager { assertNotNull(repository.turn(selectedTurn)?.contextManifest) }
                val manifest = json.decodeFromJsonElement(KitContextManifest.serializer(), selectedManifest)
                assertEquals(source.id.toString(), manifest.sources.single().id)
                assertContains(Files.readString(root.resolve("harness/last-prompt.json")), "private source body")
                Files.writeString(root.resolve("workspace/keep-instructions.txt"), "user work")
                val staleInstructions = root.resolve("harness/$nativeState").resolveSibling("stale.json")
                Files.writeString(staleInstructions, "stale instructions")
                instruction = "Updated writing instructions"
                send("after-instruction-edit"); drive(); drive()
                assertEquals("user work", Files.readString(root.resolve("workspace/keep-instructions.txt")))
                assertFalse(Files.exists(staleInstructions))
                val updatedPrompt = Files.readString(root.resolve("harness/last-prompt.json"))
                assertContains(updatedPrompt, instruction)
                assertFalse(updatedPrompt.contains("earlier-conversation"))
                withConnectionManager {
                    assertEquals(selectedManifest, repository.turn(selectedTurn)?.contextManifest, "A completed turn's handoff is immutable")
                    service.retire(sessionId)
                }
                drive() // Archive while the source is still accessible.
                exposeExecution = false
                withConnectionManager { service.activate(auth, sessionId) } // Activation without a queued turn.
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                sourceAvailable = false // Revoke while the replacement workload is still preparing.
                exposeExecution = true
                drive() // Cancel the prepared generation before hydrating its stale archive.
                withConnectionManager { assertEquals("STOPPED", repository.get(sessionId)?.state) }
                drive() // Fresh warm runtime, still without a queued turn.
                withConnectionManager { assertEquals("READY", repository.get(sessionId)?.state) }
                assertFalse(Files.exists(root.resolve("workspace/keep-instructions.txt")))
                send("after-context-revocation"); drive()
                val revokedPrompt = Files.readString(root.resolve("harness/last-prompt.json"))
                assertFalse(revokedPrompt.contains("private source body"))
                assertFalse(revokedPrompt.contains("Selected plan"))
                assertFalse(revokedPrompt.contains("earlier-conversation"))
                assertFalse(Files.exists(root.resolve("workspace/keep-instructions.txt")), "Derived workspace files must not restore revoked context")
                withConnectionManager {
                    val status = assertNotNull(service.context(auth, sessionId))
                    assertTrue(status.sources.isEmpty())
                    assertContains(status.notices, "Some selected sources are unavailable.")
                    val current = assertNotNull(repository.get(sessionId))
                    assertTrue(json.decodeFromJsonElement(KitContextManifest.serializer(), assertNotNull(current.contextManifest)).sources.isEmpty())
                }
                coEvery { contextPrompts.get(any<UUID>()) } returns null
                send("held-for-context-repair")
                val held = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                assertFailsWith<IllegalArgumentException> { drive() }
                assertFailsWith<IllegalStateException> { drive() } // Finish archival before surfacing the failed configuration.
                withConnectionManager {
                    assertEquals("FAILED", repository.get(sessionId)?.state)
                    assertEquals("QUEUED", repository.turn(held)?.status)
                    assertNull(repository.turn(held)?.contextManifest)
                    assertContains(assertNotNull(service.state(sessionId)?.failure), "Context could not be prepared")
                }
                coEvery { contextPrompts.get(any<UUID>()) } answers {
                    bosca.ai.prompts.model.Prompt(firstArg(), "kit", "Kit", "", instruction, "", "text", "text", null)
                }
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                assertEquals("held-for-context-repair", Files.readString(root.resolve("harness/$nativeState")))
                withConnectionManager { service.retire(sessionId) }; drive()
                val previousArchive = withConnectionManager { assertNotNull(repository.get(sessionId)?.archiveKey) }
                exposeExecution = false
                send("held-for-startup-context-repair")
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                coEvery { contextPrompts.get(any<UUID>()) } returns null
                exposeExecution = true
                assertFailsWith<IllegalArgumentException> { drive() }
                withConnectionManager {
                    val failed = assertNotNull(repository.get(sessionId))
                    assertEquals("FAILED", failed.state)
                    assertNull(failed.dispatchId)
                    assertEquals(previousArchive, failed.archiveKey, "Never replace a committed workspace with an unhydrated generation")
                }
                coEvery { contextPrompts.get(any<UUID>()) } answers {
                    bosca.ai.prompts.model.Prompt(firstArg(), "kit", "Kit", "", instruction, "", "text", "text", null)
                }
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                assertEquals("held-for-startup-context-repair", Files.readString(root.resolve("harness/$nativeState")))

            }
            checkContextChanges()
            suspend fun checkMemoryChanges() {
                selectedMemory = bosca.ai.memory.Memory(UUID.random(), 1, "initial-memory", "Saved preference before correction",
                    bosca.content.metadata.model.MemoryAttributes(bosca.content.metadata.model.MemoryScope.PERSONAL, principalId), false, emptyList(), OffsetDateTime.now(), null)
                send("with-memory"); drive(); drive()
                assertContains(Files.readString(root.resolve("harness/last-prompt.json")), "Saved preference before correction")
                Files.writeString(root.resolve("workspace/derived-memory.txt"), "Saved preference before correction")
                selectedMemory = requireNotNull(selectedMemory).copy(version = 2, revision = "corrected-memory", statement = "Corrected preference")
                send("after-memory-edit"); drive(); drive()
                assertFalse(Files.exists(root.resolve("workspace/derived-memory.txt")))
                assertContains(Files.readString(root.resolve("harness/last-prompt.json")), "Corrected preference")
                assertFalse(Files.readString(root.resolve("harness/last-prompt.json")).contains("Saved preference before correction"))
                Files.writeString(root.resolve("workspace/forgotten-memory.txt"), "Corrected preference")
                withConnectionManager { service.retire(sessionId) }; drive()
                selectedMemory = null // Forget, expiry, or source revocation all make the current memory unavailable.
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                send("after-memory-forget"); drive()
                assertFalse(Files.exists(root.resolve("workspace/forgotten-memory.txt")))
                assertFalse(Files.readString(root.resolve("harness/last-prompt.json")).contains("Corrected preference"))

                toolMemory = bosca.ai.memory.Memory(UUID.random(), 1, "tool-memory", "Read through MCP",
                    bosca.content.metadata.model.MemoryAttributes(bosca.content.metadata.model.MemoryScope.PERSONAL, principalId), false, emptyList(), OffsetDateTime.now(), null)
                val delivered = requireNotNull(toolMemory)
                memoryReads = listOf(bosca.ai.memory.MemoryRead(sessionId, delivered.id, delivered.revision, delivered.sourceChanged))
                Files.writeString(root.resolve("workspace/mcp-memory.txt"), delivered.statement)
                val warmMemoryGeneration = withConnectionManager { requireNotNull(repository.get(sessionId)).generation }
                send("with-unchanged-mcp-memory"); drive()
                withConnectionManager { assertEquals(warmMemoryGeneration, requireNotNull(repository.get(sessionId)).generation) }
                assertEquals(delivered.statement, Files.readString(root.resolve("workspace/mcp-memory.txt")))
                withConnectionManager { service.retire(sessionId) }; drive()
                toolMemory = null
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                assertFalse(Files.exists(root.resolve("workspace/mcp-memory.txt")))
                assertTrue(memoryReads.isEmpty())
                send("after-mcp-memory-forget"); drive()
                assertFalse(Files.readString(root.resolve("harness/last-prompt.json")).contains("Read through MCP"))
            }
            checkMemoryChanges()
            suspend fun checkMcpConnections() {
                mcpRegistration = bosca.ai.agents.model.McpServerRegistration(UUID.random(), "fixture", "Fixture integration",
                    transportType = bosca.ai.agents.model.McpTransportType.STDIO, configuration = buildJsonObject {
                        put("harness", json.encodeToJsonElement(KitMcpConfiguration.serializer(), KitMcpConfiguration(executable = "fixture",
                            environment = mapOf("REQUIRED_SECRET" to KitMcpSecretReference("MCP_TEST_ACCESS")))))
                    })
                send("with-external-mcp"); drive(); drive()
                val generation = withConnectionManager { requireNotNull(repository.get(sessionId)).generation }
                val setup = json.parseToJsonElement(Files.readString(root.resolve("harness/last-setup.json"))).jsonObject
                assertEquals(listOf("bosca", "fixture"), setup.getValue("mcpServers").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
                assertFalse(setup.toString().contains("fixture-external-secret"))
                send("warm-mcp"); drive()
                withConnectionManager { assertEquals(generation, requireNotNull(repository.get(sessionId)).generation); service.retire(sessionId) }
                drive(); send("restored-mcp"); drive()
                val restored = Files.readString(root.resolve("harness/last-setup.json"))
                assertContains(restored, "fixture")
                assertFalse(restored.contains("fixture-external-secret"))
                mcpRequired = false
                mcpRegistration = requireNotNull(mcpRegistration).copy(transportType = bosca.ai.agents.model.McpTransportType.STREAMABLE_HTTP,
                    configuration = buildJsonObject { put("harness", json.encodeToJsonElement(KitMcpConfiguration.serializer(), KitMcpConfiguration(url = "https://unsupported.invalid/mcp"))) })
                send("optional-unsupported"); drive(); drive()
                withConnectionManager {
                    assertContains(assertNotNull(service.settings(auth, sessionId)).mcpNotices.single(), "does not support")
                    assertNull(repository.next(sessionId))
                    service.retire(sessionId)
                }
                drive()
                mcpRequired = true
                send("required-unsupported"); drive()
                withConnectionManager {
                    assertEquals("FAILED", repository.get(sessionId)?.state)
                    assertEquals("QUEUED", repository.next(sessionId)?.status)
                    assertContains(assertNotNull(service.state(sessionId)?.failure), "MCP fixture")
                }
                mcpRegistration = null
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                assertEquals("required-unsupported", Files.readString(root.resolve("harness/$nativeState")))
                assertFalse(Files.readString(root.resolve("harness/last-setup.json")).contains("fixture"))
            }
            checkMcpConnections()
            suspend fun checkModelControls() {
                grants = listOf(ConfigurationPermission(configuration.id, PermissionAction.EXECUTE, group.id))
                policyValue = policyValue.copy(models = mapOf("kit" to KitModelPolicy(KitModelSelection("small", "medium"),
                    listOf("small", "large"), listOf("low", "medium", "high"))))
                send("managed-model"); drive(); drive()
                fun modelAtPrompt() = json.decodeFromString(KitModelSelection.serializer(), Files.readString(root.resolve("harness/last-model.json")))
                assertEquals(KitModelSelection("small", "medium"), modelAtPrompt())
                suspend fun choose(model: String, reasoning: String? = null) = withConnectionManager {
                    val settings = assertNotNull(service.settings(auth, sessionId))
                    val choice = KitHarnessChoice("kit", settings.policyVersion, model = model, reasoning = reasoning,
                        capabilityVersion = settings.capabilityVersion)
                    service.verifySettings(auth, sessionId, choice)
                    service.setSettings(sessionId, choice)
                }
                choose("large")
                val generation = withConnectionManager { requireNotNull(repository.get(sessionId)).generation }
                send("permission")
                val active = async { drive() }
                val approval = pendingApproval()
                choose("small", "medium") // A new choice cannot mutate the running turn.
                withConnectionManager {
                    val turn = assertNotNull(repository.turn(assertNotNull(repository.get(sessionId)?.activeTurn)))
                    val settings = json.decodeFromJsonElement(KitHarnessResolution.serializer(), assertNotNull(turn.resolvedSettings))
                    assertEquals("large", settings.modelCapabilities?.effective?.model)
                    assertEquals("CHAT", settings.modelSource)
                    service.decide(auth, sessionId, approval.id, "allow")
                }
                active.await()
                assertEquals(KitModelSelection("large", "medium"), modelAtPrompt())
                send("changed-model"); drive()
                assertEquals(KitModelSelection("small", "medium"), modelAtPrompt())
                withConnectionManager { assertEquals(generation, repository.get(sessionId)?.generation) }
                choose("large"); send("large-model"); drive()
                choose("large", "high"); send("high-reasoning"); drive()
                assertEquals(KitModelSelection("large", "high"), modelAtPrompt())
                send("warm-high"); drive()
                assertEquals(KitModelSelection("large", "high"), modelAtPrompt())
                withConnectionManager { assertEquals(generation, repository.get(sessionId)?.generation); service.retire(sessionId) }
                drive(); send("cold-high"); drive()
                assertEquals(KitModelSelection("large", "high"), modelAtPrompt())
                withConnectionManager { service.retire(sessionId) }; drive()
                Files.writeString(root.resolve("disable-high"), "")
                send("held-for-capability-repair")
                assertFailsWith<bosca.ai.kit.harness.acp.KitModelConfigurationException> { drive() }
                withConnectionManager {
                    assertEquals("FAILED", repository.get(sessionId)?.state)
                    assertEquals("QUEUED", repository.next(sessionId)?.status)
                    assertContains(assertNotNull(service.state(sessionId)?.failure), "reasoning effort is unavailable")
                }
                choose("large", "medium")
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                assertEquals("held-for-capability-repair", Files.readString(root.resolve("harness/$nativeState")))
                assertEquals(KitModelSelection("large", "medium"), modelAtPrompt())
                grants = emptyList()
                send("revoked-model-override"); drive(); drive()
                assertEquals(KitModelSelection("small", "medium"), modelAtPrompt())
                withConnectionManager {
                    val settings = assertNotNull(service.settings(auth, sessionId))
                    assertFalse(settings.canOverride)
                    assertTrue(settings.models.isEmpty())
                    assertEquals("SYSTEM", settings.modelSource)
                    assertContains(assertNotNull(settings.notice), "removed")
                }
                Files.delete(root.resolve("disable-high"))
            }
            checkModelControls()
            suspend fun checkRichResults() {
                suspend fun checkResult(id: UUID) {
                    withConnectionManager { assertEquals("COMPLETED", repository.turn(id)?.status) }
                    val display = assertNotNull(displayMessages[id]).event
                    val event = json.decodeFromJsonElement(KitHarnessTurnEvent.serializer(), display)
                    assertEquals("COMPLETED", event.status)
                    val content = event.tools.single().getValue("content").jsonArray
                        .map { it.jsonObject.getValue("content").jsonObject }
                    assertEquals(listOf("resource", "image", "resource_link"), content.map { it.getValue("type").jsonPrimitive.content })
                    assertContains(content[0].getValue("resource").jsonObject.getValue("text").jsonPrimitive.content, "sessions")
                    assertFalse(display.toString().contains("private-input"))
                }
                send("rich-results")
                val live = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                drive(); checkResult(live)
                val completed = measurement("turn.success.count")
                drive()
                assertEquals(completed, measurement("turn.success.count"), "Terminal redelivery must not duplicate terminal metrics")
                send("rich-results-recovery")
                val recovered = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                val disconnected = launch { drive() }
                withTimeout(10_000) {
                    while (Files.readString(root.resolve("harness/rich-results-emitted")) != "rich-results-recovery") delay(10)
                }
                disconnected.cancelAndJoin()
                Files.writeString(root.resolve("release-rich-results"), "")
                val current = withConnectionManager { assertNotNull(repository.get(sessionId)) }
                val journal = root.resolve("control/$recovered.jsonl")
                withTimeout(10_000) {
                    while (!Files.readString(journal).contains("end_turn")) delay(10)
                }
                drive(); checkResult(recovered)
                drive(); checkResult(recovered)
                Files.delete(root.resolve("release-rich-results"))
            }
            checkRichResults()
            suspend fun checkStartupDeadline() {
                withConnectionManager { service.retire(sessionId) }; drive()
                val checkpoint = withConnectionManager { assertNotNull(repository.get(sessionId)?.archiveKey) }
                exposeExecution = false
                send("held-for-startup-deadline")
                repeat(2) { assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() } }
                val starting = withConnectionManager { assertNotNull(repository.get(sessionId)) }
                val pending = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                val dispatchId = assertNotNull(starting.dispatchId)
                executions[dispatchId] = executions.getValue(dispatchId).copy(createdAt = OffsetDateTime.now().minusSeconds(config.startupSeconds + 1))
                failRevocation = true
                assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "revocation failure")
                withConnectionManager {
                    assertNotNull(repository.get(sessionId)?.cancelRequestedAt)
                    assertContains(assertNotNull(repository.get(sessionId)?.failure), "startup deadline")
                    assertEquals("QUEUED", repository.turn(pending)?.status)
                }
                failRevocation = false
                delayRemoval = true
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                assertTrue(starting.credentialId in revoked)
                delayRemoval = false
                drive()
                withConnectionManager {
                    val failed = assertNotNull(repository.get(sessionId))
                    assertEquals("FAILED", failed.state)
                    assertNull(failed.dispatchId)
                    assertEquals(checkpoint, failed.archiveKey)
                    assertEquals("QUEUED", repository.turn(pending)?.status)
                    assertNull(repository.turn(pending)?.resolvedSettings)
                }
                exposeExecution = true
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                assertEquals("held-for-startup-deadline", Files.readString(root.resolve("harness/$nativeState")))

                withConnectionManager { service.retire(sessionId) }; drive()
                Files.writeString(root.resolve("hold-initialize"), "")
                send("held-for-runner-restart")
                val interrupted = launch { drive() }
                withTimeout(10_000) { while (!Files.exists(root.resolve("harness/initialization-started"))) delay(10) }
                interrupted.cancelAndJoin()
                withConnectionManager {
                    val current = assertNotNull(repository.get(sessionId))
                    assertEquals("STARTING", current.state)
                    assertNull(current.failure, "Parent cancellation must not be mistaken for startup expiry")
                    assertNull(current.cancelRequestedAt)
                }
                Files.delete(root.resolve("hold-initialize"))
                drive()
                assertEquals("held-for-runner-restart", Files.readString(root.resolve("harness/$nativeState")))

                for (stage in listOf("restore", "native-readiness")) {
                    withConnectionManager { service.retire(sessionId) }; drive()
                    val beforeTimeout = withConnectionManager { assertNotNull(repository.get(sessionId)) }
                    val uploadsBeforeTimeout = uploads
                    stallArchiveRead = stage == "restore"
                    if (stage == "native-readiness") Files.writeString(root.resolve("hold-initialize"), "")
                    startupRemainingSeconds = 5
                    send("held-for-$stage")
                    withTimeout(15_000) { drive() }
                    withConnectionManager {
                        val failed = assertNotNull(repository.get(sessionId))
                        assertEquals("FAILED", failed.state)
                        assertNull(failed.dispatchId)
                        assertEquals(beforeTimeout.archiveKey, failed.archiveKey)
                        assertEquals("QUEUED", repository.next(sessionId)?.status)
                        assertContains(assertNotNull(failed.failure), "startup deadline")
                    }
                    assertEquals(uploadsBeforeTimeout, uploads, "Do not replace the checkpoint with a partially prepared runtime")
                    Files.deleteIfExists(root.resolve("hold-initialize"))
                    stallArchiveRead = false
                    startupRemainingSeconds = null
                    withConnectionManager { service.activate(auth, sessionId) }; drive()
                    assertEquals("held-for-$stage", Files.readString(root.resolve("harness/$nativeState")))
                }
            }
            checkStartupDeadline()
            suspend fun checkJournalLimits() {
                for (limit in listOf("bytes", "turns")) {
                    withConnectionManager { service.retire(sessionId) }; drive()
                    deployment = deployment.copy(profiles = deployment.profiles.map {
                        it.copy(maxJournalBytes = if (limit == "bytes") 1024 else 4096, maxJournalTurns = if (limit == "turns") 1 else 256)
                    })
                    send(if (limit == "bytes") "x".repeat(600) else "journal-count")
                    val first = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                    drive()
                    val full = withConnectionManager { assertNotNull(repository.get(sessionId)) }
                    val beforeRetirement = launches
                    send("queued-after-journal-$limit")
                    val pending = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                    drive()
                    withConnectionManager {
                        val stopped = assertNotNull(repository.get(sessionId))
                        assertEquals("STOPPED", stopped.state)
                        assertEquals("kit/runtime/$sessionId/${full.generation}.tar.gz", stopped.archiveKey)
                        assertEquals("COMPLETED", repository.turn(first)?.status)
                        assertEquals("QUEUED", repository.turn(pending)?.status)
                        assertNull(repository.turn(pending)?.resolvedSettings)
                    }
                    assertEquals(beforeRetirement, launches)
                    assertTrue(full.credentialId in revoked)
                    drive()
                    assertEquals(beforeRetirement + 1, launches)
                    withConnectionManager {
                        assertEquals("COMPLETED", repository.turn(pending)?.status)
                        assertEquals(full.generation + 1, repository.get(sessionId)?.generation)
                    }
                    assertEquals("queued-after-journal-$limit", Files.readString(root.resolve("harness/$nativeState")))
                }

                withConnectionManager { service.retire(sessionId) }; drive()
                deployment = deployment.copy(profiles = deployment.profiles.map { it.copy(maxJournalBytes = 1024, maxJournalTurns = 256) })
                val checkpoint = withConnectionManager { assertNotNull(repository.get(sessionId)?.archiveKey) }
                val previousUploads = uploads
                send("journal-flood-recovery")
                val uncertain = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                val disconnected = launch { drive() }
                val pidFile = root.resolve("harness/journal-writer-pid")
                withTimeout(10_000) { while (!Files.exists(pidFile)) delay(10) }
                disconnected.cancelAndJoin()
                send("queued-after-journal-overflow")
                val pending = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                val pid = Files.readString(pidFile).toLong()
                Files.writeString(root.resolve("release-journal-flood"), "")
                withTimeout(10_000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10) }
                assertTrue(Files.size(root.resolve("control/$uncertain.jsonl")) <= 1024)
                drive()
                withConnectionManager {
                    val failed = assertNotNull(repository.get(sessionId))
                    assertEquals("FAILED", failed.state)
                    assertEquals("INTERRUPTED", repository.turn(uncertain)?.status)
                    assertEquals("QUEUED", repository.turn(pending)?.status)
                    assertEquals(checkpoint, failed.archiveKey)
                    assertContains(assertNotNull(failed.failure), "journal exceeded")
                }
                assertEquals(previousUploads, uploads)
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                withConnectionManager {
                    assertEquals("COMPLETED", repository.turn(pending)?.status)
                    assertEquals("INTERRUPTED", repository.turn(uncertain)?.status)
                }
                assertFalse(Files.exists(pidFile), "Recovery must not replay the overflowing prompt")
                assertEquals("queued-after-journal-overflow", Files.readString(root.resolve("harness/$nativeState")))
                Files.delete(root.resolve("release-journal-flood"))
            }
            checkJournalLimits()
            suspend fun checkPhaseBudgets() {
                fun configure(readiness: Long = 600, restore: Long = 600, approval: Long = 86400) {
                    deployment = deployment.copy(profiles = deployment.profiles.map {
                        it.copy(readinessSeconds = readiness, restoreSeconds = restore, approvalSeconds = approval,
                            maxJournalBytes = config.maxJournalBytes, maxJournalTurns = config.maxJournalTurns)
                    })
                }
                withConnectionManager { service.retire(sessionId) }; drive()
                configure(restore = 1)
                val checkpoint = withConnectionManager { assertNotNull(repository.get(sessionId)?.archiveKey) }
                stallArchiveRead = true
                send("held-for-restore-budget")
                val restoreTurn = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                withTimeout(15_000) {
                    assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "restore deadline")
                }
                val restoring = withConnectionManager {
                    val current = assertNotNull(repository.get(sessionId))
                    assertEquals("STARTING", current.state)
                    assertEquals(checkpoint, current.archiveKey)
                    assertNull(current.failure)
                    assertNull(current.cancelRequestedAt)
                    assertEquals("QUEUED", repository.turn(restoreTurn)?.status)
                    assertNull(repository.turn(restoreTurn)?.resolvedSettings)
                    current
                }
                val restoreLaunches = launches
                stallArchiveRead = false
                drive()
                withConnectionManager {
                    assertEquals("COMPLETED", repository.turn(restoreTurn)?.status)
                    assertEquals(restoring.dispatchId, repository.get(sessionId)?.dispatchId)
                }
                assertEquals(restoreLaunches, launches, "A phase retry retains the original generation and startup deadline")

                withConnectionManager { service.retire(sessionId) }; drive()
                configure(readiness = 1)
                Files.writeString(root.resolve("hold-initialize"), "")
                send("held-for-readiness-budget")
                val readyTurn = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                withTimeout(15_000) {
                    assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "readiness deadline")
                }
                val preparing = withConnectionManager {
                    val current = assertNotNull(repository.get(sessionId))
                    assertEquals("STARTING", current.state)
                    assertEquals("QUEUED", repository.turn(readyTurn)?.status)
                    assertNull(repository.turn(readyTurn)?.resolvedSettings)
                    current
                }
                withTimeout(15_000) {
                    assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "readiness deadline")
                }
                Files.delete(root.resolve("hold-initialize"))
                withTimeout(15_000) {
                    while (true) {
                        try { drive(); break }
                        catch (error: IllegalStateException) {
                            assertContains(error.message.orEmpty(), "readiness deadline")
                            withConnectionManager {
                                assertEquals(preparing.dispatchId, repository.get(sessionId)?.dispatchId)
                                assertEquals("QUEUED", repository.turn(readyTurn)?.status)
                                assertNull(repository.turn(readyTurn)?.resolvedSettings)
                            }
                        }
                    }
                }
                withConnectionManager {
                    assertEquals("COMPLETED", repository.turn(readyTurn)?.status)
                    assertEquals(preparing.dispatchId, repository.get(sessionId)?.dispatchId)
                }
                send("slow-turn")
                val slow = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                drive()
                withConnectionManager { assertEquals("COMPLETED", repository.turn(slow)?.status) }
                assertEquals("slow-turn", Files.readString(root.resolve("harness/$nativeState")), "Readiness expiry must not cancel an already submitted prompt")

                withConnectionManager { service.retire(sessionId) }; drive()
                configure(approval = 2)
                send("permission")
                val live = async { drive() }
                val liveApproval = pendingApproval()
                assertTrue(java.time.Duration.between(OffsetDateTime.now(), liveApproval.expires).seconds in 0..2)
                withTimeout(10_000) { live.await() }
                withConnectionManager {
                    assertEquals("CANCELLED", repository.turn(liveApproval.turnId)?.status)
                    assertEquals("cancelled", repository.approval(liveApproval.turnId, liveApproval.requestId)?.decision)
                }
                assertEquals("slow-turn", Files.readString(root.resolve("harness/$nativeState")), "Expired approval must preserve the last completed native write")

                send("permission")
                val disconnected = launch { drive() }
                val recoveredApproval = pendingApproval()
                disconnected.cancelAndJoin()
                delay((java.time.Duration.between(OffsetDateTime.now(), recoveredApproval.expires).toMillis() + 20).coerceAtLeast(1))
                withConnectionManager {
                    assertContains(assertFailsWith<IllegalArgumentException> {
                        service.decide(auth, sessionId, recoveredApproval.id, "allow")
                    }.message.orEmpty(), "Approval expired")
                }
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                withConnectionManager {
                    val recovered = assertNotNull(repository.approval(recoveredApproval.turnId, recoveredApproval.requestId))
                    assertEquals(recoveredApproval.expires, recovered.expires, "Runner replacement must not extend the decision deadline")
                    assertEquals("cancelled", recovered.decision)
                }
                withTimeout(10_000) {
                    while (!Files.readString(root.resolve("control/${recoveredApproval.turnId}.jsonl")).contains("cancelled")) delay(10)
                }
                drive()
                withConnectionManager { assertEquals("CANCELLED", repository.turn(recoveredApproval.turnId)?.status) }
                assertEquals("slow-turn", Files.readString(root.resolve("harness/$nativeState")), "Recovered expired approval must preserve the last completed native write")
            }
            checkPhaseBudgets()
            suspend fun checkTurnAndRuntimeDeadlines() {
                fun configure(prompt: Long = 86400, hard: Long = 86400) {
                    deployment = deployment.copy(profiles = deployment.profiles.map {
                        it.copy(readinessSeconds = 600, restoreSeconds = 600, approvalSeconds = 86400,
                            promptSeconds = prompt, hardRuntimeSeconds = hard, cancellationSeconds = 2)
                    })
                }
                suspend fun waitingTurn(): KitRuntimeTurn = withTimeout(10_000) {
                    while (true) {
                        val turn = withConnectionManager { repository.get(sessionId)?.activeTurn?.let { repository.turn(it) } }
                        if (turn?.status == "SENT" && Files.exists(root.resolve("harness/waiting-pid"))) return@withTimeout turn
                        delay(10)
                    }
                    error("unreachable")
                }
                suspend fun reconcileCompleted(id: UUID) = withTimeout(10_000) {
                    while (true) {
                        try { drive() }
                        catch (_: bosca.sharedqueue.jobs.DelayException) { }
                        if (withConnectionManager { repository.turn(id)?.status } != "SENT") return@withTimeout
                        delay(10)
                    }
                }

                withConnectionManager { service.retire(sessionId) }; drive()
                configure(prompt = 2)
                send("wait")
                delay(2200) // Queue time must not consume the prompt budget.
                val live = async { drive() }
                val timed = waitingTurn()
                assertTrue(assertNotNull(timed.deadline).isAfter(OffsetDateTime.now()))
                assertTrue(java.time.Duration.between(timed.created, timed.deadline).seconds >= 4)
                send("queued-after-prompt-deadline")
                val queued = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                withTimeout(10_000) { live.await() }
                withConnectionManager {
                    assertEquals("CANCELLED", repository.turn(timed.id)?.status)
                    assertEquals("QUEUED", repository.turn(queued)?.status)
                    assertNull(repository.turn(queued)?.deadline)
                    assertNull(repository.get(sessionId)?.cancelRequestedAt)
                }
                drive()
                withConnectionManager { assertEquals("COMPLETED", repository.turn(queued)?.status) }

                send("permission")
                val lost = launch { drive() }
                val approval = pendingApproval()
                val deadline = withConnectionManager { assertNotNull(repository.turn(approval.turnId)?.deadline) }
                assertEquals(deadline, approval.expires, "A permission wait cannot extend the prompt budget")
                lost.cancelAndJoin()
                delay((java.time.Duration.between(OffsetDateTime.now(), deadline).toMillis() + 20).coerceAtLeast(1))
                withConnectionManager {
                    assertContains(assertFailsWith<IllegalArgumentException> {
                        service.decide(auth, sessionId, approval.id, "allow")
                    }.message.orEmpty(), "Approval expired")
                }
                reconcileCompleted(approval.turnId)
                withConnectionManager {
                    assertEquals(deadline, repository.turn(approval.turnId)?.deadline)
                    assertEquals("CANCELLED", repository.turn(approval.turnId)?.status)
                    assertEquals("cancelled", repository.approval(approval.turnId, approval.requestId)?.decision)
                }
                assertEquals("queued-after-prompt-deadline", Files.readString(root.resolve("harness/$nativeState")))

                Files.deleteIfExists(root.resolve("harness/waiting-pid"))
                send("ignore-cancel")
                val ignored = async { assertFails { drive() } }
                val unconfirmed = waitingTurn()
                send("queued-after-unconfirmed-deadline")
                val preserved = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                withTimeout(15_000) { ignored.await() }
                withConnectionManager {
                    assertContains(assertNotNull(repository.get(sessionId)?.failure), "did not confirm cancellation")
                }
                drive() // Workload removal may disconnect ACP before the live owner finishes cleanup.
                withConnectionManager {
                    assertEquals("INTERRUPTED", repository.turn(unconfirmed.id)?.status)
                    assertEquals("FAILED", repository.get(sessionId)?.state)
                    assertEquals("QUEUED", repository.turn(preserved)?.status)
                    assertContains(assertNotNull(repository.get(sessionId)?.failure), "did not confirm cancellation")
                    service.activate(auth, sessionId)
                }
                drive()
                withConnectionManager { assertEquals("COMPLETED", repository.turn(preserved)?.status) }

                withConnectionManager { service.retire(sessionId) }; drive()
                configure(hard = 8)
                send("ignore-cancel")
                val disconnected = launch { drive() }
                val interrupted = waitingTurn()
                val beforeExpiry = withConnectionManager { assertNotNull(repository.get(sessionId)) }
                val nativePid = Files.readString(root.resolve("harness/waiting-pid")).toLong()
                send("queued-after-hard-deadline")
                val pending = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                disconnected.cancelAndJoin()
                val secret = json.decodeFromJsonElement(KubernetesJobSecrets.serializer(), assertNotNull(beforeExpiry.secrets))
                val values = json.parseToJsonElement(Base64.getDecoder().decode(secret.ciphertext).decodeToString()).jsonObject
                val expires = values.getValue("BOSCA_RUNTIME_EXPIRES").jsonPrimitive.long
                delay((expires * 1000 - System.currentTimeMillis() + 20).coerceAtLeast(1))
                // Native work gets stopHarnessProcess's full allowance: 20s to exit on SIGTERM, then 5s after SIGKILL.
                withTimeout(25_000) { while (ProcessHandle.of(nativePid).map { it.isAlive }.orElse(false)) delay(10) }
                assertTrue(assertNotNull(process).isAlive, "The supervisor must stop native work even without a Runner")
                failRevocation = true
                assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "revocation failure")
                withConnectionManager {
                    val current = assertNotNull(repository.get(sessionId))
                    assertContains(assertNotNull(current.failure), "hard-runtime deadline")
                    assertNotNull(current.cancelRequestedAt)
                    assertEquals("SENT", repository.turn(interrupted.id)?.status)
                    assertEquals("QUEUED", repository.turn(pending)?.status)
                    assertEquals(beforeExpiry.archiveKey, current.archiveKey)
                }
                failRevocation = false
                delayRemoval = true
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                withConnectionManager { assertEquals("SENT", repository.turn(interrupted.id)?.status) }
                delayRemoval = false
                drive()
                withConnectionManager {
                    assertEquals("FAILED", repository.get(sessionId)?.state)
                    assertEquals("INTERRUPTED", repository.turn(interrupted.id)?.status)
                    assertEquals("QUEUED", repository.turn(pending)?.status)
                }
                configure()
                withConnectionManager { service.activate(auth, sessionId) }
                drive()
                withConnectionManager {
                    assertEquals("COMPLETED", repository.turn(pending)?.status)
                    assertEquals("INTERRUPTED", repository.turn(interrupted.id)?.status)
                }
                assertEquals("queued-after-hard-deadline", Files.readString(root.resolve("harness/$nativeState")))

                withConnectionManager { service.retire(sessionId) }; drive()
                configure(hard = 6)
                send("ignore-cancel")
                val liveHardExpiry = async {
                    try { drive() }
                    catch (_: bosca.ai.kit.harness.acp.AcpConnectionException) { }
                }
                val hardLimited = waitingTurn()
                withTimeout(15_000) { liveHardExpiry.await() }
                drive()
                withConnectionManager {
                    assertEquals("FAILED", repository.get(sessionId)?.state)
                    assertEquals("INTERRUPTED", repository.turn(hardLimited.id)?.status)
                    assertContains(assertNotNull(repository.get(sessionId)?.failure), "hard-runtime deadline")
                }
                configure()
                send("after-live-hard-deadline"); drive()

                withConnectionManager { service.retire(sessionId) }; drive()
                configure(hard = 4)
                Files.writeString(root.resolve("hold-initialize"), "")
                send("unsent-at-hard-deadline")
                val unsent = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                withTimeout(15_000) { drive() }
                withConnectionManager {
                    val failed = assertNotNull(repository.get(sessionId))
                    assertEquals("FAILED", failed.state)
                    assertContains(assertNotNull(failed.failure), "hard-runtime deadline during startup")
                    assertEquals("QUEUED", repository.turn(unsent)?.status)
                    assertNull(repository.turn(unsent)?.deadline)
                    assertNull(failed.dispatchId)
                }
                Files.delete(root.resolve("hold-initialize"))
                configure()
                withConnectionManager { service.activate(auth, sessionId) }
                drive()
                withConnectionManager { assertEquals("COMPLETED", repository.turn(unsent)?.status) }
            }
            checkTurnAndRuntimeDeadlines()
            suspend fun checkAdmission() {
                deployment = deployment.copy(maxRuntimes = 1, maxRuntimesPerPrincipal = 1)
                val warmLaunches = launches
                val warmSession = withConnectionManager { repository.get(sessionId)?.nativeSessionId }
                send("warm-at-capacity"); drive()
                assertEquals(warmLaunches, launches, "Admission limits do not interrupt an already admitted generation")
                withConnectionManager { assertEquals(warmSession, repository.get(sessionId)?.nativeSessionId); service.retire(sessionId) }
                drive()
                for (limit in listOf("principal", "global")) {
                    deployment = deployment.copy(maxRuntimes = if (limit == "principal") 2 else 1,
                        maxRuntimesPerPrincipal = if (limit == "principal") 1 else 2)
                    val blocker = KitRuntimeSession(UUID.random(), if (limit == "principal") principalId else UUID.random(),
                        json.encodeToJsonElement(KitHarnessConfiguration.serializer(), config.copy(profile = "kit-alternate")),
                        JsonObject(emptyMap()), idleAt = OffsetDateTime.now())
                    withConnectionManager {
                        repository.create(blocker)
                        assertEquals(1, repository.save(blocker.copy(state = "STOPPING", dispatchId = UUID.random())))
                    }
                    try {
                        val before = withConnectionManager { assertNotNull(repository.get(sessionId)) }
                        val previousLaunches = launches
                        val previousIssued = issued
                        send("queued-at-$limit-capacity")
                        val turn = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                        repeat(2) {
                            if (it > 0) withConnectionManager { service.activate(auth, sessionId) }
                            drive()
                            withConnectionManager {
                                val limited = assertNotNull(repository.get(sessionId))
                                assertEquals("FAILED", limited.state)
                                assertContains(assertNotNull(limited.failure), "capacity is in use")
                                assertNull(limited.dispatchId); assertNull(limited.credentialId); assertNull(limited.secrets)
                                assertEquals(before.archiveKey, limited.archiveKey)
                                assertEquals("QUEUED", repository.turn(turn)?.status)
                                assertNull(repository.turn(turn)?.deadline)
                                assertFalse(repository.pending(100, 0, 0).any { pending -> pending.sessionId == sessionId })
                            }
                            assertEquals(previousIssued, issued, "Admission must precede scoped credential issuance")
                            assertEquals(previousLaunches, launches, "Rejected admission cannot dispatch a workload")
                        }
                        withConnectionManager {
                            val removed = assertNotNull(repository.get(blocker.sessionId))
                            assertEquals(1, repository.save(removed.copy(state = "FAILED")))
                            service.activate(auth, sessionId)
                        }
                        drive()
                        withConnectionManager { assertEquals("COMPLETED", repository.turn(turn)?.status) }
                        assertEquals(previousLaunches + 1, launches)
                        assertEquals(previousIssued + 1, issued)
                        assertEquals("queued-at-$limit-capacity", Files.readString(root.resolve("harness/$nativeState")))
                    } finally {
                        withConnectionManager {
                            val current = assertNotNull(repository.get(blocker.sessionId))
                            val owner = UUID.random()
                            assertEquals(1, repository.save(current.copy(owner = owner, deletedAt = OffsetDateTime.now())))
                            assertEquals(1, repository.deleteSession(current.sessionId, owner, current.epoch))
                        }
                    }
                    if (limit == "principal") { withConnectionManager { service.retire(sessionId) }; drive() }
                }
            }
            checkAdmission()
            suspend fun checkRuntimeIdentity() {
                withConnectionManager { service.retire(sessionId) }; drive()
                Files.deleteIfExists(root.resolve("native-started"))
                val reads = archiveReads
                val checkpoint = withConnectionManager { assertNotNull(repository.get(sessionId)?.archiveKey) }
                send("identity-qualified-turn")
                val turn = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                observedImageId = null
                assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
                withConnectionManager {
                    assertEquals("STARTING", repository.get(sessionId)?.state)
                    assertEquals("QUEUED", repository.turn(turn)?.status)
                }
                assertFalse(Files.exists(root.resolve("native-started")), "Missing image observation must not launch native work")
                val dispatch = withConnectionManager { assertNotNull(repository.get(sessionId)?.dispatchId) }
                executions[dispatch] = executions.getValue(dispatch).copy(imageId = "wrong-image")
                failRemoval = true
                assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "removal failure")
                withConnectionManager {
                    assertNotNull(repository.get(sessionId)?.cancelRequestedAt)
                    assertTrue(issued in revoked)
                }
                failRemoval = false
                drive()
                for (mismatch in listOf("image", "cli", "adapter", "harness", "statePolicy")) {
                    withConnectionManager {
                        val failed = assertNotNull(repository.get(sessionId))
                        assertEquals("FAILED", failed.state)
                        assertContains(assertNotNull(failed.failure), "does not match")
                        assertEquals(checkpoint, failed.archiveKey)
                        assertNull(failed.credentialId)
                        assertTrue(issued in revoked)
                        assertEquals("QUEUED", repository.turn(turn)?.status)
                        assertNull(repository.turn(turn)?.deadline)
                        assertNull(failed.dispatchId)
                        assertTrue(executions.values.all { it.status.isTerminal })
                    }
                    observedImage = if (mismatch == "image") "unexpected:latest" else config.image
                    observedImageId = config.imageId
                    deployment = deployment.copy(profiles = deployment.profiles.map { profile -> profile.copy(versions = when (mismatch) {
                        "cli" -> config.versions.copy(cli = "unexpected")
                        "adapter" -> config.versions.copy(adapter = "unexpected")
                        "harness" -> config.versions.copy(harness = "unexpected")
                        "statePolicy" -> config.versions.copy(statePolicy = "unexpected")
                        else -> config.versions
                    }) })
                    withConnectionManager { service.activate(auth, sessionId) }; drive()
                }
                withConnectionManager { assertEquals("FAILED", repository.get(sessionId)?.state); assertEquals("QUEUED", repository.turn(turn)?.status) }
                assertFalse(Files.exists(root.resolve("native-started")), "Mismatched runtime must not launch native work")
                assertEquals(reads, archiveReads, "Mismatched runtime must not receive a checkpoint")
                deployment = deployment.copy(profiles = deployment.profiles.map { it.copy(versions = config.versions) })
                withConnectionManager { service.activate(auth, sessionId) }; drive()
                withConnectionManager { assertEquals("COMPLETED", repository.turn(turn)?.status) }
                assertEquals("identity-qualified-turn", Files.readString(root.resolve("harness/$nativeState")))
            }
            checkRuntimeIdentity()
            suspend fun checkDomainContext() {
                val specSource = KitContextSelection(KitContextSourceKind.SPEC, specification.id)
                val fileSource = KitContextSelection(KitContextSourceKind.GIT_FILE, gitRepository.id, "main", "docs/plan.md")
                val before = withConnectionManager { assertNotNull(repository.get(sessionId)) }
                val launched = launches
                withConnectionManager {
                    service.selectContext(sessionId, specSource, false)
                    service.selectContext(sessionId, fileSource, false)
                    service.selectContext(sessionId, fileSource, false)
                    assertEquals(before.idleAt, repository.get(sessionId)?.idleAt, "Selection does not extend runtime lifetime")
                }
                assertEquals(launched, launches, "Selecting context cannot allocate a runtime")
                send("with-domain-context")
                val turn = withConnectionManager { assertNotNull(repository.next(sessionId)).id }
                drive(); drive()
                val original = withConnectionManager { assertNotNull(repository.turn(turn)?.contextManifest) }
                val manifest = json.decodeFromJsonElement(KitContextManifest.serializer(), original)
                assertTrue(manifest.sources.any { it.kind == "spec" && it.id == specification.id.toString() })
                assertEquals(gitCommit, manifest.sources.single { it.kind == "git_file" }.version)
                assertContains(Files.readString(root.resolve("harness/last-prompt.json")), "Domain Git source at $gitCommit")
                val warm = launches
                send("unchanged-domain-context"); drive()
                assertEquals(warm, launches)
                Files.writeString(root.resolve("workspace/domain-work.txt"), "retained user work")
                gitCommit = "c".repeat(40)
                send("after-git-advance"); drive(); drive()
                assertEquals("retained user work", Files.readString(root.resolve("workspace/domain-work.txt")))
                assertContains(Files.readString(root.resolve("harness/last-prompt.json")), "Domain Git source at $gitCommit")
                withConnectionManager {
                    assertEquals(original, repository.turn(turn)?.contextManifest, "Completed source handoffs are immutable")
                    service.retire(sessionId)
                }
                drive()
                withConnectionManager { assertNotNull(repository.get(sessionId)?.archiveCreated) }
                domainAllowed = false
                send("after-domain-revocation"); drive(); drive()
                assertFalse(Files.exists(root.resolve("workspace/domain-work.txt")), "Revoked domain context cannot survive in restored workspace files")
                assertFalse(Files.readString(root.resolve("harness/last-prompt.json")).contains("Domain Git source"))
                withConnectionManager {
                    assertTrue(service.context(auth, sessionId)?.sources.orEmpty().none { it.kind in setOf("spec", "git_file") })
                    service.selectContext(sessionId, specSource, true)
                    service.selectContext(sessionId, fileSource, true)
                    val selections = (0 until 16).map { KitContextSelection(KitContextSourceKind.SPEC, UUID.random()) }
                    selections.forEach { service.selectContext(sessionId, it, false) }
                    service.selectContext(sessionId, selections.first(), false)
                    assertFailsWith<IllegalArgumentException> { service.selectContext(sessionId, KitContextSelection(KitContextSourceKind.SPEC, UUID.random()), false) }
                    assertEquals(16, service.context(auth, sessionId)?.selectionCount)
                    val otherState = (chat.state as JsonObject) - "harnessSources"
                    service.clearContext(sessionId)
                    assertEquals(JsonObject(otherState), chat.state)
                    assertEquals(0, service.context(auth, sessionId)?.selectionCount)
                }
            }
            checkDomainContext()
            metricsUnavailable = true
            send("metrics-outage"); drive()
            metricsUnavailable = false
            assertEquals("metrics-outage", Files.readString(root.resolve("harness/$nativeState")))
            assertTrue(measurement("startup.success.count") > 0)
            assertTrue(measurement("startup.success.milliseconds") > 0)
            assertTrue(measurement("turn.success.count") > 0)
            assertTrue(measurement("turn.success.milliseconds") > 0)
            assertTrue(measurement("credential_revoke.failure.count") > 0)
            assertTrue(measurement("admission.denied.count") > 0)
            assertTrue(measurement("restore.success.bytes") > 0)
            assertTrue(measurement("restore.success.age_milliseconds") > 0)
            assertTrue(measurement("approval.success.count") > 0)
            assertTrue(measurement("archive.failure.count") > 0)
            assertTrue(measurement("cleanup.success.milliseconds") > 0)
            withConnectionManager { assertNull(repository.get(sessionId)?.archiveCreated, "A fresh context has no checkpoint creation time") }
            withConnectionManager { service.retire(sessionId) }; drive()
            send("cleanup-final"); drive()
            provides<bosca.cache.CacheManager> { mockk(relaxed = true) }
            provides<bosca.cache.RequestCacheSerializer> { mockk(relaxed = true) }
            val active = withConnectionManager { repository.activeCount() }
            KitRuntimeRecovery(repository, telemetry).use { it.sweep() }
            assertEquals(1L, measurement("snapshot.samples"))
            assertEquals(active, measurement("snapshot.active"))
            assertTrue(measurement("recovery.started.count") > 0)
            delayRemoval = true
            withConnectionManager { service.delete(sessionId) }
            assertFailsWith<bosca.sharedqueue.jobs.DelayException> { drive() }
            withConnectionManager { assertFalse(repository.hasCapacity(principalId, 1, 1), "Deleting a live workload retains its admission slot") }
            delayRemoval = false
            failArchiveDelete = true
            assertContains(assertFailsWith<IllegalStateException> { drive() }.message.orEmpty(), "archive deletion failure")
            withConnectionManager {
                assertNotNull(repository.get(sessionId)?.deletedAt)
                assertNull(repository.get(sessionId)?.dispatchId)
                assertNull(repository.get(sessionId)?.credentialId)
                assertNull(repository.get(sessionId)?.secrets)
                assertTrue(repository.hasCapacity(principalId, 1, 1), "Archive deletion retries do not reserve removed runtime capacity")
                assertTrue(repository.pending(100, 0, 0).any { it.sessionId == sessionId }, "Failed purge must remain recoverable")
            }
            assertTrue(archives.isNotEmpty())
            failArchiveDelete = false
            drive()
            withConnectionManager { assertNull(repository.get(sessionId)) }
            assertTrue(archives.isEmpty())
            coVerify(exactly = 1) { tokens.revokeToken(1, principalId) }
            assertTrue(stored.isEmpty(), "Every generation credential is deleted once its workload is retired")
            coVerify(exactly = messages.size - 1) { sessions.recordUserMessage(sessionId, any()) }
        }
        try {
            exercise()
        } finally {
            replacements.forEach { it.destroy(); if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly().waitFor() }
            process?.destroy()
            process?.let { if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly().waitFor() }
            unmockkStatic("bosca.ai.kit.harness.runtime.KitRuntimeJobExecutorExecutorKt")
            ProviderRegistry.clear()
            pool.close(); postgres.stop()
            root.toFile().deleteRecursively()
        }
    }
}
