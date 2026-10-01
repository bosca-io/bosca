@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.graphql.Batch
import bosca.pipelines.PipelineContext
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.model.BrokenPipeline
import bosca.pipelines.model.PIPELINE_TRIGGERS_CHANGED_CHANNEL
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.model.PipelineTriggersChanged
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.SlotConnectionValidator
import bosca.pipelines.repository.PipelinePermissionRepository
import bosca.pipelines.repository.PipelineRecord
import bosca.pipelines.repository.PipelineRepository
import bosca.pubsub.PubSubService
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.ExperimentalUuidApi

/**
 * Stores pipelines and converts the persisted [PipelineRecord] (graph as `jsonb`) to/from the typed
 * [Pipeline]. The graph (de)serializes through a `Json` built once from the platform's global `Json`
 * plus every module's [PipelineNodeSerializers] (aggregated via [ProviderRegistry.findAll]) — so the
 * polymorphic node graph round-trips natively, with no engine knowledge of the concrete node types.
 */
@ServiceImplementation
class PipelineServiceImpl(
    private val repository: PipelineRepository,
    private val permissionRepository: PipelinePermissionRepository,
    private val executor: PipelineExecutor,
    private val securityService: SecurityService,
    private val runtimeConfiguration: PipelinesRuntimeConfiguration,
    private val pubSubProvider: ObjectProvider<PubSubService>,
) : PipelineService {

    private val log = org.slf4j.LoggerFactory.getLogger(PipelineServiceImpl::class.java)

    private val permissionCache = ServiceCache<UUID, List<EntityPermission>>(
        "pipelines:permissions",
        UUIDKeySerializer,
        batchResolver = { keys, batch ->
            val allPermissions = permissionRepository.getPermissionsByPipelineIds(keys)
            val grouped = allPermissions.groupBy { it.pipelineId }
            keys.forEach { key ->
                batch.setData(key, grouped[key]?.map { it as EntityPermission } ?: emptyList())
            }
        }
    ) {
        permissionRepository.getPermissionsByPipelineId(it).map { it as EntityPermission }
    }

    @Volatile
    private var cachedJson: Json? = null

    @OptIn(InternalDI::class)
    private suspend fun graphJson(): Json {
        cachedJson?.let { return it }
        val global = provide<Json>()
        val nodeModules = ProviderRegistry.findAll(PipelineNodeSerializers::class)
            .filter { it.exists }
            .map { it.get().module }
        val combined = SerializersModule {
            include(global.serializersModule)
            nodeModules.forEach { include(it) }
        }
        @Suppress("JSON_FORMAT_REDUNDANT")
        return Json(global) {
            serializersModule = combined
            allowStructuredMapKeys = true
        }.also { cachedJson = it }
    }

    private suspend fun toPipeline(record: PipelineRecord): Pipeline {
        val graph = graphJson().decodeFromJsonElement(PipelineGraph.serializer(), record.graph)
        return Pipeline(
            id = record.id,
            name = record.name,
            description = record.description,
            acceptedInputType = record.acceptedInputType,
            tags = record.tags,
            triggered = record.triggered,
            key = record.key,
            api = record.api,
            public = record.public,
            schedule = record.schedule,
            maxConcurrentRuns = record.maxConcurrentRuns,
            maxRunsPerMinute = record.maxRunsPerMinute,
            nodes = graph.nodes,
            edges = graph.edges,
            groups = graph.groups,
            version = record.version,
            gitRepositoryId = record.gitRepositoryId,
            gitPath = record.gitPath,
            lastSyncError = record.lastSyncError,
            deletedAt = record.deletedAt,
        )
    }

    override suspend fun getAll(): List<Pipeline> = repository.getAll().mapNotNull {
        try {
            toPipeline(it)
        } catch (e: Exception) {
            log.error("Failed to convert pipeline record to Pipeline object", e)
            null
        }
    }

    override suspend fun getBroken(): List<BrokenPipeline> = repository.getAll().mapNotNull { record ->
        try {
            graphJson().decodeFromJsonElement(PipelineGraph.serializer(), record.graph)
            null // decodes cleanly — not broken
        } catch (e: Exception) {
            BrokenPipeline(record.id, record.name, record.key, e.message ?: e.toString())
        }
    }

    override suspend fun get(id: UUID): Pipeline? = repository.getById(id)?.let { toPipeline(it) }

    override suspend fun getByKey(key: String): Pipeline? =
        key.takeIf { it.isNotBlank() }?.let { repository.getByKey(it)?.let { record -> toPipeline(record) } }

    override suspend fun acceptingInput(typeNames: Set<String>): List<Pipeline> =
        getAll().filter { it.acceptedInputType in typeNames }

    // Per-event cache, sibling of [triggeredTypesCache]: triggeredFor runs in the dispatch / inline
    // hot path (e.g. the analytics transform chain calls it once per event batch), so it must not hit
    // the DB per call. Kept fresh across instances by the pub/sub invalidation below; the
    // [TRIGGER_CACHE_TTL_MS] backstop only bounds staleness if a pub/sub message is ever missed.
    private val triggeredForCache = ConcurrentHashMap<String, Pair<List<Pipeline>, Long>>()

    override suspend fun triggeredFor(eventName: String): List<Pipeline> {
        val now = System.currentTimeMillis()
        triggeredForCache[eventName]?.let { (pipelines, expires) -> if (now < expires) return pipelines }
        val pipelines = repository.getTriggeredByEventType(eventName).mapNotNull {
            try {
                toPipeline(it)
            } catch (e: Exception) {
                log.error("Failed to convert pipeline record to Pipeline object", e)
                null
            }
        }
        triggeredForCache[eventName] = pipelines to (now + TRIGGER_CACHE_TTL_MS)
        return pipelines
    }

    // The dispatch gate runs in the event-firing hot path, so it must not hit the DB per event. Kept
    // fresh across instances by the pub/sub invalidation below; the [TRIGGER_CACHE_TTL_MS] backstop
    // only bounds staleness if a pub/sub message is ever missed. The local instance also clears
    // immediately on save/delete.
    @Volatile
    private var triggeredTypesCache: Pair<Set<String>, Long>? = null

    override suspend fun triggeredEventTypes(): Set<String> {
        val now = System.currentTimeMillis()
        triggeredTypesCache?.let { (types, expires) -> if (now < expires) return types }
        val types = repository.getTriggeredEventTypes().toSet()
        triggeredTypesCache = types to (now + TRIGGER_CACHE_TTL_MS)
        return types
    }

    // Cross-instance cache invalidation: a save/delete on any instance publishes to
    // [PIPELINE_TRIGGERS_CHANGED_CHANNEL]; every instance clears its triggered caches immediately
    // instead of waiting out the TTL backstop. Pub/sub is best-effort, so the TTL still guarantees
    // eventual correctness if a message is missed; when pub/sub is absent the caches degrade to
    // TTL-only. SupervisorJob isolates the subscriber; the loop retries with capped backoff.
    private val invalidationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("pipeline-trigger-invalidation"))

    init {
        if (pubSubProvider.exists) subscribeTriggerInvalidations()
    }

    private fun subscribeTriggerInvalidations() {
        invalidationScope.launch {
            val pubSub = pubSubProvider.get()
            var backoffMs = INITIAL_RETRY_DELAY_MS
            while (true) {
                try {
                    pubSub.subscribe(PIPELINE_TRIGGERS_CHANGED_CHANNEL, PipelineTriggersChanged.serializer())
                        .collect {
                            backoffMs = INITIAL_RETRY_DELAY_MS
                            triggeredTypesCache = null
                            triggeredForCache.clear()
                        }
                    delay(backoffMs)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    log.error("Pipeline trigger cache invalidation subscriber failed; retrying in {}ms", backoffMs, e)
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun publishTriggersChanged(pipelineId: UUID) {
        if (!pubSubProvider.exists) return
        try {
            pubSubProvider.get().publish(
                PIPELINE_TRIGGERS_CHANGED_CHANNEL,
                PipelineTriggersChanged.serializer(),
                PipelineTriggersChanged(pipelineId),
            )
        } catch (e: Exception) {
            log.error("Failed to publish pipeline trigger cache invalidation for {}", pipelineId, e)
        }
    }

    override suspend fun shutdown() {
        invalidationScope.cancel()
    }

    override suspend fun save(
        id: UUID,
        name: String,
        description: String,
        acceptedInputType: String,
        triggered: Boolean,
        version: Long,
        graph: JsonElement,
        tags: List<String>,
        key: String,
        api: Boolean,
        public: Boolean,
        schedule: String?,
        maxConcurrentRuns: Int?,
        maxRunsPerMinute: Int?,
    ): Pipeline {
        val endpointKey = key.trim()
        require(!api || endpointKey.isNotEmpty()) { "An API-callable pipeline needs an endpoint key" }
        // Validate the graph against the node registry before persisting.
        graphJson().decodeFromJsonElement(PipelineGraph.serializer(), graph)
        val record = PipelineRecord(
            id = id,
            name = name,
            description = description,
            acceptedInputType = acceptedInputType,
            // Canonicalize: trim, drop blanks, de-dupe (case-insensitive) so a tag has one stored form.
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
            triggered = triggered,
            key = endpointKey,
            api = api,
            public = public,
            schedule = schedule?.trim()?.takeIf { it.isNotBlank() },
            // Normalize ≤0 to null (unlimited) so "no limit" has one canonical representation.
            maxConcurrentRuns = maxConcurrentRuns?.takeIf { it > 0 },
            maxRunsPerMinute = maxRunsPerMinute?.takeIf { it > 0 },
            graph = graph,
            version = version,
        )
        val saved = if (id == UUID.NIL) {
            repository.add(record)
        } else {
            repository.update(record)
                ?: error("Pipeline $id not found or version conflict (expected $version)")
        }
        triggeredTypesCache = null
        triggeredForCache.clear()
        publishTriggersChanged(saved.id)
        return toPipeline(saved)
    }

    override suspend fun getPermissions(entity: Pipeline): List<EntityPermission> {
        return permissionCache.get(entity.id) ?: emptyList()
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        permissionCache.addToBatch(batch)
    }

    override suspend fun addPermission(pipelineId: UUID, groupId: UUID, action: PermissionAction) {
        transaction {
            permissionRepository.addPermission(pipelineId, groupId, action)
        }
        permissionCache.remove(pipelineId)
    }

    override suspend fun deletePermission(pipelineId: UUID, groupId: UUID, action: PermissionAction) {
        transaction {
            permissionRepository.deletePermission(pipelineId, groupId, action)
        }
        permissionCache.remove(pipelineId)
    }

    override suspend fun delete(id: UUID) {
        repository.softDelete(id)
        triggeredTypesCache = null
        triggeredForCache.clear()
        publishTriggersChanged(id)
    }

    override suspend fun run(pipeline: Pipeline, input: PipelineValue): PipelineValue? {
        val context = PipelineContext(
            securityService.impersonate(runtimeConfiguration.serviceAccount),
            graphJson(),
        )
        return executor.execute(pipeline, input, context).requireCompleted()
    }

    override suspend fun graphAsJsonElement(pipeline: Pipeline): JsonElement =
        graphJson().encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(pipeline.nodes, pipeline.edges, pipeline.groups))

    override suspend fun decodeGraph(graph: JsonElement): PipelineGraph =
        graphJson().decodeFromJsonElement(PipelineGraph.serializer(), graph)

    override suspend fun descriptorFor(node: bosca.pipelines.node.PipelineNode): NodeDescriptor? {
        val typeKey = ((graphJson().encodeToJsonElement(bosca.pipelines.node.PipelineNode.serializer(), node) as? JsonObject)
            ?.get("type") as? JsonPrimitive)?.content ?: return null
        return descriptors()[typeKey]
    }

    override suspend fun validateGraph(graph: JsonElement): String? {
        val json = graphJson()
        val decoded = try {
            json.decodeFromJsonElement(PipelineGraph.serializer(), graph)
        } catch (e: SerializationException) {
            return e.message ?: "invalid pipeline graph"
        } catch (e: IllegalArgumentException) {
            return e.message ?: "invalid pipeline graph"
        }
        val nodes = (graph as? JsonObject)?.get("nodes") as? JsonArray ?: return null
        val keyById = nodes.mapNotNull { node ->
            val obj = node as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val type = (obj["type"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            id to type
        }.toMap()
        // A node may declare its output per instance (e.g. a JSONata node annotated "returns a
        // string"); those declarations override the (dynamic) type descriptor in the validator.
        val declaredOutputs = decoded.nodes.mapNotNull { node ->
            (node as? bosca.pipelines.node.HasDeclaredOutput)?.let { node.id to it }
        }.toMap()
        // …and the input-side mirror: a node may require a specific type on one of its slots per
        // instance (an email template node's payload contract).
        val declaredInputs = decoded.nodes.mapNotNull { node ->
            (node as? bosca.pipelines.node.HasDeclaredInputs)?.let { node.id to it }
        }.toMap()
        val violations = SlotConnectionValidator.validate(keyById, decoded.edges, descriptors(), declaredOutputs, declaredInputs)
        return violations.ifEmpty { null }?.joinToString("; ")
    }

    @Volatile
    private var cachedDescriptors: Map<String, NodeDescriptor>? = null

    /** Node-type descriptors keyed by `@SerialName`, aggregated across modules (see [graphJson]). */
    @OptIn(InternalDI::class)
    private suspend fun descriptors(): Map<String, NodeDescriptor> {
        cachedDescriptors?.let { return it }
        return ProviderRegistry.findAll(PipelineNodeSerializers::class)
            .filter { it.exists }
            .flatMap { it.get().descriptors }
            .associateBy { it.key }
            .also { cachedDescriptors = it }
    }

    companion object {
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        // Durable backstop for the triggered caches. Pub/sub handles timely cross-instance
        // invalidation; this only bounds staleness if a pub/sub message is ever missed.
        private const val TRIGGER_CACHE_TTL_MS = 300_000L
    }
}
