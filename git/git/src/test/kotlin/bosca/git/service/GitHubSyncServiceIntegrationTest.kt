@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.db.ConnectionConfig
import bosca.db.afterCommit
import bosca.events.catalog.CoreGitEventCatalogRegistrarProvider
import bosca.events.catalog.EventCatalogRegistrar
import bosca.git.model.GitHubDelivery
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.repository.*
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.service.PipelineRunServiceImpl
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.every
import io.mockk.spyk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.polymorphic
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.git.graphql.*
import bosca.graphql.*
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.observability.ErrorCapture
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.GroupEvaluator
import bosca.security.service.AuthenticationProviders
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUIDSerializer
import bosca.serialization.OffsetDateTimeSerializer
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import bosca.git.model.GitHubDeliveryConflictException
import bosca.git.model.GitHubRepositoryPairInput
import bosca.git.model.GitHubWebhookRejectedException
import bosca.git.model.GitHubWebhookInputException
import bosca.git.model.GitHubWebhookUnavailableException
import bosca.git.model.Repository
import bosca.git.repository.GitHubSyncRepositoryImpl
import bosca.pipelines.service.PipelineSecretService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

/** Production intake, migrations and generated JDBC mapping run against PostgreSQL. */
class GitHubSyncServiceIntegrationTest {
    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("github_intake_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 4,
        ), key = "github-intake-test"))
    }

    private val repositoryId = UUID.random()
    private val principalId = UUID.random()
    private val servicePrincipalId = UUID.random()
    private val repository = GitHubSyncRepositoryImpl()
    private val hosted = Repository(id = repositoryId, slug = "source", name = "Source", ownerId = UUID.random())
    private val hostedService = mockk<RepositoryService>()
    private val secrets = mockk<PipelineSecretService>()
    private val security = mockk<SecurityService>()
    private val pipelines = mockk<bosca.pipelines.service.PipelineService>()
    private val service = GitHubSyncServiceImpl(repository, hostedService, secrets, security)
    private val input = GitHubRepositoryPairInput(repositoryId, 123, "bosca-io", "source", "github-webhook", "github-token", true)
    private val secret = "test-secret-✓"
    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer())
        contextual(OffsetDateTimeSerializer())
    } }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    development: false\n".byteInputStream()))
        coEvery { hostedService.findById(repositoryId) } returns hosted
        coEvery { secrets.resolve("github-webhook") } returns secret
        coEvery { secrets.resolve("github-token") } returns "token"
        coEvery { security.getPrincipalById(principalId) } returns Principal(id = principalId)
        withDb {
            connection().useStatement("drop schema if exists git cascade; create schema git; create table git.repositories(id uuid primary key)") { it.execute() }
            val migration = javaClass.getResource("/db/migrations/V44__github_intake.sql")?.readText() ?: error("Missing migration")
            connection().useStatement(migration) { it.execute() }
            connection().useStatement("insert into git.repositories(id) values ('$repositoryId')") { it.execute() }
        }
    }

    @AfterTest fun cleanup() = ProviderRegistry.clear()

    private fun payload(userId: Long = 7, type: String = "User", remoteId: Long = 123, extra: String = "") =
        """{"repository":{"id":$remoteId,"full_name":"bosca-io/source"},"sender":{"id":$userId,"type":"$type"},"message":"✓ café"$extra}"""

    private suspend fun receive(body: String = payload(), event: String = "push", id: String = UUID.random().toString()) =
        service.onDelivery(repositoryId, id, event, WebhookService.computeSignature(secret, body), body.toByteArray())

    @Test fun `pair stores only secret references and updates with optimistic locking`() = withDb {
        val pair = service.savePair(input)
        assertEquals(0, pair.version)
        assertEquals(pair, service.findPair(repositoryId))
        val renamed = service.savePair(input.copy(owner = "new-owner", name = "renamed", enabled = false))
        assertEquals(1, renamed.version)
        assertEquals(pair.created, renamed.created)
        assertEquals("new-owner", renamed.owner)
        assertEquals("renamed", renamed.name)
        assertEquals("github-token", renamed.tokenSecretName)
        assertEquals("github-webhook", renamed.webhookSecretName)
        assertFalse(renamed.enabled)
        assertFailsWith<IllegalStateException> { service.savePair(input) }
        assertFailsWith<IllegalArgumentException> { service.savePair(input.copy(githubRepositoryId = 999, version = 1)) }
        assertFailsWith<GitHubWebhookRejectedException> { receive() }
        assertTrue(service.findDeliveries(repositoryId, 0, 25).isEmpty())
    }

    @Test fun `signed repeated delivery retains one identity original attribution and complete JSON`() = withDb {
        service.savePair(input)
        val user = service.mapUser(7, principalId)
        assertEquals(principalId, user.principalId)
        val id = UUID.random().toString()
        val accepted = receive(id = id)
        assertEquals(principalId, accepted.principalId)
        assertEquals(7, accepted.githubUserId)
        assertFalse(accepted.ignored)
        assertEquals("✓ café", accepted.payload.jsonObject["message"]?.jsonPrimitive?.content)
        service.unmapUser(7)
        val duplicate = receive(id = id.uppercase())
        assertEquals(accepted, duplicate)
        assertEquals(listOf(accepted), service.findDeliveries(repositoryId, 0, 25))
        assertTrue(service.findUsers(0, 25).isEmpty())
    }

    @Test fun `delivery ID rejects changed bytes event or repository`() = withDb {
        service.savePair(input)
        val id = UUID.random().toString()
        receive(id = id)
        assertFailsWith<GitHubDeliveryConflictException> { receive(body = payload(userId = 8), id = id) }
        assertFailsWith<GitHubDeliveryConflictException> { receive(event = "pull_request", id = id) }
        assertEquals(1, service.findDeliveries(repositoryId, 0, 25).size)
    }

    @Test fun `rollback releases a delivery occurrence for retry`() = withDb {
        service.savePair(input)
        val id = UUID.random().toString()
        assertFailsWith<IllegalStateException> { transaction { receive(id = id); error("rollback") } }
        assertTrue(service.findDeliveries(repositoryId, 0, 25).isEmpty())
        assertEquals(id, receive(id = id).deliveryId)
    }

    @Test fun `unknown missing or bot users never inherit an integration principal`() = withDb {
        service.savePair(input)
        service.mapUser(7, principalId)
        assertNull(receive(payload(userId = 8)).principalId)
        assertNull(receive(payload(type = "Bot")).principalId)
        assertNull(receive("""{"repository":{"id":123}}""").principalId)
        assertNull(receive("""{"repository":{"id":123},"sender":{"id":7}}""").principalId)
        assertNull(receive(payload(userId = 0)).principalId)
    }

    @Test fun `fork pull requests are ignored while paired branches are eligible`() = withDb {
        service.savePair(input)
        val prefix = ",\"pull_request\":{\"head\":{\"repo\":{\"id\":"
        assertTrue(receive(payload(extra = prefix + "999}}}"), "pull_request").ignored)
        assertFalse(receive(payload(extra = prefix + "123}}}"), "pull_request").ignored)
        assertTrue(receive(payload(extra = ",\"pull_request\":{\"head\":{\"repo\":null}}"), "pull_request").ignored)
        assertTrue(receive(event = "ping").ignored)
    }

    @Test fun `invalid authenticity or repository identity never persists intake`() = withDb {
        service.savePair(input)
        for (signature in listOf(null, "sha1=bad", "sha256=" + "0".repeat(64))) {
            assertFailsWith<GitHubWebhookRejectedException> {
                service.onDelivery(repositoryId, UUID.random().toString(), "push", signature, "not JSON".toByteArray())
            }
        }
        assertFailsWith<GitHubWebhookRejectedException> { receive(payload(remoteId = 999)) }
        assertFailsWith<IllegalArgumentException> { service.onDelivery(repositoryId, "bad", "push", null, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { service.onDelivery(repositoryId, UUID.random().toString(), "bad event", null, byteArrayOf()) }
        assertFailsWith<GitHubWebhookInputException> { receive("invalid JSON") }
        val bytes = byteArrayOf(0xc3.toByte(), 0x28)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        }
        val signature = "sha256=" + mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
        assertFailsWith<IllegalArgumentException> { service.onDelivery(repositoryId, UUID.random().toString(), "push", signature, bytes) }
        assertTrue(service.findDeliveries(repositoryId, 0, 25).isEmpty())
    }

    @Test fun `administrators can disable intake after a repository is archived`() = withDb {
        service.savePair(input)
        coEvery { hostedService.findById(repositoryId) } returns hosted.copy(archived = true)
        assertFalse(service.savePair(input.copy(enabled = false)).enabled)
    }

    @Test fun `a delivery occurrence cannot move between paired repositories`() = withDb {
        service.savePair(input)
        val id = UUID.random().toString()
        receive(id = id)
        val otherId = UUID.random()
        connection().useStatement("insert into git.repositories(id) values ('$otherId')") { it.execute() }
        coEvery { hostedService.findById(otherId) } returns hosted.copy(id = otherId)
        service.savePair(input.copy(repositoryId = otherId, githubRepositoryId = 456))
        val body = payload(remoteId = 456)
        assertFailsWith<GitHubDeliveryConflictException> {
            service.onDelivery(otherId, id, "push", WebhookService.computeSignature(secret, body), body.toByteArray())
        }
        assertTrue(service.findDeliveries(otherId, 0, 25).isEmpty())
        assertEquals(1, service.findDeliveries(repositoryId, 0, 25).size)
    }

    @Test fun `unavailable repository or configuration fails before intake`() = withDb {
        assertFailsWith<GitHubWebhookRejectedException> { receive() }
        service.savePair(input)
        for (record in listOf(null, hosted.copy(deleted = true), hosted.copy(archived = true))) {
            coEvery { hostedService.findById(repositoryId) } returns record
            assertFailsWith<GitHubWebhookRejectedException> { receive() }
        }
        coEvery { hostedService.findById(repositoryId) } returns hosted
        coEvery { secrets.resolve("github-webhook") } returns null
        assertFailsWith<GitHubWebhookUnavailableException> { receive() }
    }

    @Test fun `invalid pairing user mapping and pagination fail loudly`() = withDb {
        for (invalid in listOf(input.copy(githubRepositoryId = 0), input.copy(version = -1), input.copy(owner = "a/b"),
            input.copy(name = ""), input.copy(webhookSecretName = ""), input.copy(tokenSecretName = ""), input.copy(version = 1))) {
            assertFailsWith<IllegalArgumentException> { service.savePair(invalid) }
        }
        coEvery { secrets.resolve("github-webhook") } returns ""
        assertFailsWith<IllegalArgumentException> { service.savePair(input) }
        coEvery { secrets.resolve("github-webhook") } returns secret
        coEvery { secrets.resolve("github-token") } returns null
        assertFailsWith<IllegalArgumentException> { service.savePair(input) }
        coEvery { hostedService.findById(repositoryId) } returns null
        assertFailsWith<NoSuchElementException> { service.savePair(input) }
        coEvery { hostedService.findById(repositoryId) } returns hosted.copy(archived = true)
        assertFailsWith<IllegalArgumentException> { service.savePair(input) }
        assertFailsWith<IllegalArgumentException> { service.mapUser(0, principalId) }
        assertFailsWith<IllegalArgumentException> { service.unmapUser(0) }
        coEvery { security.getPrincipalById(principalId) } returns null
        assertFailsWith<NoSuchElementException> { service.mapUser(7, principalId) }
        coEvery { security.getPrincipalById(principalId) } returns Principal(id = principalId, deletedAt = java.time.OffsetDateTime.now())
        assertFailsWith<IllegalArgumentException> { service.mapUser(7, principalId) }
        assertFailsWith<IllegalArgumentException> { service.findUsers(-1, 25) }
        assertFailsWith<IllegalArgumentException> { service.findDeliveries(repositoryId, 0, 0) }
        assertFailsWith<IllegalArgumentException> { service.findDeliveries(repositoryId, 0, 101) }
    }

    @Test fun `GitHub official test vector validates exact raw bytes`() {
        val signature = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
        assertTrue(GitHubSyncServiceImpl.verifySignature("It's a Secret to Everybody", signature, "Hello, World!".toByteArray()))
        assertFalse(GitHubSyncServiceImpl.verifySignature("It's a Secret to Everybody", signature, "Hello, World!\n".toByteArray()))
    }

    @Test fun `generated service provider resolves the production intake dependencies`() = withDb {
        provides<bosca.git.repository.GitHubSyncRepository>(singleton = true) { repository }
        provides<RepositoryService>(singleton = true) { hostedService }
        provides<PipelineSecretService>(singleton = true) { secrets }
        provides<SecurityService>(singleton = true) { security }
        val wired = GitHubSyncServiceImplProvider().get()
        val pair = wired.savePair(input)
        assertEquals(pair, wired.findPair(repositoryId))
        val body = payload()
        val accepted = wired.onDelivery(repositoryId, UUID.random().toString(), "push", WebhookService.computeSignature(secret, body), body.toByteArray())
        assertEquals(listOf(accepted), wired.findDeliveries(repositoryId, 0, 25))
    }

    @Test fun `generated GraphQL resolvers expose persisted configuration with administrator authorization`() = withDb {
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<ErrorCapture> { ErrorCapture.Noop }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("GitHubSyncGraphQLTest") }
        val registered = bosca.git.configuration.GitSchemaRegistrar().load()
        assertTrue("type GitHubRepositoryPair" in registered)
        val types = javaClass.getResource("/graphql/github.graphqls")?.readText() ?: error("Missing GitHub SDL")
        // Load the complete GitHub SDL, including its root namespace extensions.
        SchemaRegistry.initialize(object : SchemaRegistrar {
            override suspend fun load() = """
                scalar UUID
                scalar DateTime
                scalar JSON
                scalar Long
                scalar Upload
                type Query { _empty: Boolean }
                type Mutation { _empty: Boolean }
                $types
            """.trimIndent()
        })
        val groups = GroupEvaluator(security)
        val root = object : SchemaRoot {
            override val query = object : QueryRoot {}
            override val mutation = object : MutationRoot {}
            override val subscription = object : SubscriptionRoot {}
        }
        val graphQL = object : GraphQLService(root, false) {
            override val dispatchersRegistrar = object : DispatchersRegistrar {
                override suspend fun dispatchers(): Map<String, Dispatcher> = listOf(
                    GitHubSyncQueryDispatcher(GitHubSyncQuery(service, groups)),
                    GitHubSyncMutationDispatcher(GitHubSyncMutation(service, groups)),
                    GitHubRepositoryPairControllerDispatcher(GitHubRepositoryPairController()),
                    GitHubUserControllerDispatcher(GitHubUserController()),
                    GitHubDeliveryControllerDispatcher(GitHubDeliveryController()),
                ).associateBy { it.type.typeName }
            }
            override suspend fun initialize(builder: RuntimeWiringBuilder) {
                builder.type("Query") { field("github") { GitHub } }
                builder.type("Mutation") { field("github") { GitHubMutation } }
            }
        }
        val admin = ImpersonatedAuthenticationContext(Principal(id = principalId), listOf(
            Group(name = "administrators", description = "", type = GroupType.SYSTEM),
        ))
        val mutation = """mutation { github { savePair(input: {
            repositoryId: "$repositoryId", githubRepositoryId: 123, owner: "bosca-io", name: "source",
            webhookSecretName: "github-webhook", tokenSecretName: "github-token", enabled: true
        }) { repositoryId githubRepositoryId owner name webhookSecretName tokenSecretName enabled version created modified } } }"""
        val saved = graphQL.execute(admin, GraphQLRequest(query = mutation)).jsonObject
        assertFalse("errors" in saved, saved.toString())
        assertEquals("0", saved.getValue("data").jsonObject.getValue("github").jsonObject.getValue("savePair").jsonObject.getValue("version").jsonPrimitive.content)
        val mapped = graphQL.execute(admin, GraphQLRequest(query = """mutation { github {
            mapUser(githubUserId: 7, principalId: "$principalId") { githubUserId principalId created modified }
        } }""")).jsonObject
        assertFalse("errors" in mapped, mapped.toString())
        val user = service.findUsers(0, 25).single()
        val accepted = receive()
        val query = """{ github {
            pair(repositoryId: "$repositoryId") { repositoryId githubRepositoryId owner name webhookSecretName tokenSecretName enabled version created modified }
            users { githubUserId principalId created modified }
            deliveries(repositoryId: "$repositoryId") { deliveryId repositoryId event payload githubUserId principalId ignored created }
        } }"""
        val result = graphQL.execute(admin, GraphQLRequest(query = query)).jsonObject
        assertFalse("errors" in result, result.toString())
        val data = result.getValue("data").jsonObject.getValue("github").jsonObject
        assertEquals(principalId.toString(), data.getValue("users").jsonArray.single().jsonObject.getValue("principalId").jsonPrimitive.content)
        val delivery = data.getValue("deliveries").jsonArray.single().jsonObject
        assertEquals(accepted.payload, delivery.getValue("payload"))
        assertEquals("7", delivery.getValue("githubUserId").jsonPrimitive.content)
        assertEquals(principalId.toString(), delivery.getValue("principalId").jsonPrimitive.content)
        val ordinary = ImpersonatedAuthenticationContext(Principal(id = principalId), emptyList())
        assertTrue("errors" in graphQL.execute(ordinary, GraphQLRequest(query = query)).jsonObject)
        val unmapped = graphQL.execute(admin, GraphQLRequest(query = """mutation { github {
            unmapUser(githubUserId: 7)
        } }""")).jsonObject
        assertFalse("errors" in unmapped, unmapped.toString())
        assertEquals("true", unmapped.getValue("data").jsonObject.getValue("github").jsonObject.getValue("unmapUser").jsonPrimitive.content)
        assertTrue(service.findUsers(0, 25).isEmpty())
        // Native-safe explicit serializers retain IDs, body and contextual timestamps across job boundaries.
        val pair = assertNotNull(service.findPair(repositoryId))
        assertEquals(pair, json.decodeFromString(bosca.git.model.GitHubRepositoryPair.serializer(), json.encodeToString(bosca.git.model.GitHubRepositoryPair.serializer(), pair)))
        assertEquals(user, json.decodeFromString(bosca.git.model.GitHubUser.serializer(), json.encodeToString(bosca.git.model.GitHubUser.serializer(), user)))
        assertEquals(accepted, json.decodeFromString(bosca.git.model.GitHubDelivery.serializer(), json.encodeToString(bosca.git.model.GitHubDelivery.serializer(), accepted)))
    }

    @Serializable
    @SerialName("githubDeliveryProbe")
    class DeliveryProbe(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
            val delivery = context.json.decodeFromJsonElement(
                GitHubDelivery.serializer(), requireNotNull(inputs.first).encode(context.json),
            )
            observed += Observation(
                delivery, context.authentication.principal()?.id, context.runId, context.runJobId,
            )
            return inputs.first
        }

        companion object {
            val observed = mutableListOf<Observation>()
        }
    }

    data class Observation(
        val delivery: GitHubDelivery,
        val principalId: UUID?,
        val runId: UUID?,
        val jobId: UUID?,
    )

    private class EventFixture(
        val pipelines: MutableList<Pipeline>,
        val pipelineService: bosca.pipelines.service.PipelineService,
        val runService: bosca.pipelines.service.PipelineRunService,
        val runRepository: PipelineRunRepositoryImpl,
        val json: Json,
    ) {
        val jobs = linkedMapOf<UUID, Job>()
        private val completedJobs = mutableSetOf<UUID>()
        val events = mutableListOf<bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent>()
        lateinit var queue: JobQueue
        var failPublication = false

        suspend fun dispatchAll() {
            for (queued in jobs.values.filter { job -> job.getId() !in completedJobs && events.any {
                it.jobId == job.getId() && it.executor == bosca.pipelines.trigger.PipelineDispatchJobExecutor::class.qualifiedName
            } }) {
                val job = spyk(queued)
                every { job.isLocked } returns true
                withContext(queue.asCoroutineContext(job)) {
                    bosca.pipelines.trigger.PipelineDispatchJobExecutor(pipelineService).execute()
                }
                completedJobs += queued.getId()
            }
        }

        suspend fun driveAll() {
            for (queued in jobs.values.filter { job -> job.getId() !in completedJobs && events.any {
                it.jobId == job.getId() && it.executor == bosca.pipelines.trigger.PipelineRunJobExecutor::class.qualifiedName
            } }) {
                val job = spyk(queued)
                every { job.isLocked } returns true
                withContext(queue.asCoroutineContext(job)) {
                    bosca.pipelines.trigger.PipelineRunJobExecutor(pipelineService, runService, json).execute()
                }
                completedJobs += queued.getId()
            }
        }

        suspend fun runs(delivery: GitHubDelivery) = DeliveryProbe.observed
            .filter { it.delivery.deliveryId == delivery.deliveryId }
            .map { requireNotNull(runRepository.getById(requireNotNull(it.runId))) }
    }

    /** Exercises generated event dispatch, both production pipeline jobs and JDBC run mapping. */
    private suspend fun installTriggeredPipeline(): EventFixture {
        connection().useStatement("""
            drop schema if exists pipelines cascade;
            create table if not exists groups (id uuid primary key);
            do $$ begin
                if not exists (select 1 from pg_type where typname = 'permission_action') then
                    create type permission_action as enum ('view', 'edit', 'manage', 'delete', 'execute');
                end if;
            end $$;
        """.trimIndent()) { it.execute() }
        for (resource in PipelinesMigration().resources) {
            val sql = PipelinesMigration::class.java.getResource("/db/migrations/$resource")?.readText()
                ?: error("Missing pipeline migration: $resource")
            connection().useStatement(sql) { it.execute() }
        }
        val graphJson = Json(json) {
            serializersModule = SerializersModule {
                include(json.serializersModule)
                polymorphic(PipelineNode::class) {
                    subclass(InputNode::class, InputNode.serializer())
                    subclass(OutputNode::class, OutputNode.serializer())
                    subclass(DeliveryProbe::class, DeliveryProbe.serializer())
                }
            }
        }
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { graphJson }
        provides<EventCatalogRegistrar>(name = "CoreGit", singleton = true) { CoreGitEventCatalogRegistrarProvider() }
        coEvery { security.getPrincipalGroups(any<UUID>()) } returns emptyList()
        coEvery { security.getPrincipalByIdentifier(any()) } returns Principal(id = servicePrincipalId)
        coEvery { pipelines.graphAsJsonElement(any()) } answers {
            val p = firstArg<Pipeline>()
            graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(p.nodes, p.edges))
        }
        coEvery { pipelines.decodeGraph(any()) } answers {
            graphJson.decodeFromJsonElement(PipelineGraph.serializer(), firstArg())
        }
        val eventName = GitHubDelivery.serializer().descriptor.serialName
        val graph = Pipeline(
            id = UUID.NIL,
            name = "Inbound GitHub", key = "configured-by-administrator", triggered = true, acceptedInputType = eventName,
            nodes = listOf(InputNode("input", acceptedType = eventName), DeliveryProbe("probe"), OutputNode("output")),
            edges = listOf(
                PipelineEdge(id = "in", source = "input", target = "probe"),
                PipelineEdge(id = "out", source = "probe", target = "output"),
            ),
        )
        val record = PipelineRepositoryImpl().add(PipelineRecord(
            name = graph.name, key = graph.key, acceptedInputType = eventName, triggered = true,
            graph = pipelines.graphAsJsonElement(graph),
        ))
        val runRepository = PipelineRunRepositoryImpl()
        val runService = PipelineRunServiceImpl(
            runRepository = runRepository,
            runLogRepository = PipelineRunLogRepositoryImpl(),
            resultStore = mockk<PipelineRunResultStore>(relaxed = true),
            nodeExecutionRepository = NodeExecutionRepositoryImpl(),
            iterationRepository = PipelineRunIterationRepositoryImpl(),
            rollbackRepository = RollbackRepositoryImpl(),
            pipelineService = pipelines,
            executor = PipelineExecutorImpl(),
            securityService = security,
            config = PipelinesRuntimeConfiguration(),
            pubSub = mockk(relaxed = true),
        )
        val fixture = EventFixture(mutableListOf(graph.copy(id = record.id)), pipelines, runService, runRepository, graphJson)
        coEvery { pipelines.triggeredEventTypes() } answers { if (fixture.pipelines.isEmpty()) emptySet() else setOf(eventName) }
        coEvery { pipelines.triggeredFor(eventName) } answers { fixture.pipelines.toList() }
        coEvery { pipelines.get(any()) } answers { fixture.pipelines.find { it.id == firstArg<UUID>() } }
        provides<bosca.pipelines.PipelineEventDispatcher>(singleton = true) {
            bosca.pipelines.trigger.PipelineEventDispatcherImpl(pipelines, graphJson)
        }
        val delegate = mockk<JobQueue>(relaxed = true)
        coEvery { delegate.enqueue(any()) } coAnswers {
            val job = firstArg<Job>()
            if (job.getId() == UUID.NIL) job.setPersistentId(UUID.random())
            afterCommit {
                check(!fixture.failPublication) { "Queue unavailable" }
                fixture.jobs[job.getId()] = job
            }
            job.getId()
        }
        val channel = mockk<bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel>()
        coEvery { channel.emit(any()) } answers { fixture.events += firstArg<bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent>() }
        fixture.queue = bosca.sharedqueue.jobs.enqueue.EventEmittingJobQueue(delegate, "pipelines", channel)
        provides<JobQueue>(name = bosca.pipelines.configuration.PipelinesJobQueueNames.jobQueue, singleton = true) { fixture.queue }
        DeliveryProbe.observed.clear()
        return fixture
    }

    @Test fun `verified delivery reaches existing pipeline jobs with its original user data`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        service.mapUser(7, principalId)
        val delivery = receive()
        assertTrue(fixture.runs(delivery).isEmpty())
        fixture.dispatchAll()
        fixture.driveAll()
        val run = fixture.runs(delivery).single()
        assertEquals(PipelineRunStatus.OK, run.status)
        assertNull(run.principalId)
        assertEquals(delivery, DeliveryProbe.observed.single().delivery)
        assertEquals(servicePrincipalId, DeliveryProbe.observed.single().principalId)
        assertEquals(run.runJobId, DeliveryProbe.observed.single().jobId)
        assertTrue(fixture.events.any { it.executor == bosca.pipelines.trigger.PipelineDispatchJobExecutor::class.qualifiedName })
        assertTrue(fixture.events.any { it.executor == bosca.pipelines.trigger.PipelineRunJobExecutor::class.qualifiedName })

        service.unmapUser(7)
        assertEquals(delivery, receive(id = delivery.deliveryId.uppercase()))
        fixture.dispatchAll()
        fixture.driveAll()
        assertEquals(2, DeliveryProbe.observed.size)
        assertTrue(DeliveryProbe.observed.all { it.delivery == delivery && it.principalId == servicePrincipalId })
    }

    @Test fun `delivery IDs use existing event scope deduplication`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        val id = UUID.random().toString()
        val delivery = bosca.events.withEventManager {
            bosca.events.deferredEvents {
                receive(id = id)
                receive(id = id)
                assertTrue(fixture.jobs.isEmpty())
                assertNotNull(repository.findDelivery(id))
            }
        }
        assertEquals(1, fixture.jobs.size)
        fixture.dispatchAll()
        fixture.driveAll()
        assertEquals(delivery, DeliveryProbe.observed.single().delivery)
    }

    @Test fun `one event starts every configured matching pipeline`() = withDb {
        val fixture = installTriggeredPipeline()
        val original = fixture.pipelines.single()
        val second = PipelineRepositoryImpl().add(PipelineRecord(
            name = "Second inbound pipeline", key = "another-key", acceptedInputType = original.acceptedInputType,
            graph = pipelines.graphAsJsonElement(original), triggered = true,
        ))
        fixture.pipelines += original.copy(id = second.id, key = second.key)
        service.savePair(input)
        val delivery = receive()
        fixture.dispatchAll()
        fixture.driveAll()
        val runs = fixture.runs(delivery)
        assertEquals(fixture.pipelines.map { it.id }.toSet(), runs.map { it.pipelineId }.toSet())
        assertTrue(runs.all { it.status == PipelineRunStatus.OK })
        assertEquals(2, DeliveryProbe.observed.size)
    }

    @Test fun `failed event publication can be retried under the original delivery ID`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        val id = UUID.random().toString()
        fixture.failPublication = true
        assertFailsWith<IllegalStateException> { receive(id = id) }
        val delivery = assertNotNull(repository.findDelivery(id))
        assertTrue(fixture.runs(delivery).isEmpty())
        fixture.failPublication = false
        assertEquals(delivery, receive(id = id))
        fixture.dispatchAll()
        fixture.driveAll()
        assertEquals(PipelineRunStatus.OK, fixture.runs(delivery).single().status)
        assertEquals(delivery, DeliveryProbe.observed.single().delivery)
    }

    @Test fun `rolled back intake publishes no event job or pipeline run`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        val id = UUID.random().toString()
        assertFailsWith<IllegalStateException> {
            transaction {
                receive(id = id)
                assertTrue(fixture.jobs.isEmpty())
                error("rollback")
            }
        }
        assertNull(repository.findDelivery(id))
        assertTrue(fixture.jobs.isEmpty())
        val retried = receive(id = id)
        fixture.dispatchAll()
        fixture.driveAll()
        assertEquals(PipelineRunStatus.OK, fixture.runs(retried).single().status)
    }

    @Test fun `ignored and fork deliveries dispatch no pipeline events`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        assertTrue(receive(event = "ping").ignored)
        assertTrue(receive(payload(extra = ",\"pull_request\":{\"head\":{\"repo\":{\"id\":999}}}"), "pull_request").ignored)
        assertTrue(fixture.jobs.isEmpty())
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `unmapped and bot senders remain unattributed in the pipeline input`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        service.mapUser(7, principalId)
        for (body in listOf(payload(userId = 8), payload(type = "Bot"))) {
            val delivery = receive(body)
            fixture.dispatchAll()
            fixture.driveAll()
            assertEquals(PipelineRunStatus.OK, fixture.runs(delivery).single().status)
            val observed = DeliveryProbe.observed.last()
            assertEquals(delivery, observed.delivery)
            assertNull(observed.delivery.principalId)
            assertEquals(servicePrincipalId, observed.principalId)
        }
    }

    @Test fun `deliveries use the existing triggered pipeline gate`() = withDb {
        val fixture = installTriggeredPipeline()
        fixture.pipelines.clear()
        service.savePair(input)
        val delivery = receive()
        assertFalse(delivery.ignored)
        assertTrue(fixture.jobs.isEmpty())
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.runs(delivery).isEmpty())
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val manager = pool.connection()
        try { withContext(manager.asCoroutineContext()) { block() } }
        finally { withContext(NonCancellable) { manager.release() } }
    }
}
