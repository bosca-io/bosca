package bosca.pipelines

import bosca.pipelines.node.RollbackSink
import bosca.pipelines.node.NodeExecutionSink
import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.jobQueueOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Ambient state threaded to every node in a single pipeline run.
 *
 * The *value* flows along the graph's edges (output → input → …); this context carries the
 * cross-cutting concerns any node might need regardless of where it sits in the graph:
 *  - [authentication] — the principal the run acts as, so permissioned nodes (e.g. a Profile fetch
 *    or `SendEmail`) call their services under the right identity.
 *  - [json] — the DI-provided [Json] for native-safe `Serializable` ↔ `JsonElement` bridging.
 *  - [inputCreated] — when the occurrence that started this run happened. For triggered runs this
 *    is the event's fire time (carried through the dispatch jobs, so queue latency doesn't shift
 *    it); for inline/dry runs it defaults to construction time.
 *  - [attributes] — a mutable scratch map for out-of-band communication between nodes (e.g. a fetch
 *    node stashing a resolved entity for a later node to reuse instead of re-fetching).
 *  - [dryRun]/[trace] — dry-run mode: action nodes skip their side effects and record what they
 *    *would* have done into [trace]; the executor records every node's output there.
 *  - [invocationStack] — the chain of pipeline ids currently executing above (and including) this
 *    run, appended to by the executor itself as each pipeline starts. It guards cross-pipeline
 *    recursion (the graph validator can't see across stored pipelines): a pipeline already on the
 *    chain — or a chain deeper than the executor's maximum nesting depth — aborts the run.
 *  - [runId] — the durable run this evaluation belongs to ([bosca.pipelines.model.PipelineRun.id]),
 *    or `null` for non-durable runs (inline analytics, manual/API, dry). A suspendable node uses it
 *    to stamp the run identity onto the work it enqueues, so completion can resume the right run; a
 *    `null` runId means the run cannot suspend and such a node must fall back to synchronous output.
 *
 * The engine is domain-agnostic: no event / trigger / analytics concepts appear here. A consumer
 * (scripting triggers, analytics, …) builds the context and feeds the typed input value separately.
 *
 * The maps are [ConcurrentHashMap]-backed because a fan-out stage runs its nodes concurrently
 * against this single shared context; branches that write should use distinct keys.
 */
class PipelineContext(
    val authentication: AuthenticationContext,
    val json: Json,
    val dryRun: Boolean = false,
    val trace: DryRunTrace? = null,
    val inputCreated: OffsetDateTime = OffsetDateTime.now(),
    val invocationStack: List<UUID> = emptyList(),
    val runId: UUID? = null,
    /**
     * The id of the **run's job** ([bosca.pipelines.model.PipelineRun.runJobId]) — the platform job
     * driving this durable run. A suspendable node attaches its backing-work job to this job as a
     * child (so the run job stays open until that work completes). `null` for non-durable runs, or a
     * durable run not driven by a job; a node then just enqueues its backing work unparented.
     */
    val runJobId: UUID? = null,
    /**
     * The **already-locked** run-job object, present only while the run is being driven from inside the
     * run job's own lock — the initial drive (the run job is executing) or a resume driven from the run
     * job's `onChildStatusChanged` (the bubble-up holds the parent lock). A suspendable node attaches its
     * backing job to *this* object directly (it must not re-`getJob(runJobId)`, which would re-enter the
     * held lock). `null` for drives not inside the run job's lock (a manual/API resume); a node
     * then loads the run job by [runJobId] to attach.
     */
    val runJob: Job? = null,
    /**
     * Receives per-node execution events for a durable run's timeline. Set by the
     * run service for durable runs (a buffer it flushes after each drive); `null` for non-durable
     * runs (inline/manual/API/dry), which keep no per-node records.
     */
    val nodeSink: NodeExecutionSink? = null,
    /**
     * Receives a [RollbackSink] event whenever a node with a rollback pipeline completes.
     * Set by the run service for durable runs (buffered + flushed after
     * each drive); `null` for non-durable runs, which carry no saga rollback.
     */
    val rollbackSink: RollbackSink? = null,
) {
    val attributes: MutableMap<String, Any> = ConcurrentHashMap()

    fun correlate(job: Job, nodeId: String) {
        job.setContext(json, PipelineResumeCorrelation(runId ?: error("missing run id"), nodeId))
    }

    suspend fun enqueue(queue: JobQueue, child: Job, runOnParentComplete: Boolean = false): UUID {
        when {
            runJob != null -> runJob.addChild(child, runOnParentComplete = runOnParentComplete)
            // The run job lives on ITS queue, not necessarily [queue] (the child's target queue) —
            // runJobId was captured from the driving coroutine, so that coroutine's queue is the run
            // job's home. Looking it up on the child's queue would silently attach nothing.
            runJobId != null -> {
                val runJobQueue = jobQueueOrNull() ?: queue
                runJobQueue.getJob(runJobId) { parent ->
                    parent?.addChild(child, runOnParentComplete = runOnParentComplete)
                    parent?.let { runJobQueue.setJob(it) }
                }
            }
        }
        return queue.enqueue(child)
    }
}

/**
 * Per-node record of a traced (usually dry) run, keyed by node id:
 *  - [outputs] — each node's output encoded to JSON via its carried serializer (`null` output → absent).
 *  - [actions] — what a side-effecting node *would have done* (job name + config, email fields, …),
 *    recorded by the node itself when it skips execution under [PipelineContext.dryRun].
 *  - [errors] — the failing node's message when the run aborts.
 */
class DryRunTrace {
    val outputs: MutableMap<String, JsonElement> = ConcurrentHashMap()
    val actions: MutableMap<String, JsonElement> = ConcurrentHashMap()
    val errors: MutableMap<String, String> = ConcurrentHashMap()

    /** Nodes the executor skipped (with the reason) — e.g. their condition branch wasn't taken. */
    val skipped: MutableMap<String, String> = ConcurrentHashMap()

    // Recording helpers: callers hold a nullable `trace?`, so a single safe-call (`trace?.recordAction(…)`)
    // expresses "record only on a traced run" without a second null-check on the (always non-null) maps.
    fun recordOutput(nodeId: String, value: JsonElement) { outputs[nodeId] = value }
    fun recordAction(nodeId: String, value: JsonElement) { actions[nodeId] = value }
    fun recordError(nodeId: String, message: String) { errors[nodeId] = message }
    fun recordSkip(nodeId: String, reason: String) { skipped[nodeId] = reason }
}
