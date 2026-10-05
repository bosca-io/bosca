package bosca.pipelines.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Lifecycle of a **durable** pipeline run (the live, mutable run-state row — distinct from the
 * append-only `pipeline_run_log` history, which records one immutable row per finished run).
 *
 *  - [RUNNING]   — the run is actively evaluating its graph.
 *  - [SUSPENDED] — the run has parked, awaiting one or more out-of-band results (e.g. a job it
 *    enqueued). It will be resumed from its checkpoint when those results arrive. (Reached only in
 *    later phases; declared here so the durable state machine is complete from the start.)
 *  - [OK]        — the run finished successfully.
 *  - [FAILED]    — a node failed (or a slot/error-port violation aborted the run).
 *  - [CANCELLED] — the run was explicitly cancelled before reaching a terminal state.
 *
 * Stored as the native Postgres enum `pipelines.pipeline_run_status` (lowercase labels); the
 * [EnumMapper] bridges Kotlin ⇄ column, and the value is `@Serializable` so it projects through
 * GraphQL unchanged. Native-safe: no reflective serializer lookup.
 */
@DbMapper(PipelineRunStatusMapper::class)
@Serializable
enum class PipelineRunStatus {
    RUNNING,
    SUSPENDED,
    OK,
    FAILED,
    CANCELLED;

    /** A run that has reached a terminal state will not transition again. */
    val isTerminal: Boolean get() = this == OK || this == FAILED || this == CANCELLED
}

object PipelineRunStatusMapper : EnumMapper<PipelineRunStatus>({ PipelineRunStatus.valueOf(it.uppercase()) })
