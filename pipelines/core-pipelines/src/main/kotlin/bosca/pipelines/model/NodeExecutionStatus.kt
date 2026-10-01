package bosca.pipelines.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Outcome of a single node execution within a run. One node can produce several of
 * these over its lifecycle — e.g. a suspendable node records [SUSPENDED] when it parks and then [OK]
 * (or [FAILED]) when its resume completes it, so the per-node timeline shows the whole story.
 *
 *  - [OK]        — the node produced its output (possibly on a routed port).
 *  - [FAILED]    — the node threw, hit an unhandled error port, or its backing work failed.
 *  - [SKIPPED]   — a wired node whose branch wasn't taken (no value reached it).
 *  - [SUSPENDED] — the node parked the run on out-of-band work (a job or timer).
 *
 * Stored as the native Postgres enum `pipelines.node_execution_status` (lowercase labels); the
 * [EnumMapper] bridges Kotlin ⇄ column, and the value is `@Serializable` so it projects through
 * GraphQL unchanged. Native-safe: no reflective serializer lookup.
 */
@DbMapper(NodeExecutionStatusMapper::class)
@Serializable
enum class NodeExecutionStatus {
    OK,
    FAILED,
    SKIPPED,
    SUSPENDED,
}

object NodeExecutionStatusMapper : EnumMapper<NodeExecutionStatus>({ NodeExecutionStatus.valueOf(it.uppercase()) })
