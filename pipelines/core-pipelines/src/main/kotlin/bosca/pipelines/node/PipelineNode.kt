package bosca.pipelines.node

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.model.RetryPolicy
import bosca.pipelines.service.NodeSuspensionService
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A node in a pipeline graph.
 *
 * Open, polymorphic and `@Serializable`: each concrete node type is a subclass carrying its own
 * typed settings, registered into the polymorphic `SerializersModule` with its **explicit**
 * serializer by KSP — never reflective subclass discovery (GraalVM-native safe).
 *
 * Behavior lives on the node: [execute] consumes this node's inbound [PipelineValue]s under the
 * run's [PipelineContext] and returns its output as a [PipelineValue] (value + serializer), or
 * `null` if it has no output (e.g. an async enqueue). Because nodes are deserialized data they are
 * not constructor-injected; [execute] resolves any services it needs via the native-safe
 * `provide<T>()` registry lookup.
 *
 * The executor invokes [run], not [execute]. The default [run] simply wraps [execute] as
 * [NodeResult.Output], so ordinary synchronous nodes implement only [execute] and never see
 * [NodeResult]. A node that defers to an out-of-band job and wants the run to **suspend** (park and
 * release the worker, resuming when that job finishes) overrides [run] and may
 * return [NodeResult.Suspend].
 */
@Serializable
abstract class PipelineNode {
    abstract val id: String

    /** Operator-given display name; empty = show the node type's label. */
    abstract val name: String

    /** Operator-given note about what this node does in this pipeline. */
    abstract val description: String

    abstract val position: NodePosition

    /**
     * Optional per-node retry policy; `null` = run once. Cross-cutting reliability
     * config shared by every node type, so it lives on the base. Declared as a `var` body property
     * (not a constructor param) so it applies uniformly without every subclass re-declaring it —
     * kotlinx assigns it on deserialize; it is set once from the stored graph and only read at run
     * time. (It is NOT carried by data-class `copy()` — re-decode the graph rather than copy nodes.)
     */
    var retry: RetryPolicy? = null

    /** Optional per-node timeout in seconds; `null` = no timeout. See [retry] for the `var` rationale. */
    var timeoutSeconds: Long? = null

    /**
     * Optional rollback pipeline: when this (side-effecting) node
     * completes successfully and the run later terminates non-OK, the run service runs this pipeline —
     * with this node's output as its input — to undo the side effect. Completed rollback-enabled nodes are
     * rolled back in reverse order (saga rollback). `null` = no rollback. See [retry] for the `var`
     * body-property rationale; the rollback pipeline must run synchronously (it cannot suspend).
     */
    @Contextual
    var rollbackPipeline: UUID? = null

    /**
     * Whether this node parks the run on out-of-band work instead of producing its value inline. A
     * node flips this to `true` to get durability for free: in a durable run [run] returns
     * [NodeResult.Suspend] and [doSuspend] runs [execute] inside a child of the run job (retry,
     * timeout, error-port routing all come from the job system). Default `false` — the node is
     * synchronous and [execute] produces its value in line.
     */
    open val willSuspend: Boolean get() = false

    /**
     * What this node produces in a dry run. A pure [TransformNode] runs [execute] (it has no side
     * effect to skip, so its real output flows into the trace and on to downstream nodes); a
     * side-effecting [ActionNode] inherits the default (unsupported) and overrides this to trace the
     * would-be effect and pass a value through without performing it.
     */
    protected open suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? = error("Dry run not supported")

    /** The node's actual work: produce its output [PipelineValue] (or `null`), performing any side effect in line. */
    protected abstract suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue?

    /**
     * Park the run on this node's work. The default ([willSuspend] = true with no override) hands
     * [execute] to the [NodeSuspensionService], which runs it inside a durable child of the run job —
     * so a node gets suspend/retry simply by setting [willSuspend], never authoring a backing job.
     * Override only when a node has a purpose-built job to enqueue instead.
     */
    protected open suspend fun doSuspend(context: PipelineContext, inputs: NodeInputs) {
        provide<NodeSuspensionService>().suspendNode(context, this, inputs)
    }

    /**
     * The executor's entry point. Default: run [execute] synchronously and return its value as
     * [NodeResult.Output]. Override to support suspension (return [NodeResult.Suspend]); such a node
     * should fall back to a synchronous [NodeResult.Output] when the run is not durable
     * ([PipelineContext.runId] is `null`), since there is nothing to resume.
     */
    open suspend fun run(context: PipelineContext, inputs: NodeInputs): NodeResult {
        if (context.dryRun) return NodeResult.Output(dryRun(context, inputs))
        if (willSuspend && context.runId != null) {
            return NodeResult.Suspend { doSuspend(context, inputs) }
        }
        return NodeResult.Output(execute(context, inputs))
    }

}

/** A node that transforms data and produces an output [PipelineValue]. */
@Serializable
abstract class TransformNode : PipelineNode() {
    /** A transform is pure, so a dry run runs it for real — its output flows into the trace and downstream. */
    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? = execute(context, inputs)
}

/**
 * A node that performs a side effect. Output is **optional** — most actions return `null`, but some
 * (e.g. an in-process `ExecuteScript`) return a [PipelineValue] consumable downstream in the same run.
 */
@Serializable
abstract class ActionNode : PipelineNode()
