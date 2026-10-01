package bosca.pipelines.service

import bosca.pipelines.model.BrokenPipeline
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineValue
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * CRUD for stored, reusable [Pipeline]s. Consumers (scripting triggers, analytics, …) load a pipeline
 * here, then run it via [PipelineExecutor]. Implementations (de)serialize the polymorphic node graph
 * through the engine's aggregated node `SerializersModule` (native-safe).
 *
 * Extends [PermissionService] so endpoint-exposed pipelines use the standard group-based grant model
 * (mirroring scripts).
 */
interface PipelineService : PermissionService<Pipeline, UUID> {

    suspend fun getAll(): List<Pipeline>

    /**
     * Pipelines whose stored graph no longer decodes (a renamed/removed node type, a module no longer
     * loaded, or corrupt data). These are absent from [getAll] and throw from [get], so this is the only
     * way to see and then delete them. Read straight from the record — no graph decode.
     */
    suspend fun getBroken(): List<BrokenPipeline>

    suspend fun get(id: UUID): Pipeline?

    /** A pipeline by its REST endpoint [Pipeline.key], or null when no pipeline carries the key. */
    suspend fun getByKey(key: String): Pipeline?

    /** Grants [action] on the pipeline to a security group. */
    suspend fun addPermission(pipelineId: UUID, groupId: UUID, action: PermissionAction)

    /** Revokes a previously granted [action] on the pipeline from a security group. */
    suspend fun deletePermission(pipelineId: UUID, groupId: UUID, action: PermissionAction)

    /**
     * Pipelines whose declared `acceptedInputType` is one of [typeNames] — the caller passes the
     * input value's runtime type plus its supertypes (computed via the `java.lang.Class` hierarchy,
     * native-safe), so this returns every pipeline that can accept that input.
     */
    suspend fun acceptingInput(typeNames: Set<String>): List<Pipeline>

    /** Triggered (active) pipelines that accept [eventName] — what the dispatch path runs. */
    suspend fun triggeredFor(eventName: String): List<Pipeline>

    /**
     * The set of event types with at least one triggered pipeline — a short-TTL-cached gate so the
     * event-firing process can skip dispatch work for events no pipeline listens to.
     */
    suspend fun triggeredEventTypes(): Set<String>

    /**
     * Executes [pipeline] inline (synchronously, in the caller's coroutine) with [input], under the
     * pipelines module's configured service account. For consumers that need the result in-flow —
     * e.g. analytics transform steps. Returns the value reaching the Output node, or `null`.
     */
    suspend fun run(pipeline: Pipeline, input: PipelineValue): PipelineValue?

    /**
     * Creates (when [id] is `UUID.NIL`) or updates a pipeline. [graph] is the node/edge graph as a
     * [JsonElement] (validated against the node registry before storing). Updates are optimistic-locked
     * by [version]. Exposing the pipeline as a REST endpoint ([api]) requires a non-blank [key].
     */
    suspend fun save(
        id: UUID,
        name: String,
        description: String,
        acceptedInputType: String,
        triggered: Boolean,
        version: Long,
        graph: JsonElement,
        tags: List<String> = emptyList(),
        key: String = "",
        api: Boolean = false,
        public: Boolean = false,
        schedule: String? = null,
        maxConcurrentRuns: Int? = null,
        maxRunsPerMinute: Int? = null,
    ): Pipeline

    suspend fun delete(id: UUID)

    /** The pipeline's node/edge graph as a [JsonElement] (drives the editor). */
    suspend fun graphAsJsonElement(pipeline: Pipeline): JsonElement

    /**
     * Decode a stored or snapshot graph [JsonElement] back into its typed [PipelineGraph]
     * (nodes + edges) via the aggregated node `SerializersModule` — the inverse of
     * [graphAsJsonElement]. Used to rebuild a runnable graph from a durable run's snapshot.
     */
    suspend fun decodeGraph(graph: JsonElement): PipelineGraph

    /**
     * The registered [NodeDescriptor] for [node] (its declared slots/ports), or `null` if its type is
     * not registered. Looked up by the node's `@SerialName` discriminator — used, e.g., to find a
     * node's declared error output ports when routing a backing-job failure.
     */
    suspend fun descriptorFor(node: PipelineNode): NodeDescriptor?

    /**
     * Validates [graph] against the registered node types (the same decode [save] performs before
     * persisting). Returns a human-readable error message, or `null` when the graph is valid.
     */
    suspend fun validateGraph(graph: JsonElement): String?
}
