@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.service

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.trigger.ExecuteNodeInJob
import bosca.pipelines.trigger.enqueue
import bosca.service.annotation.ServiceImplementation
import kotlin.uuid.ExperimentalUuidApi

/**
 * Default [NodeSuspensionService]: park a node by enqueuing an [ExecuteNodeInJob] that re-runs its
 * `execute()` durably. The node's live inbound values are encoded to JSON here (at suspend) so the
 * backing job needs no upstream re-evaluation. The generated `enqueue(context, nodeId)` stamps the
 * run/node correlation and attaches the job as a child of the run job, so its completion bubbles up to
 * the run-job drive listener and resumes the node — the same path a purpose-built backing job uses.
 */
@ServiceImplementation
class NodeSuspensionServiceImpl : NodeSuspensionService {

    override suspend fun suspendNode(context: PipelineContext, node: PipelineNode, inputs: NodeInputs) {
        val runId = context.runId ?: error("Suspending node '${node.id}' requires a durable run")
        ExecuteNodeInJob(
            runId = runId,
            nodeId = node.id,
            inputs = inputs.asMap().mapValues { it.value.encode(context.json) },
        ).enqueue(context, node.id)
    }
}
