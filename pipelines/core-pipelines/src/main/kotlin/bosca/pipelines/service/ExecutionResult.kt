package bosca.pipelines.service

import bosca.pipelines.node.PipelineValue

/**
 * A resumable snapshot of a run's progress:
 *  - [outputs] — the output of every node evaluated so far (a node present here — even with a `null`
 *    value — has run and will not run again).
 *  - [awaiting] — the ids of nodes currently parked on out-of-band work. The executor neither runs
 *    these nor treats them as ready, so a re-drive never re-fires a still-suspended node (which would
 *    double-enqueue its backing job).
 *  - [skipped] — the ids of nodes that were SKIPPED (a not-taken branch / a dependency that didn't
 *    deliver), as opposed to nodes that ran and produced a `null` output. Carried so the skip cascade
 *    (a node whose required input came from a skipped node skips too) survives a suspend/resume.
 *
 * Passed back into [PipelineExecutor.execute] to resume; carried out on [ExecutionResult.Suspended].
 */
data class ExecutionState(
    val outputs: Map<String, PipelineValue?>,
    val awaiting: Set<String> = emptySet(),
    val skipped: Set<String> = emptySet(),
)

/** A node that parked the run this evaluation: its id and its deferred enqueue. */
data class Parked(
    val nodeId: String,
    val enqueue: suspend () -> Unit,
)

/**
 * The outcome of one [PipelineExecutor.execute] call — either the run finished, or it is parked on
 * one or more out-of-band awaits.
 *
 * The executor returns this instead of persisting anything itself: it stays domain-pure and
 * repository-free, and the caller (the durable run service) decides how to checkpoint, mark
 * suspended, and schedule the backing work.
 */
sealed interface ExecutionResult {

    /** The graph ran to completion; [output] is the Output node's value, or `null` if there is none. */
    data class Completed(val output: PipelineValue?) : ExecutionResult

    /**
     * The run is parked. [state] is the full checkpoint to persist ([ExecutionState.outputs] plus the
     * complete [ExecutionState.awaiting] set — prior awaits still outstanding plus the new ones).
     * [newlyParked] are only the nodes that parked *this* evaluation; their [Parked.enqueue] schedules
     * the backing work and **must be invoked only after [state] + the suspended status are durably
     * persisted** (so a fast-completing job can't resume a run whose suspended state isn't written
     * yet). A resume that makes no further progress yields an empty [newlyParked] — nothing new to
     * enqueue, the run simply stays parked on its remaining awaits.
     */
    data class Suspended(
        val state: ExecutionState,
        val newlyParked: List<Parked>,
    ) : ExecutionResult
}

/**
 * The completed output, or fail loudly. For the genuinely **inline** contexts that have no run to
 * resume — a dry-run *simulation*, the analytics in-flow [PipelineService.run], and a node's
 * degrade-to-inline path when there is no durable `runId` (e.g. RunPipeline/ForEach inside a dry or
 * non-durable run). In all of these the suspendable nodes guard on `dryRun || runId == null` and never
 * actually park, so reaching [ExecutionResult.Suspended] here means a node tried to suspend with
 * nowhere to park — a bug, not a silent no-op.
 *
 * Durable contexts do NOT use this: triggered, scheduled, and now manual/API runs all drive through
 * [PipelineRunService] and handle [ExecutionResult.Suspended] by checkpointing + resuming.
 */
fun ExecutionResult.requireCompleted(): PipelineValue? = when (this) {
    is ExecutionResult.Completed -> output
    is ExecutionResult.Suspended -> error(
        "Pipeline suspended at node(s) ${state.awaiting.joinToString()}, but this is an inline context " +
            "with nowhere to park — only durable runs (triggered/scheduled/manual/API) can suspend and resume"
    )
}
