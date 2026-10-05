package bosca.pipelines.model

import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A stored, reusable pipeline: a declared accepted input type plus a graph of [PipelineNode]s and
 * [PipelineEdge]s. Exactly one node is the entry (an `InputNode`); an `OutputNode` (the result) is
 * optional. The same serialized form drives both the Vue Flow editor and the executor.
 *
 * [nodes] is a polymorphic list — (de)serialization requires the KSP-assembled node
 * `SerializersModule` (explicit serializers; no reflection).
 *
 * A pipeline can additionally be exposed as a REST endpoint (`POST/GET /api/v1/p/{key}`), mirroring
 * API scripts: [api] opts it in, [key] is its stable URL handle, and access follows the standard
 * permission model ([public] → anyone; otherwise group-based EXECUTE grants / admin override) via
 * `PipelinePermissionEvaluator`.
 */
@Serializable
data class Pipeline(
    override val id: UUID,
    val name: String,
    val description: String = "",
    /** Fully-qualified type the `InputNode` accepts; consumers filter pipelines by assignability. */
    val acceptedInputType: String,
    /** Free-form categorization labels — the UI groups and filters pipelines by these. */
    val tags: List<String> = emptyList(),
    /** When on, the pipeline runs automatically whenever its accepted event fires. */
    val triggered: Boolean = false,
    /** Stable URL handle for the REST endpoint (`/api/v1/p/{key}`); empty when not endpoint-exposed. */
    val key: String = "",
    /** When on, the pipeline is callable via its REST endpoint. */
    val api: Boolean = false,
    /** When on (and [api] is on), the endpoint is callable without an EXECUTE grant. */
    override val public: Boolean = false,
    /** Cron expression for a scheduled run, or null when not scheduled. */
    val schedule: String? = null,
    /** Max durable runs in flight at once; null/≤0 = unlimited. Excess triggers shed. */
    val maxConcurrentRuns: Int? = null,
    /** Max durable runs started per rolling minute; null/≤0 = unlimited. Excess triggers shed. */
    val maxRunsPerMinute: Int? = null,
    val nodes: List<PipelineNode> = emptyList(),
    val edges: List<PipelineEdge> = emptyList(),
    /** Editor-only visual group frames (see [NodeGroup]); ignored by the executor, round-tripped so a saved pipeline keeps its grouping. */
    val groups: List<NodeGroup> = emptyList(),
    val version: Long = 0,
    /** The PIPELINE_PROJECT Git repository this pipeline is serialized to, when linked. */
    val gitRepositoryId: UUID? = null,
    /** Repository-relative path of the pipeline's YAML file, when linked. */
    val gitPath: String? = null,
    /** Last Git sync error for this pipeline, or `null` when the last sync succeeded. */
    val lastSyncError: String? = null,
    val deletedAt: OffsetDateTime? = null,
) : PermissibleEntity<UUID> {

    @Transient
    override val publicContent: Boolean = false

    @Transient
    override val publicList: Boolean = false

    @Transient
    override val publicSupplementary: Boolean = false

    /** An API-callable pipeline is "published" for permission evaluation (mirrors scripts' enabled). */
    override val isPublished: Boolean
        get() = api

    @Transient
    override val isAdvertised: Boolean = false

    override val isDeleted: Boolean
        get() = deletedAt != null
}

/** The pipeline's entry node, or null for an (invalid) graph without one. */
val Pipeline.inputNode: InputNode? get() = nodes.firstOrNull { it is InputNode } as? InputNode

/** The pipeline's exit node, or null for a pure side-effect pipeline. */
val Pipeline.outputNode: OutputNode? get() = nodes.firstOrNull { it is OutputNode } as? OutputNode

/** A directed data edge from [source] node's [sourcePort] output to [target] node's [targetPort]. */
@Serializable
data class PipelineEdge(
    val id: String,
    val source: String,
    val target: String,
    val sourcePort: String? = null,
    val targetPort: String? = null,
)
