package bosca.pipelines.service

import bosca.pipelines.model.NodeExecutionRecord
import bosca.pipelines.model.RunStep
import bosca.pipelines.model.NodeMetrics
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunLog
import bosca.pipelines.model.PipelineRunLogWithName
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.json.JsonElement

/**
 * Owns the lifecycle of a pipeline *run* — both the durable, mutable run state ([PipelineRun], the
 * row a run is rebuilt from when it resumes) and the append-only run history ([PipelineRunLog]).
 *
 * Consumers that drive runs (e.g. the triggered-run job executor) talk to this service rather than
 * the run repositories directly, so run-recording policy (snapshotting, checkpointing, history) lives
 * in one place.
 */
interface PipelineRunService : Service {

    /**
     * Begin a durable run and drive it: enforce the pipeline's concurrency / rate caps
     * — if [Pipeline.maxConcurrentRuns] in-flight runs already exist, or
     * [Pipeline.maxRunsPerMinute] have started in the last rolling minute, the run is **shed** (`null`
     * returned, reason logged); a soft cap, since the count and the insert are not one atomic step.
     * Otherwise snapshot [pipeline]'s graph and the seed [input] (its encoded value + origin type),
     * record the run [PipelineRunStatus.RUNNING], and evaluate it to completion or its first suspend
     * (recording the terminal outcome + run-history entry, or persisting a checkpoint + scheduling the
     * backing work). Snapshotting the graph means a later edit to the stored pipeline can never change
     * a run already in flight. [inputCreated] is the originating occurrence time, threaded to context.
     *
     * A non-null [authentication] marks an **on-demand** run (manual / API): the initiating request
     * thread is not itself a run job, so the run is enqueued to drive durably via its own run job —
     * rather than driven inline, which would orphan it in SUSPENDED at the first suspending node (the
     * backing job having no run job to resume it) — and the call blocks briefly for a fast run to
     * settle. Inside an active database transaction it returns without waiting, since job delivery is
     * deferred until the outermost transaction commits. Its [authentication] principal is captured on
     * the run row so the whole durable run (first pass, backing work, every resume) executes under
     * the caller's security context — security
     * traverses end to end, not just the first pass. A `null` [authentication] is a triggered/scheduled
     * run, already executing inside its run job; it is driven inline under the pipelines service account
     * (as every resume of such a run does). Returns the run handle — OK with an Output if it finished in
     * one pass, SUSPENDED/RUNNING if it parked or is still in flight on the queue — or `null` if shed.
     *
     * Failures are recorded (terminal `FAILED` + history), not rethrown, so one bad pipeline cannot
     * poison the queue; cancellation always propagates.
     */
    suspend fun start(
        pipeline: Pipeline,
        input: PipelineValue,
        eventName: String,
        inputCreated: OffsetDateTime,
        authentication: AuthenticationContext? = null,
    ): PipelineRun?

    /** Record a run's terminal outcome ([PipelineRunStatus.OK] / [PipelineRunStatus.FAILED] / [PipelineRunStatus.CANCELLED]). */
    suspend fun complete(runId: UUID, status: PipelineRunStatus, error: String?)

    /** Durable run state by id, or `null` when absent or soft-deleted. */
    suspend fun get(runId: UUID): PipelineRun?

    /** Records a failed backing-work attempt without ending the suspended run or changing retry policy. */
    suspend fun recordNodeAttemptFailure(runId: UUID, nodeId: String, error: String)

    /** In-flight durable runs (running or suspended), newest activity first — for operator visibility. */
    suspend fun listActive(offset: Long, limit: Int): List<PipelineRun>

    /** The child runs a For Each / Run Pipeline node spawned under [parentRunId]'s [nodeId], in item order. */
    suspend fun listChildren(parentRunId: UUID, nodeId: String): List<PipelineRun>

    /**
     * The dead-letter queue: runs that terminally FAILED, newest first, for
     * operator triage. A poison run lands here instead of being lost; [replay] re-runs it after a fix.
     */
    suspend fun listDeadLetter(offset: Long, limit: Int): List<PipelineRun>

    /**
     * Restart a finished run: [start] a fresh durable run from the source
     * run's seed input, against the **current** pipeline definition (so an edit that fixes the failure
     * applies), falling back to the source's graph snapshot if the pipeline was since deleted. Returns
     * the new run, or `null` when the source run no longer exists (or the fresh run was shed by caps).
     * Recovery for dead-lettered (failed) runs.
     */
    suspend fun restart(runId: UUID): PipelineRun?

    /**
     * Remove a terminal run from the dead-letter queue (operator action): soft-delete it so it drops
     * out of the dead-letter list and the run views. Only a terminal run is removable — an in-flight
     * run must be cancelled first. Returns `true` when a row was deleted, `false` when the run is
     * missing or still running.
     */
    suspend fun delete(runId: UUID): Boolean

    /**
     * Apply the retention policy: soft-delete terminal run-state rows past
     * [bosca.pipelines.configuration.PipelinesRuntimeConfiguration.runStateRetentionDays] and delete
     * run-history rows past `runHistoryRetentionDays`. Returns the total rows reaped. Called by the
     * scheduled retention sweep.
     */
    suspend fun purgeExpiredRuns(): Int

    /**
     * Fan a durable ForEach out into one child durable run of the body
     * pipeline per item: open the iteration aggregation (keyed by [parentRunId]/[nodeId], expecting
     * [items].size results), then start + drive each child linked to its item index. A child that
     * completes reports its output back; a child that suspends parks and reports when it later
     * resumes. When all items finish, the parent's ForEach await is signalled with the joined array
     * (or failed, per [continueOnError]). Called from the ForEach node's suspend thunk.
     *
     * [maxConcurrency] bounds how many children are in flight at once — a suspended child still holds
     * its slot, which is what makes `1` a real sequential guarantee for dependency-ordered items: the
     * first N children start with the suspend and item `i + N` starts only when item `i` reports.
     * `<= 0` keeps the legacy fan-everything-at-once behavior.
     */
    suspend fun startIteration(
        parentRunId: UUID,
        nodeId: String,
        bodyPipelineId: UUID,
        items: List<JsonElement>,
        continueOnError: Boolean,
        inputCreated: OffsetDateTime,
        runJob: bosca.sharedqueue.jobs.Job? = null,
        runJobId: UUID? = null,
        maxConcurrency: Int = 0,
    )

    /**
     * Run another pipeline as a **durable child** of a parent run: start one
     * child run of [bodyPipelineId] seeded with [input], linked to [parentRunId]/[nodeId]. The parent
     * (parked on the node's await) resumes with the child's Output value when the child completes, or
     * fails if the child fails. This is what lets a `RunPipeline` node compose a child that itself
     * suspends (a timer) without the parent giving up durability. Called from the node's
     * suspend thunk.
     */
    suspend fun runChildPipeline(
        parentRunId: UUID,
        nodeId: String,
        bodyPipelineId: UUID,
        input: PipelineValue,
        inputCreated: OffsetDateTime,
        runJob: bosca.sharedqueue.jobs.Job? = null,
        runJobId: UUID? = null,
    )

    /**
     * Process an already-created run by id — a RunPipeline/ForEach child started as a child job of the
     * parent run job. Reconstructs the run's pipeline + seed input from the
     * stored row and evaluates it to completion or its first suspend, exactly like the drive in [start];
     * the child reports its output to the parent on completion the usual way. No-op if the run is missing
     * or already terminal (idempotent under the child-run job's at-least-once redelivery).
     */
    suspend fun process(runId: UUID)

    /** A run's per-node execution timeline, in execution order. */
    suspend fun nodeTimeline(runId: UUID): List<NodeExecutionRecord>

    /**
     * The run's step-oriented progress view: one row per Status node and per HUMAN
     * wait (Approval Gate / Wait for Input) in graph order, recursing into For Each / Run Pipeline
     * children — per-item rows once child runs exist, and the child pipeline's steps pre-shown PENDING
     * before the fan-out is reached. Engine plumbing nodes never appear.
     */
    suspend fun steps(runId: UUID): List<RunStep>

    /**
     * The step plan of whatever triggered pipeline(s) accept [eventName] — every row PENDING, child
     * pipelines pre-shown. What a release dashboard shows BEFORE any run exists.
     */
    suspend fun stepsForTrigger(eventName: String): List<RunStep>

    /**
     * The nodes [runId] is ACTUALLY parked on, drilled through fan-outs into child runs — the honest
     * answer to "what is this run waiting on", labelled per item.
     */
    suspend fun awaitingNodes(runId: UUID): List<bosca.pipelines.model.RunAwaitingNode>

    /** Per-node aggregate metrics across a pipeline's runs, busiest node first. */
    suspend fun nodeMetrics(pipelineId: UUID): List<NodeMetrics>

    /** Append a finished-run entry to the run history. */
    suspend fun recordLog(log: PipelineRunLog): PipelineRunLog

    /** A pipeline's run history (newest first), each row joined with the pipeline name — for operator listings. */
    suspend fun listRunHistory(pipelineId: UUID, offset: Long, limit: Int): List<PipelineRunLogWithName>

    /** Run history across all pipelines (newest first), each joined with its pipeline name — for the global runs view. */
    suspend fun listAllRunHistory(offset: Long, limit: Int): List<PipelineRunLogWithName>

    /**
     * Resume a suspended run after the work node [nodeId] parked on finished. On [succeeded] the
     * node's output is taken from the result store (its staged value), recorded into the checkpoint,
     * and evaluation continues (to completion or the next suspend); otherwise the run fails with
     * [error]. Idempotent under at-least-once redelivery — a duplicate (node already recorded, run no
     * longer awaiting it, or already terminal) is a no-op.
     *
     * [runJob] is the run's already-locked job when this resume is driven from inside that job's lock
     * (its `onChildStatusChanged` drive hook) — the next backing job is attached to it directly. `null`
     * for a manual/external resume, which loads the run job by id to attach.
     */
    suspend fun resume(
        runId: UUID,
        nodeId: String,
        succeeded: Boolean,
        error: String?,
        runJob: bosca.sharedqueue.jobs.Job? = null,
    )

    /**
     * Cancel a run: mark it [PipelineRunStatus.CANCELLED] with [reason] and record it in history. A
     * resume subsequently delivered for the run is ignored (it is already terminal). No-op if the run
     * is missing or already terminal.
     */
    suspend fun cancel(runId: UUID, reason: String?)

    /**
     * Fail every run stuck [PipelineRunStatus.SUSPENDED] past the configured max lifetime — a backstop
     * for backing work that never resolves (the scheduled sweeper calls this). Returns how many runs
     * were swept.
     */
    suspend fun sweepStuckSuspended(): Int

    companion object {
        /** Channel-name prefix for the per-run live-event stream. */
        const val RUN_EVENT_CHANNEL_PREFIX = "bosca.pipelines.run."

        /**
         * The per-run PubSub channel carrying live [bosca.pipelines.model.PipelineRunUpdate]s for [runId].
         * Per-run, so a subscriber filters by what it subscribes to rather
         * than receiving every run's events; shared so the publisher (run service) and the subscriber
         * (GraphQL subscription resolver) agree on the channel without coupling.
         */
        fun runEventChannel(runId: UUID): String = "$RUN_EVENT_CHANNEL_PREFIX$runId"
    }
}
