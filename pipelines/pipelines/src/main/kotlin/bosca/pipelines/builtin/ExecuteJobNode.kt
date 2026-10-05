package bosca.pipelines.builtin

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunResultStore
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action: enqueues the platform job named [jobName] with the node's inbound value as its
 * configuration. The per-job [JobConfigurationEnqueuer] is registered in DI under that name (the
 * same registry the scheduler enumerates), resolved here via native-safe `provide`. The inbound
 * [PipelineValue] (e.g. a `JsonataNode`'s output) is encoded to JSON via its own serializer and
 * handed off as the job config.
 *
 * Fire-and-forget by default — no output, downstream nodes are skipped. With [awaitCompletion] the
 * node becomes a **durable gate**: it parks the run ([NodeResult.Suspend]) and enqueues the job as a
 * **child of the run job**, stamping a [PipelineResumeCorrelation] into its context. When the backing
 * job finishes, its completion bubbles up to the run job, whose drive listener resumes this node —
 * passing the inbound value through on success and failing the run on the job's failure. There is
 * **no synchronous wait** — awaiting requires a durable run (a [PipelineContext.runId]); without one
 * the node errors rather than blocking a worker.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Execute Job",
    description = "Enqueues a background job with the inbound value as its configuration — optionally parks the run until the job finishes, failing the run if the job fails.",
    group = "Core",
    subgroup = "Actions",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Configuration",
            description = "The value enqueued as the job's configuration; with await on, this same value passes through on success.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "jobName", control = SettingControl.REFERENCE, reference = ReferenceSource.JOB,
            label = "Job", required = true, placeholder = "Pick a job…",
            description = "The inbound value becomes the job's configuration — shape it with a JSONata node first.",
        ),
        SettingSlot(
            name = "awaitCompletion", control = SettingControl.BOOLEAN, label = "Wait for the job to finish", default = "false",
            description = "On: the run parks until the job finishes — the inbound value passes through on success, a failed or timed-out job fails the run. Off: fire-and-forget — downstream nodes are skipped.",
        ),
        SettingSlot(
            name = "awaitTimeoutSeconds", control = SettingControl.INTEGER, label = "Wait timeout (seconds)", default = "600",
            placeholder = "600", visibleWhenSetting = "awaitCompletion", visibleWhenEquals = "true",
        ),
    ],
)
@Serializable
@SerialName("executeJob")
class ExecuteJobNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val jobName: String,
    /** Wait (by parking the run) for the job's terminal status instead of fire-and-forget. */
    val awaitCompletion: Boolean = false,
    /**
     * Reserved: a per-node await timeout. The durable await is bounded by the run service's
     * stuck-suspended sweep (a global max lifetime), not a per-node timer, so this is not currently
     * consulted; it is retained so pipelines stored with it still deserialize.
     */
    val awaitTimeoutSeconds: Long = 600,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    /**
     * Awaiting always parks the run and resumes when the backing child job's completion bubbles up to
     * the run job's drive listener — never a synchronous wait. Dry runs and fire-and-forget fall
     * through to [execute].
     */
    override suspend fun run(context: PipelineContext, inputs: NodeInputs): NodeResult {
        if (awaitCompletion && !context.dryRun) {
            val runId = context.runId
                ?: error("Execute Job node '${name.ifBlank { id }}': awaiting job '$jobName' requires a durable run")
            return suspendForJob(runId, context, inputs)
        }
        return NodeResult.Output(execute(context, inputs))
    }

    /**
     * Stash this gate's success-output (its inbound value — what it passes through on success) into
     * the result store, then enqueue the job carrying the run/node correlation in its context plus
     * the resume callback. The enqueue is deferred (it does not wait), so it can run after the
     * suspended checkpoint is durably persisted; the run resumes on the job's terminal status.
     */
    private fun suspendForJob(runId: UUID, context: PipelineContext, inputs: NodeInputs): NodeResult.Suspend {
        val inbound = inputs.first
        return NodeResult.Suspend {
            val params = inbound?.encode(context.json) ?: buildJsonObject { }
            // Always stage (JSON null when there is no inbound value) so the resume always finds the
            // staged output present — a genuinely missing object is then a real error, not "no output".
            provide<PipelineRunResultStore>().put(runId, id, inbound?.encode(context.json) ?: JsonNull)
            // The job is configured dynamically by name (not a compile-time @JobDefinition), so enqueue it
            // via the context's building blocks: correlate stamps the run/node return address on the
            // backing job; enqueue attaches it as a CHILD of the run job (keeping the run open) and queues
            // it. Its terminal status bubbles up to the run job, whose drive listener resumes this node —
            // no resume callback on the backing job itself.
            val enqueuer = provide<JobConfigurationEnqueuer>(name = jobName)
            val backing = enqueuer.prepare(params) { context.correlate(this, id) }
            context.enqueue(enqueuer.queue(), backing)
        }
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val params = inputs.first?.encode(context.json) ?: buildJsonObject { }
        if (context.dryRun) {
            context.trace?.recordAction(id, buildJsonObject {
                put("action", "enqueueJob")
                put("jobName", jobName)
                put("params", params)
                put("awaitCompletion", awaitCompletion)
            })
            // An awaiting node is a gate, not a sink — pass the value through so the dry run
            // traces the downstream subtree it would unblock.
            return if (awaitCompletion) inputs.first else null
        }
        // Fire-and-forget: awaiting is handled in run() by parking the run, never here.
        provide<JobConfigurationEnqueuer>(name = jobName).enqueue(params)
        return null
    }
}
