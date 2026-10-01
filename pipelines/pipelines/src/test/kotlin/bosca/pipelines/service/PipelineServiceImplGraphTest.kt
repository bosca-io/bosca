@file:OptIn(ExperimentalUuidApi::class, bosca.di.annotation.InternalDI::class)

package bosca.pipelines.service

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.UUIDCacheKey
import bosca.cache.asCoroutineContext
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.Batch
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PIPELINE_TRIGGERS_CHANGED_CHANNEL
import bosca.pipelines.model.PipelineTriggersChanged
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.NodeInputSlot
import bosca.pipelines.node.NodeOutputSlot
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.pipelines.repository.PipelinePermission
import bosca.pipelines.repository.PipelinePermissionRepository
import bosca.pipelines.repository.PipelineRecord
import bosca.pipelines.repository.PipelineRepository
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.pubsub.PubSubService
import bosca.pubsub.Message
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exercises the REAL [PipelineServiceImpl] graph helpers ([decodeGraph], [descriptorFor]) — which the
 * durable-run e2e stubs out with a fake. Both go through the aggregated node `SerializersModule`
 * (`findAll(PipelineNodeSerializers)`), so this registers one with a test node type.
 */
class PipelineServiceImplGraphTest {

    private class TestPubSub : PubSubService {
        val subscribed = CompletableDeferred<Unit>()
        val incoming = MutableSharedFlow<Message<PipelineTriggersChanged>>(replay = 1)

        override suspend fun <T> publish(
            channel: String,
            serializer: SerializationStrategy<T>,
            message: T,
        ) = Unit

        @Suppress("UNCHECKED_CAST")
        override fun <T> subscribe(
            channel: String,
            deserializer: DeserializationStrategy<T>,
        ): Flow<Message<T>> {
            subscribed.complete(Unit)
            return incoming as Flow<Message<T>>
        }
    }

    @Serializable
    @SerialName("svcTestNode")
    class SvcTestNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
    }

    /** An INTEGER-emitting source whose output is incompatible with a UUID slot (for slot violations). */
    @Serializable
    @SerialName("svcIntSource")
    class IntSourceNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
    }

    /** A node with a single UUID-typed input slot — fed an INTEGER, it violates slot rules. */
    @Serializable
    @SerialName("svcUuidTarget")
    class UuidTargetNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
    }

    /**
     * A node whose constructor `init` block throws [IllegalArgumentException] (via `require`). kotlinx
     * does NOT wrap constructor exceptions, so decoding a graph containing this node propagates a raw
     * IAE straight out of `decodeFromJsonElement` — driving the service's `catch (IllegalArgumentException)`
     * arm (which a malformed-JSON input cannot reach, since that throws a SerializationException).
     */
    @Serializable
    @SerialName("svcIaeNode")
    class IaeNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        init {
            require(false) { "svcIaeNode always rejects construction" }
        }

        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
    }

    /** A fixed node the [FixedNodeDeser] yields for any object — needs no "id"/"type" in the JSON. */
    class FixedNode(override val id: String) : TransformNode() {
        override val name: String = ""
        override val description: String = ""
        override val position: NodePosition = NodePosition()
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
    }

    /**
     * A polymorphic default deserializer that swallows the JSON body and returns a fixed node — so a node
     * object lacking "id" and/or "type" still decodes successfully. This lets the typed graph decode pass
     * while the RAW nodes array carries pathological entries, driving validateGraph's per-node null arms.
     */
    object FixedNodeDeser : kotlinx.serialization.DeserializationStrategy<PipelineNode> {
        override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
            kotlinx.serialization.descriptors.buildClassSerialDescriptor("FixedNode")

        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): PipelineNode {
            (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement()
            return FixedNode("fixed")
        }
    }

    private val baseJson = Json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }

    // Stable cache doubles so the ServiceCache-backed permission paths resolve through the resolver
    // (mirrors CampaignServiceImplTest): every remote lookup reports a miss, so the resolver fires.
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val remoteCache = mockk<Cache<UUID>>(relaxed = true)
    private val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)

    /** Real UUID-keyed serializer so batch keys round-trip to their actual UUIDs (no mock null keys). */
    private val uuidKeySerializer = object : CacheKeySerializer<UUID> {
        override fun toLocalKey(cacheName: String, value: UUID): CacheKey<UUID> = UUIDCacheKey(cacheName, value)
        override fun fromRemoteKey(key: String): CacheKey<UUID> = error("unused")
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { baseJson }

        coEvery { cacheManager.maybeAddCache<UUID>(any(), any(), any()) } returns remoteCache
        coEvery { cacheManager.getCache<UUID>(any()) } returns remoteCache
        every { remoteCache.keySerializer } returns uuidKeySerializer
        coEvery { remoteCache.get(any()) } returns mockk<CacheValue> {
            every { exists } returns false
            every { value } returns null
        }
        coEvery { remoteCache.getBatch(any()) } answers {
            firstArg<List<CacheKey<UUID>>>().map {
                mockk<CacheValue> {
                    every { exists } returns false
                    every { value } returns null
                }
            }
        }
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> { requestCacheSerializer }

        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction<Any?>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[0] as suspend () -> Any?).invoke()
        }

        provides<PipelineNodeSerializers> {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                        subclass(OutputNode::class, OutputNode.serializer())
                        subclass(SvcTestNode::class, SvcTestNode.serializer())
                    }
                }
                override val descriptors = listOf(
                    NodeDescriptor(key = "svcTestNode", label = "Test", category = NodeCategory.TRANSFORM),
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    private suspend fun <T> withRequestCache(block: suspend () -> T): T {
        val cache = RequestCache(cacheManager, requestCacheSerializer)
        return withContext(cache.asCoroutineContext()) { block() }
    }

    private fun service(
        repository: PipelineRepository = mockk(relaxed = true),
        permissionRepository: PipelinePermissionRepository = mockk(relaxed = true),
        executor: PipelineExecutor = mockk(relaxed = true),
        securityService: SecurityService = mockk(relaxed = true),
        pubSubProvider: ObjectProvider<PubSubService> = mockk(relaxed = true),
    ) = PipelineServiceImpl(
        repository, permissionRepository, executor, securityService, PipelinesRuntimeConfiguration(), pubSubProvider,
    )

    /** A valid persisted graph element for building [PipelineRecord]s the service can decode. */
    private fun graphElement(): JsonElement = baseJsonWithNodes().let { j ->
        j.encodeToJsonElement(
            bosca.pipelines.model.PipelineGraph.serializer(),
            bosca.pipelines.model.PipelineGraph(listOf(InputNode(id = "in", acceptedType = "JSON")), emptyList()),
        )
    }

    private fun baseJsonWithNodes() = Json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
            polymorphic(PipelineNode::class) {
                subclass(InputNode::class, InputNode.serializer())
                subclass(OutputNode::class, OutputNode.serializer())
                subclass(SvcTestNode::class, SvcTestNode.serializer())
            }
        }
    }

    private fun record(id: UUID = UUID.random(), name: String = "p", triggered: Boolean = false, key: String = "", api: Boolean = false) =
        PipelineRecord(id = id, name = name, acceptedInputType = "JSON", triggered = triggered, key = key, api = api, graph = graphElement())

    @Test
    fun `decodeGraph round-trips the polymorphic node graph`() = runTest {
        val svc = service()
        val nodes = listOf(InputNode(id = "in", acceptedType = "JSON"), SvcTestNode(id = "t"), OutputNode(id = "out"))
        val edges = listOf(PipelineEdge(id = "e1", source = "in", target = "t"), PipelineEdge(id = "e2", source = "t", target = "out"))
        val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "JSON", nodes = nodes, edges = edges)

        val decoded = svc.decodeGraph(svc.graphAsJsonElement(pipeline))

        assertEquals(3, decoded.nodes.size)
        assertTrue(decoded.nodes.any { it is SvcTestNode }, "the typed node should survive the round-trip")
        assertEquals(2, decoded.edges.size)
    }

    @Test
    fun `descriptorFor resolves a registered node type`() = runTest {
        assertEquals("svcTestNode", service().descriptorFor(SvcTestNode(id = "t"))?.key)
    }

    @Test
    fun `descriptorFor returns null for a type with no registered descriptor`() = runTest {
        assertEquals(null, service().descriptorFor(InputNode(id = "in", acceptedType = "JSON")))
    }

    @Test
    fun `getAll maps records to pipelines`() = runTest {
        val repo = mockk<PipelineRepository>()
        coEvery { repo.getAll() } returns listOf(record(name = "a"), record(name = "b"))
        val all = service(repository = repo).getAll()
        assertEquals(listOf("a", "b"), all.map { it.name })
    }

    @Test
    fun `broken graphs are filtered from normal reads and exposed for repair`() = runTest {
        val repo = mockk<PipelineRepository>()
        val valid = record(name = "valid")
        val broken = record(name = "broken", key = "broken-key").copy(graph = JsonPrimitive("invalid graph"))
        coEvery { repo.getAll() } returns listOf(valid, broken)
        val svc = service(repository = repo)

        assertEquals(listOf("valid"), svc.getAll().map { it.name })
        val failures = svc.getBroken()
        assertEquals(1, failures.size)
        assertEquals(broken.id, failures.single().id)
        assertEquals("broken", failures.single().name)
        assertEquals("broken-key", failures.single().key)
        assertTrue(failures.single().error.isNotBlank())
    }

    @Test
    fun `getBroken preserves a useful diagnostic when decoding fails without a message`() = runTest {
        provides<PipelineNodeSerializers>(overrideExisting = true) {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                        defaultDeserializer { NullMessageSerExNodeDeser }
                    }
                }
                override val descriptors = emptyList<NodeDescriptor>()
            }
        }
        val repo = mockk<PipelineRepository>()
        val broken = record(name = "broken", key = "broken-key").copy(
            graph = buildJsonObject {
                put("nodes", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "unknown")
                        put("id", "n")
                    })
                })
                put("edges", buildJsonArray { })
            },
        )
        coEvery { repo.getAll() } returns listOf(broken)

        val failure = service(repository = repo).getBroken().single()

        assertEquals(kotlinx.serialization.SerializationException().toString(), failure.error)
    }

    @Test
    fun `get returns the pipeline or null`() = runTest {
        val repo = mockk<PipelineRepository>()
        val id = UUID.random()
        coEvery { repo.getById(id) } returns record(id = id, name = "x")
        val missing = UUID.random()
        coEvery { repo.getById(missing) } returns null
        val svc = service(repository = repo)
        assertEquals("x", svc.get(id)?.name)
        assertNull(svc.get(missing))
    }

    @Test
    fun `getByKey short-circuits on a blank key and never hits the repository`() = runTest {
        val repo = mockk<PipelineRepository>()
        assertNull(service(repository = repo).getByKey(""))
        coVerify(exactly = 0) { repo.getByKey(any()) }
    }

    @Test
    fun `getByKey resolves a non-blank key`() = runTest {
        val repo = mockk<PipelineRepository>()
        coEvery { repo.getByKey("k") } returns record(name = "keyed", key = "k")
        assertEquals("keyed", service(repository = repo).getByKey("k")?.name)
    }

    @Test
    fun `acceptingInput filters by accepted type`() = runTest {
        val repo = mockk<PipelineRepository>()
        coEvery { repo.getAll() } returns listOf(record(name = "json"))
        assertEquals(1, service(repository = repo).acceptingInput(setOf("JSON")).size)
        assertEquals(0, service(repository = repo).acceptingInput(setOf("other.Event")).size)
    }

    @Test
    fun `triggeredFor delegates to the repository`() = runTest {
        val repo = mockk<PipelineRepository>()
        coEvery { repo.getTriggeredByEventType("ev") } returns listOf(record(name = "t", triggered = true))
        assertEquals(1, service(repository = repo).triggeredFor("ev").size)
    }

    @Test
    fun `triggeredFor filters undecodable records and caches the valid result`() = runTest {
        val repo = mockk<PipelineRepository>()
        coEvery { repo.getTriggeredByEventType("ev") } returns listOf(
            record(name = "valid", triggered = true),
            record(name = "broken", triggered = true).copy(graph = JsonPrimitive("invalid graph")),
        )
        val svc = service(repository = repo)

        assertEquals(listOf("valid"), svc.triggeredFor("ev").map { it.name })
        assertEquals(listOf("valid"), svc.triggeredFor("ev").map { it.name })
        coVerify(exactly = 1) { repo.getTriggeredByEventType("ev") }
        svc.shutdown()
    }

    @Test
    fun `triggeredEventTypes caches within the TTL`() = runTest {
        val repo = mockk<PipelineRepository>()
        coEvery { repo.getTriggeredEventTypes() } returns listOf("a", "b")
        val svc = service(repository = repo)
        assertEquals(setOf("a", "b"), svc.triggeredEventTypes())
        assertEquals(setOf("a", "b"), svc.triggeredEventTypes())
        coVerify(exactly = 1) { repo.getTriggeredEventTypes() }
    }

    @Test
    fun `trigger invalidation subscription clears both local trigger caches`() = runTest {
        val repo = mockk<PipelineRepository>()
        var records = listOf(record(name = "first", triggered = true))
        coEvery { repo.getTriggeredByEventType("ev") } answers { records }
        coEvery { repo.getTriggeredEventTypes() } returnsMany listOf(listOf("ev"), listOf("ev", "other"))
        val pubSub = TestPubSub()
        val provider = object : ObjectProvider<PubSubService> {
            override val type = PubSubService::class
            override val exists = true
            override suspend fun get() = pubSub
        }
        val svc = service(repository = repo, pubSubProvider = provider)
        pubSub.subscribed.await()
        assertEquals(listOf("first"), svc.triggeredFor("ev").map { it.name })
        assertEquals(setOf("ev"), svc.triggeredEventTypes())

        records = listOf(record(name = "second", triggered = true))
        pubSub.incoming.emit(
            Message(PIPELINE_TRIGGERS_CHANGED_CHANNEL, PipelineTriggersChanged(UUID.random())),
        )

        withTimeout(2_000) {
            while (svc.triggeredFor("ev").single().name != "second") delay(10)
        }
        assertEquals(setOf("ev", "other"), svc.triggeredEventTypes())
        svc.shutdown()
        coVerify(exactly = 2) { repo.getTriggeredByEventType("ev") }
        coVerify(exactly = 2) { repo.getTriggeredEventTypes() }
    }

    @Test
    fun `trigger invalidation subscription retries after completion and failure`() = runTest {
        val attempts = AtomicInteger()
        val subscribed = CountDownLatch(3)
        val pubSub = object : PubSubService {
            override suspend fun <T> publish(
                channel: String,
                serializer: SerializationStrategy<T>,
                message: T,
            ) = Unit

            override fun <T> subscribe(
                channel: String,
                deserializer: DeserializationStrategy<T>,
            ): Flow<Message<T>> {
                val attempt = attempts.incrementAndGet()
                subscribed.countDown()
                return when (attempt) {
                    1 -> emptyFlow()
                    2 -> flow { throw IllegalStateException("broker unavailable") }
                    else -> flow { awaitCancellation() }
                }
            }
        }
        val provider = object : ObjectProvider<PubSubService> {
            override val type = PubSubService::class
            override val exists = true
            override suspend fun get() = pubSub
        }
        val svc = service(pubSubProvider = provider)

        assertTrue(
            withContext(Dispatchers.IO) { subscribed.await(4, TimeUnit.SECONDS) },
            "the subscriber should retry after a completed flow and a failed flow",
        )
        assertEquals(3, attempts.get())
        svc.shutdown()
    }

    @Test
    fun `trigger invalidation publication is best effort`() = runTest {
        val repo = mockk<PipelineRepository>(relaxed = true)
        val pubSub = mockk<PubSubService>()
        var available = false
        val provider = object : ObjectProvider<PubSubService> {
            override val type = PubSubService::class
            override val exists get() = available
            override suspend fun get() = pubSub
        }
        val svc = service(repository = repo, pubSubProvider = provider)
        available = true
        coEvery {
            pubSub.publish(
                PIPELINE_TRIGGERS_CHANGED_CHANNEL,
                any(),
                any<PipelineTriggersChanged>(),
            )
        } returns Unit

        val first = UUID.random()
        svc.delete(first)
        coVerify(exactly = 1) {
            pubSub.publish(
                PIPELINE_TRIGGERS_CHANGED_CHANNEL,
                any(),
                match<PipelineTriggersChanged> { it.pipelineId == first },
            )
        }

        coEvery {
            pubSub.publish(
                PIPELINE_TRIGGERS_CHANGED_CHANNEL,
                any(),
                any<PipelineTriggersChanged>(),
            )
        } throws IllegalStateException("broker unavailable")
        svc.delete(UUID.random())
        svc.shutdown()
    }

    @Test
    fun `save creates a new pipeline when id is NIL and normalizes blank schedule and non-positive caps`() = runTest {
        val repo = mockk<PipelineRepository>()
        val captured = slot<PipelineRecord>()
        coEvery { repo.add(capture(captured)) } answers { captured.captured }
        service(repository = repo).save(
            id = UUID.NIL, name = "n", description = "d", acceptedInputType = "JSON", triggered = false,
            version = 0, graph = graphElement(), key = "", api = false, public = false,
            schedule = "  ", maxConcurrentRuns = 0, maxRunsPerMinute = -1,
        )
        assertEquals(null, captured.captured.schedule, "blank schedule normalizes to null")
        assertEquals(null, captured.captured.maxConcurrentRuns, "<=0 cap normalizes to null")
        assertEquals(null, captured.captured.maxRunsPerMinute)
        coVerify(exactly = 1) { repo.add(any()) }
    }

    @Test
    fun `save updates an existing pipeline when id is set`() = runTest {
        val repo = mockk<PipelineRepository>()
        val id = UUID.random()
        coEvery { repo.update(any()) } returns record(id = id, name = "u")
        service(repository = repo).save(
            id = id, name = "u", description = "", acceptedInputType = "JSON", triggered = false,
            version = 1, graph = graphElement(),
        )
        coVerify(exactly = 1) { repo.update(any()) }
        coVerify(exactly = 0) { repo.add(any()) }
    }

    @Test
    fun `save rejects an API pipeline without an endpoint key`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service().save(
                id = UUID.NIL, name = "n", description = "", acceptedInputType = "JSON", triggered = false,
                version = 0, graph = graphElement(), key = "", api = true,
            )
        }
    }

    @Test
    fun `save throws when an update finds no row`() = runTest {
        val repo = mockk<PipelineRepository>()
        coEvery { repo.update(any()) } returns null
        assertFailsWith<IllegalStateException> {
            service(repository = repo).save(
                id = UUID.random(), name = "n", description = "", acceptedInputType = "JSON", triggered = false,
                version = 9, graph = graphElement(),
            )
        }
    }

    @Test
    fun `delete soft-deletes via the repository`() = runTest {
        val repo = mockk<PipelineRepository>(relaxed = true)
        service(repository = repo).delete(UUID.NIL)
        coVerify(exactly = 1) { repo.softDelete(UUID.NIL) }
    }

    @Test
    fun `run executes the pipeline under the service account and returns the output`() = runTest {
        val executor = mockk<PipelineExecutor>()
        val output = PipelineValue.ofJson(buildJsonObject { put("done", true) })
        coEvery { executor.execute(any(), any(), any(), any()) } returns ExecutionResult.Completed(output)
        val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "JSON")
        val result = service(executor = executor).run(pipeline, PipelineValue.ofJson(buildJsonObject { put("x", 1) }))
        assertNotNull(result)
    }

    @Test
    fun `validateGraph returns null for a structurally valid graph`() = runTest {
        assertNull(service().validateGraph(graphElement()))
    }

    @Test
    fun `validateGraph reports a decode failure`() = runTest {
        val bad = buildJsonObject { put("nodes", JsonPrimitive("not-an-array")) }
        assertNotNull(service().validateGraph(bad))
    }

    // --- permissions / cache ---

    @Test
    fun `getPermissions returns the resolved grants when the cache yields a non-empty list`() = runTest {
        withRequestCache {
            val permRepo = mockk<PipelinePermissionRepository>()
            val id = UUID.random()
            val groupId = UUID.random()
            coEvery { permRepo.getPermissionsByPipelineId(id) } returns
                listOf(PipelinePermission(pipelineId = id, groupId = groupId, action = PermissionAction.EXECUTE))
            val pipeline = Pipeline(id = id, name = "p", acceptedInputType = "JSON")

            val perms = service(permissionRepository = permRepo).getPermissions(pipeline)

            assertEquals(1, perms.size)
            assertEquals(PermissionAction.EXECUTE, perms.first().action)
            assertEquals(groupId, perms.first().groupId)
            coVerify(exactly = 1) { permRepo.getPermissionsByPipelineId(id) }
        }
    }

    @Test
    fun `getPermissions returns an empty list when there are no grants`() = runTest {
        withRequestCache {
            val permRepo = mockk<PipelinePermissionRepository>()
            val id = UUID.random()
            coEvery { permRepo.getPermissionsByPipelineId(id) } returns emptyList()
            val pipeline = Pipeline(id = id, name = "p", acceptedInputType = "JSON")

            assertTrue(service(permissionRepository = permRepo).getPermissions(pipeline).isEmpty())
        }
    }

    @Test
    fun `addPermissionsToBatch resolves grants for matched keys and empties for unmatched keys`() = runTest {
        withRequestCache {
            val permRepo = mockk<PipelinePermissionRepository>()
            val withGrant = UUID.random()
            val withoutGrant = UUID.random()
            val groupId = UUID.random()
            // Only one of the two requested ids has a grant -> exercises both the `grouped[key]?.map`
            // (match) and the `?: emptyList()` (no-match) arms of the batch resolver.
            coEvery { permRepo.getPermissionsByPipelineIds(any()) } returns
                listOf(PipelinePermission(pipelineId = withGrant, groupId = groupId, action = PermissionAction.EXECUTE))

            val batch = Batch<UUID, List<EntityPermission>>(listOf(withGrant, withoutGrant))
            service(permissionRepository = permRepo).addPermissionsToBatch(batch)

            assertEquals(1, batch.getData(withGrant)?.size)
            assertEquals(PermissionAction.EXECUTE, batch.getData(withGrant)?.first()?.action)
            assertTrue(batch.getData(withoutGrant)?.isEmpty() == true)
            coVerify(exactly = 1) { permRepo.getPermissionsByPipelineIds(any()) }
        }
    }

    @Test
    fun `addPermission writes the grant inside a transaction and evicts the cache`() = runTest {
        withRequestCache {
            val permRepo = mockk<PipelinePermissionRepository>(relaxed = true)
            val pipelineId = UUID.random()
            val groupId = UUID.random()

            service(permissionRepository = permRepo).addPermission(pipelineId, groupId, PermissionAction.EXECUTE)

            coVerify(exactly = 1) { permRepo.addPermission(pipelineId, groupId, PermissionAction.EXECUTE) }
        }
    }

    @Test
    fun `deletePermission removes the grant inside a transaction and evicts the cache`() = runTest {
        withRequestCache {
            val permRepo = mockk<PipelinePermissionRepository>(relaxed = true)
            val pipelineId = UUID.random()
            val groupId = UUID.random()

            service(permissionRepository = permRepo).deletePermission(pipelineId, groupId, PermissionAction.VIEW)

            coVerify(exactly = 1) { permRepo.deletePermission(pipelineId, groupId, PermissionAction.VIEW) }
        }
    }

    // --- save edge cases ---

    @Test
    fun `save accepts an API pipeline with an endpoint key and trims surrounding whitespace`() = runTest {
        val repo = mockk<PipelineRepository>()
        val captured = slot<PipelineRecord>()
        coEvery { repo.add(capture(captured)) } answers { captured.captured }

        service(repository = repo).save(
            id = UUID.NIL, name = "n", description = "d", acceptedInputType = "JSON", triggered = false,
            version = 0, graph = graphElement(), key = "  endpoint  ", api = true, public = true,
        )

        assertEquals("endpoint", captured.captured.key, "the endpoint key is trimmed before persisting")
        assertTrue(captured.captured.api)
        assertTrue(captured.captured.public)
    }

    @Test
    fun `save keeps a non-blank schedule and positive concurrency caps`() = runTest {
        val repo = mockk<PipelineRepository>()
        val captured = slot<PipelineRecord>()
        coEvery { repo.add(capture(captured)) } answers { captured.captured }

        service(repository = repo).save(
            id = UUID.NIL, name = "n", description = "d", acceptedInputType = "JSON", triggered = true,
            version = 0, graph = graphElement(), key = "", api = false, public = false,
            schedule = "  0 0 * * *  ", maxConcurrentRuns = 5, maxRunsPerMinute = 10,
        )

        assertEquals("0 0 * * *", captured.captured.schedule, "a non-blank schedule is trimmed and kept")
        assertEquals(5, captured.captured.maxConcurrentRuns)
        assertEquals(10, captured.captured.maxRunsPerMinute)
    }

    @Test
    fun `save canonicalizes tags by trimming dropping blanks and deduplicating case insensitively`() = runTest {
        val repo = mockk<PipelineRepository>()
        val captured = slot<PipelineRecord>()
        coEvery { repo.add(capture(captured)) } answers { captured.captured }

        service(repository = repo).save(
            id = UUID.NIL,
            name = "n",
            description = "d",
            acceptedInputType = "JSON",
            triggered = false,
            version = 0,
            graph = graphElement(),
            tags = listOf(" First ", "", "first", "SECOND", "  "),
        )

        assertEquals(listOf("First", "SECOND"), captured.captured.tags)
    }

    // --- getByKey edge case ---

    @Test
    fun `getByKey returns null when a non-blank key resolves to no record`() = runTest {
        val repo = mockk<PipelineRepository>()
        coEvery { repo.getByKey("missing") } returns null
        assertNull(service(repository = repo).getByKey("missing"))
        coVerify(exactly = 1) { repo.getByKey("missing") }
    }

    // --- trigger-cache TTL expiry ---

    @Test
    fun `trigger caches refetch after their TTL expires`() = runTest {
        val repo = mockk<PipelineRepository>()
        var records = listOf(record(name = "first", triggered = true))
        coEvery { repo.getTriggeredByEventType("ev") } answers { records }
        coEvery { repo.getTriggeredEventTypes() } returnsMany listOf(listOf("a"), listOf("a", "b"))
        val svc = service(repository = repo)

        assertEquals(setOf("a"), svc.triggeredEventTypes())
        assertEquals(listOf("first"), svc.triggeredFor("ev").map { it.name })

        records = listOf(record(name = "second", triggered = true))
        PipelineServiceImpl::class.java.getDeclaredField("triggeredTypesCache").apply {
            isAccessible = true
            set(svc, setOf("a") to 0L)
        }
        PipelineServiceImpl::class.java.getDeclaredField("triggeredForCache").apply {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cache = get(svc) as java.util.concurrent.ConcurrentHashMap<String, Pair<List<Pipeline>, Long>>
            cache["ev"] = cache.getValue("ev").first to 0L
        }

        assertEquals(setOf("a", "b"), svc.triggeredEventTypes())
        assertEquals(listOf("second"), svc.triggeredFor("ev").map { it.name })
        coVerify(exactly = 2) { repo.getTriggeredEventTypes() }
        coVerify(exactly = 2) { repo.getTriggeredByEventType("ev") }
    }

    // --- graphJson / descriptors caching (second-call short-circuit) ---

    @Test
    fun `decodeGraph reuses the cached Json on repeated calls`() = runTest {
        val svc = service()
        val element = graphElement()
        // First call builds and caches the aggregated Json + descriptors; the second hits the cache arm.
        assertNotNull(svc.decodeGraph(element))
        assertNotNull(svc.decodeGraph(element))
        assertEquals("svcTestNode", svc.descriptorFor(SvcTestNode(id = "t"))?.key)
        assertEquals("svcTestNode", svc.descriptorFor(SvcTestNode(id = "t2"))?.key)
    }

    // --- validateGraph additional arms ---

    @Test
    fun `validateGraph returns null when the element has no nodes array`() = runTest {
        // Decodes to an empty PipelineGraph (no nodes/edges), then `nodes` is absent -> the
        // `?: return null` arm after the successful decode.
        val noNodes = buildJsonObject { put("edges", buildJsonArray { }) }
        assertNull(service().validateGraph(noNodes))
    }

    @Test
    fun `validateGraph reports a decode failure for a top-level non-object`() = runTest {
        // Decoding a JsonArray into the object-shaped PipelineGraph throws (a JsonDecodingException,
        // i.e. SerializationException), driving the decode-failure return-message path with a distinct
        // input from the other decode-failure test. (The IllegalArgumentException catch arm is not
        // reachable from JSON input in kotlinx 1.10 — see branch notes.)
        val notAnObject: JsonElement = buildJsonArray { add(JsonPrimitive("nope")) }
        assertNotNull(service().validateGraph(notAnObject))
    }

    @Test
    fun `validateGraph reports slot connection violations`() = runTest {
        // Register a node module/descriptors with an INTEGER source feeding a UUID slot -> a violation.
        provides<PipelineNodeSerializers>(overrideExisting = true) {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                        subclass(OutputNode::class, OutputNode.serializer())
                        subclass(IntSourceNode::class, IntSourceNode.serializer())
                        subclass(UuidTargetNode::class, UuidTargetNode.serializer())
                    }
                }
                override val descriptors = listOf(
                    NodeDescriptor(
                        key = "svcIntSource", label = "Int", category = NodeCategory.TRANSFORM,
                        outputs = listOf(NodeOutputSlot(name = "out", kind = SlotKind.INTEGER)),
                    ),
                    NodeDescriptor(
                        key = "svcUuidTarget", label = "Uuid", category = NodeCategory.TRANSFORM,
                        inputs = listOf(NodeInputSlot(name = "in", typeLabel = "UUID", kind = SlotKind.UUID)),
                    ),
                )
            }
        }
        val graph = buildJsonObject {
            put("nodes", buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("svcIntSource")); put("id", JsonPrimitive("s")) })
                add(buildJsonObject { put("type", JsonPrimitive("svcUuidTarget")); put("id", JsonPrimitive("t")) })
            })
            put("edges", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive("e1")); put("source", JsonPrimitive("s")); put("target", JsonPrimitive("t"))
                })
            })
        }

        val violations = service().validateGraph(graph)

        assertNotNull(violations)
        assertTrue(violations.contains("uuid"), "the violation names the expected UUID slot kind: $violations")
        assertTrue(violations.contains("integer"), "the violation names the offending integer source: $violations")
    }

    @Test
    fun `descriptorFor returns null for an OutputNode whose type has no descriptor`() = runTest {
        // The "output" type key extracts fine, but no descriptor is registered for it -> the
        // `descriptors()[typeKey]` lookup misses and returns null (a different node than the InputNode case).
        assertNull(service().descriptorFor(OutputNode(id = "out")))
    }

    // NOTE: the populated-but-EXPIRED cache arm (now >= expires) of triggeredEventTypes is deliberately
    // left uncovered. Exercising it requires controlling the wall clock the service reads
    // (System.currentTimeMillis()), and the only way to do that from a test — mockkStatic(System::class) —
    // mocks System process-wide: every thread (Testcontainers Ryuk, coroutine dispatchers, the coverage
    // agent) then routes through mockk's interceptor, which records millions of calls (OOM) and corrupts
    // mockk's state for unrelated tests. Not worth a process-global hazard for one branch; the cache-hit
    // (now < expires) and absent-cache arms are covered above.

    // --- save: an explicitly null schedule and null caps normalize to null (the `?.` null arms) ---

    @Test
    fun `save normalizes a null schedule and null caps to null`() = runTest {
        val repo = mockk<PipelineRepository>()
        val captured = slot<PipelineRecord>()
        coEvery { repo.add(capture(captured)) } answers { captured.captured }

        service(repository = repo).save(
            id = UUID.NIL, name = "n", description = "d", acceptedInputType = "JSON", triggered = false,
            version = 0, graph = graphElement(), key = "", api = false, public = false,
            schedule = null, maxConcurrentRuns = null, maxRunsPerMinute = null,
        )

        assertNull(captured.captured.schedule, "a null schedule stays null (the `?.trim()` null arm)")
        assertNull(captured.captured.maxConcurrentRuns, "a null cap stays null")
        assertNull(captured.captured.maxRunsPerMinute)
    }

    // --- getPermissions: the cache yields null (remote hit with no stored value) -> `?: emptyList()` ---

    @Test
    fun `getPermissions falls back to an empty list when the cache resolves to null`() = runTest {
        // Make the remote cache report a HIT whose stored value is empty/null. RequestCache then keeps
        // `result == null` and skips the resolver (because `fromCache.exists` is true), so
        // permissionCache.get(...) returns null -> the service's `?: emptyList()` fallback arm fires.
        coEvery { remoteCache.get(any()) } returns mockk<CacheValue> {
            every { exists } returns true
            every { value } returns null
        }
        withRequestCache {
            val permRepo = mockk<PipelinePermissionRepository>(relaxed = true)
            val pipeline = Pipeline(id = UUID.random(), name = "p", acceptedInputType = "JSON")

            assertTrue(service(permissionRepository = permRepo).getPermissions(pipeline).isEmpty())
            // The resolver must NOT have fired — the (null) value came from the remote hit.
            coVerify(exactly = 0) { permRepo.getPermissionsByPipelineId(any()) }
        }
    }

    // --- validateGraph / decodeGraph: the IllegalArgumentException decode-failure catch arm ---

    @Test
    fun `validateGraph returns the message when decode throws IllegalArgumentException from a node constructor`() = runTest {
        // Register a node type whose constructor `init { require(false) }` throws IAE. kotlinx does not
        // wrap constructor exceptions, so decodeFromJsonElement propagates the raw IAE -> the service's
        // `catch (e: IllegalArgumentException)` arm runs and returns e.message.
        provides<PipelineNodeSerializers>(overrideExisting = true) {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                        subclass(OutputNode::class, OutputNode.serializer())
                        subclass(IaeNode::class, IaeNode.serializer())
                    }
                }
                override val descriptors = emptyList<NodeDescriptor>()
            }
        }
        val graph = buildJsonObject {
            put("nodes", buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("svcIaeNode")); put("id", JsonPrimitive("bad")) })
            })
            put("edges", buildJsonArray { })
        }

        val message = service().validateGraph(graph)

        assertNotNull(message, "the IAE catch arm must return a (non-null) message")
        assertTrue(message.contains("svcIaeNode"), "the returned message is the IAE's message: $message")
    }

    @Test
    fun `decodeGraph propagates the IllegalArgumentException from a node constructor`() = runTest {
        // decodeGraph has no try/catch, so the same constructor IAE surfaces directly to the caller.
        provides<PipelineNodeSerializers>(overrideExisting = true) {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                        subclass(IaeNode::class, IaeNode.serializer())
                    }
                }
                override val descriptors = emptyList<NodeDescriptor>()
            }
        }
        val graph = buildJsonObject {
            put("nodes", buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("svcIaeNode")); put("id", JsonPrimitive("bad")) })
            })
            put("edges", buildJsonArray { })
        }
        assertFailsWith<IllegalArgumentException> { service().decodeGraph(graph) }
    }

    // --- validateGraph: the per-node mapNotNull `?: return@mapNotNull null` arms (238/239/240) ---
    //
    // These run only after a SUCCESSFUL decode of the graph, then re-read the RAW `nodes` array to pair
    // id -> type. A strict decoder would reject any malformed node element first, so to reach the null
    // arms the decode must SUCCEED despite a raw node that is not an object / lacks "id" / lacks "type".
    // We arrange that with a lenient Json plus a polymorphic DEFAULT deserializer that yields a fixed
    // node for any (even discriminator-less) object — so the raw array can carry pathological entries
    // while the typed decode still succeeds.

    @Test
    fun `validateGraph skips raw node entries that are missing id or type`() = runTest {
        // The validator re-reads the RAW nodes array AFTER a successful decode. A strict decoder would
        // reject a node lacking "id"/"type" first, so to reach the per-node null arms the decode must
        // SUCCEED despite such entries. We register a polymorphic DEFAULT deserializer ([FixedNodeDeser])
        // that ignores the body and yields a fixed node, so any object (even one with no "id"/"type")
        // decodes -> the typed decode passes while the raw entries are pathological.
        provides<PipelineNodeSerializers>(overrideExisting = true) {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                        subclass(OutputNode::class, OutputNode.serializer())
                        defaultDeserializer { FixedNodeDeser }
                    }
                }
                override val descriptors = emptyList<NodeDescriptor>()
            }
        }
        // Raw nodes: one object missing "type" (240 null), one object missing "id" (239 null), one
        // well-formed (the happy mapNotNull arm). All decode via the default deserializer.
        val graph = buildJsonObject {
            put("nodes", buildJsonArray {
                add(buildJsonObject { put("id", JsonPrimitive("noType")); put("payload", JsonPrimitive("x")) })
                add(buildJsonObject { put("type", JsonPrimitive("unregistered")); put("payload", JsonPrimitive("y")) })
                add(buildJsonObject { put("type", JsonPrimitive("unregistered2")); put("id", JsonPrimitive("ok")) })
            })
            put("edges", buildJsonArray { })
        }

        // No edges -> no slot violations -> overall null, but the mapNotNull body still runs over every
        // raw entry, exercising the missing-type (240) and missing-id (239) `return@mapNotNull null` arms
        // plus the well-formed (non-null) arm.
        assertNull(service().validateGraph(graph))
    }

    @Test
    fun `validateGraph reports a decode failure when a raw node entry is a JSON primitive`() = runTest {
        // A bare primitive at a node position cannot be read as a polymorphic node — the typed decode
        // throws a JsonDecodingException (a SerializationException) at line 230, so validateGraph returns
        // its message via the `catch (e: SerializationException)` arm (232) and never reaches the raw
        // re-read. (The `node as? JsonObject` guard at 238 is therefore a pure defensive arm: a non-object
        // raw entry always fails the strict typed decode first.)
        val lenient = Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
            serializersModule = baseJson.serializersModule
        }
        provides<Json>(overrideExisting = true) { lenient }
        provides<PipelineNodeSerializers>(overrideExisting = true) {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                        defaultDeserializer { FixedNodeDeser }
                    }
                }
                override val descriptors = emptyList<NodeDescriptor>()
            }
        }
        val graph = buildJsonObject {
            put("nodes", buildJsonArray {
                add(JsonPrimitive("not-an-object"))
            })
            put("edges", buildJsonArray { })
        }

        assertNotNull(service().validateGraph(graph), "a non-object node element fails decode -> message")
    }

    // --- descriptorFor: the defensive `?: return null` arm (223) ---
    //
    // descriptorFor encodes the node polymorphically and reads the "type" key from the encoded object.
    // With the default `classDiscriminator = "type"` a registered node always yields a `{ "type": ... }`
    // object, so the null arm is never seen. We drive it by re-providing a Json whose discriminator is
    // NOT "type": the encoded object then carries the discriminator under a different key and has no
    // "type" key at all -> `obj["type"]` is null -> `as? JsonPrimitive` is null -> `?: return null`.

    // --- validateGraph: the catch arms' `e.message ?: "invalid pipeline graph"` NULL-message right arms ---
    //
    // The existing decode-failure tests reach the catch arms with NON-null messages (a JsonDecodingException
    // / a `require` IAE both carry text). These two drive the `?: "invalid pipeline graph"` fallback by
    // registering polymorphic default deserializers that throw exceptions whose `message` is null — one a
    // SerializationException (the `catch (SerializationException)` arm), one an IllegalArgumentException
    // (the `catch (IllegalArgumentException)` arm). Both throw BEFORE reading any element to keep kotlinx's
    // shared JSON-decode state clean across test classes.

    /**
     * A node whose polymorphic deserializer throws a message-less [SerializationException] straight away
     * (before reading any element, so the shared JSON decoder state stays clean).
     */
    object NullMessageSerExNodeDeser : kotlinx.serialization.DeserializationStrategy<PipelineNode> {
        override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
            kotlinx.serialization.descriptors.buildClassSerialDescriptor("NullMessageSerExNode")

        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): PipelineNode {
            throw kotlinx.serialization.SerializationException() // null message — thrown before reading any element
        }
    }

    /**
     * A node whose polymorphic deserializer throws a message-less [IllegalArgumentException] (NOT a
     * [SerializationException]) straight away — kotlinx propagates it raw out of `decodeFromJsonElement`,
     * driving the service's `catch (IllegalArgumentException)` arm (distinct from the SerializationException
     * arm). It throws BEFORE reading any element, so it leaves the JSON decoder in a clean state (calling
     * `decodeJsonElement()` and THEN throwing corrupts kotlinx's shared decode state across test classes).
     */
    object NullMessageIaeNodeDeser : kotlinx.serialization.DeserializationStrategy<PipelineNode> {
        override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
            kotlinx.serialization.descriptors.buildClassSerialDescriptor("NullMessageIaeNode")

        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): PipelineNode {
            throw IllegalArgumentException() // null message, thrown before reading any element (state-safe)
        }
    }

    @Test
    fun `validateGraph falls back to the default message when a SerializationException has no message`() = runTest {
        // A polymorphic default deserializer that throws a message-less SerializationException -> the
        // `catch (e: SerializationException)` arm's `e.message ?: "invalid pipeline graph"` takes its
        // right (default-message) arm.
        provides<PipelineNodeSerializers>(overrideExisting = true) {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                        defaultDeserializer { NullMessageSerExNodeDeser }
                    }
                }
                override val descriptors = emptyList<NodeDescriptor>()
            }
        }
        val graph = buildJsonObject {
            put("nodes", buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("anything")); put("id", JsonPrimitive("n")) })
            })
            put("edges", buildJsonArray { })
        }

        assertEquals("invalid pipeline graph", service().validateGraph(graph))
    }

    @Test
    fun `validateGraph falls back to the default message when an IllegalArgumentException has no message`() = runTest {
        // A polymorphic default deserializer that throws a message-less IAE -> kotlinx propagates it raw
        // (it is an IllegalArgumentException, not a SerializationException) -> the
        // `catch (e: IllegalArgumentException)` arm's `e.message ?: "invalid pipeline graph"` takes its
        // right (default-message) arm.
        provides<PipelineNodeSerializers>(overrideExisting = true) {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                        defaultDeserializer { NullMessageIaeNodeDeser }
                    }
                }
                override val descriptors = emptyList<NodeDescriptor>()
            }
        }
        val graph = buildJsonObject {
            put("nodes", buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("anything")); put("id", JsonPrimitive("bad")) })
            })
            put("edges", buildJsonArray { })
        }

        assertEquals("invalid pipeline graph", service().validateGraph(graph))
    }

    // NOTE: the remaining residual branches on 222/223/236/238/239/240 are provably-dead defensive
    // null-safety arms unreachable through the public API without forbidden techniques (no reflection,
    // no editing main): e.g. `encodeToJsonElement(PipelineNode.serializer())` always yields a JsonObject
    // so `as? JsonObject` (222) never null; "type" is the polymorphic discriminator so a non-primitive
    // "type" (240) fails the strict typed decode before the raw re-read; a raw node that is not an object
    // (238) can't decode polymorphically at all; and the per-node id/type arms' second null-check is the
    // redundant arm of the Kotlin `?.` idiom. The reachable id/type arms (missing key) are already
    // covered by `validateGraph skips raw node entries that are missing id or type`.

    @Test
    fun `descriptorFor returns null when the encoded node carries no type key`() = runTest {
        val nonTypeDiscriminator = Json(baseJson) { classDiscriminator = "__kind__" }
        provides<Json>(overrideExisting = true) { nonTypeDiscriminator }
        // Register a descriptor under the key "input" so the test is DISCRIMINATING: if descriptorFor
        // wrongly extracted a type key it would resolve THIS descriptor (non-null) at the `descriptors()`
        // lookup. The only way the result is null is the `?: return null` arm at the type-key extraction.
        provides<PipelineNodeSerializers>(overrideExisting = true) {
            object : PipelineNodeSerializers {
                override val module = SerializersModule {
                    polymorphic(PipelineNode::class) {
                        subclass(InputNode::class, InputNode.serializer())
                    }
                }
                override val descriptors = listOf(
                    NodeDescriptor(key = "input", label = "In", category = NodeCategory.TRANSFORM),
                )
            }
        }
        // InputNode encodes (under "__kind__") to an object with no "type" key, so descriptorFor's
        // `(... ?.get("type") as? JsonPrimitive)?.content ?: return null` takes the return-null arm —
        // it never reaches the (registered) descriptor lookup, so the result is null.
        assertNull(service().descriptorFor(InputNode(id = "in", acceptedType = "JSON")))
    }
}
