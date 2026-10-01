@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * A live status change for a durable run, broadcast over PubSub so the run / timeline views can
 * update without polling. Two shapes, discriminated by [nodeId]:
 *  - **run-level** ([nodeId] == null): [runStatus] is the run's new status — RUNNING on start/resume,
 *    SUSPENDED when it parks, OK / FAILED / CANCELLED when it terminates.
 *  - **node-level** ([nodeId] set): [nodeStatus] is that node's outcome (OK / FAILED / SUSPENDED),
 *    with the [port] its value left on and [error] when it failed — mirroring the node's timeline record.
 *
 * Published per-run on [bosca.pipelines.service.PipelineRunService.runEventChannel] AFTER the gating DB
 * write commits, so a subscriber never observes a status the run did not durably reach. A runtime
 * carrier only — never persisted.
 */
@Serializable
data class PipelineRunUpdate(
    @Contextual
    val runId: UUID,
    /** The run's new status on a run-level transition; `null` for a node-level update. */
    val runStatus: PipelineRunStatus? = null,
    /** The node whose status changed; `null` for a run-level transition. */
    val nodeId: String? = null,
    /** The node's outcome on a node-level update; `null` for a run-level transition. */
    val nodeStatus: NodeExecutionStatus? = null,
    /** The output port the node's value left on (routing / error), when applicable. */
    val port: String? = null,
    /** Failure message when a node or the run failed. */
    val error: String? = null,
    @Contextual
    val at: OffsetDateTime = java.time.OffsetDateTime.now(),
)
