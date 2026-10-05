package bosca.pipelines.service

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineNode
import bosca.service.Service

/**
 * Suspends a node by running its [PipelineNode.execute] inside a durable backing job.
 * This is what the default [PipelineNode.doSuspend] delegates to, so a node opts into
 * durability by flipping [PipelineNode.willSuspend] alone — it never has to author its own backing
 * job. Running `execute()` in a child of the run job means the job system's retry, timeout, and
 * failure→error-port routing apply for free, and the run parks (releasing the worker) until the work
 * finishes.
 *
 * Resolved via `provide<T>()` from [PipelineNode] (deserialized data, not constructor-injected), so
 * this is a [Service]. The implementation lives in the `pipelines` module (the engine), keeping the
 * `core-pipelines` contract free of the concrete job + run-state machinery.
 */
interface NodeSuspensionService : Service {

    /**
     * Park [node] of the durable run behind [context]: stage its captured [inputs] and enqueue a
     * backing job — correlated to the run/node and attached as a child of the run job — that re-runs
     * [PipelineNode.execute] and stages the output the resume will promote. Requires a durable run
     * ([PipelineContext.runId] non-null); a node only reaches here when the run can suspend.
     */
    suspend fun suspendNode(context: PipelineContext, node: PipelineNode, inputs: NodeInputs)
}
