package bosca.pipelines.service

import bosca.pipelines.PipelineContext
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.PipelineValue
import bosca.service.Service

/**
 * Runs a [Pipeline] graph: seeds the `InputNode` with the input value, threads [PipelineContext]
 * (with its mutable scratch) through every node, evaluates the graph in topological order with
 * concurrent fan-out and fan-in via multi-input nodes, and fires action nodes.
 *
 * Domain-agnostic: the consumer supplies the typed input (its runtime type assignable to the
 * pipeline's accepted type) and the [PipelineContext].
 */
interface PipelineExecutor : Service {

    /**
     * Evaluates [pipeline] and returns whether it [ExecutionResult.Completed] or parked
     * ([ExecutionResult.Suspended]).
     *
     * A fresh run passes [input] (the `InputNode`'s seed) and no [state]. A **resume** passes the
     * prior [state] (a checkpoint carried out on an earlier [ExecutionResult.Suspended]); evaluation
     * picks up from there and every node already in the checkpoint is skipped — never re-run — which
     * is what makes resume idempotent (action nodes don't re-fire). When [state] is supplied [input]
     * is ignored (the seed is already in the checkpoint), so resume callers may pass `null`.
     *
     * The executor persists nothing — it is domain-pure and repository-free. On a suspend the caller
     * (the durable run service) persists [ExecutionResult.Suspended.state] and marks the run
     * suspended *before* invoking the carried `enqueue`. Non-durable callers (inline, manual/API,
     * dry, nested `RunPipeline`) unwrap with [requireCompleted].
     */
    suspend fun execute(
        pipeline: Pipeline,
        input: PipelineValue?,
        context: PipelineContext,
        state: ExecutionState? = null,
    ): ExecutionResult
}
