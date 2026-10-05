package bosca.workops.pipeline

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunResultStore
import bosca.workops.jobs.WaitForHealthyJob
import bosca.workops.jobs.enqueue
import bosca.workops.model.environment.EnvironmentDeployment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * parks the run until the inbound [EnvironmentDeployment] reports **HEALTHY**,
 * then passes it through. Place it between a Deploy (or Promote item) and anything that should only
 * happen once the rollout is genuinely serving — a promotion gate, Mark Deployed rollup, the next stage.
 *
 * The wait is a correlated [WaitForHealthyJob]: on each delivery the deployment's durable
 * health status is checked; when the deployment declares a `healthCheckUrl` the job actively probes it
 * (a 2xx records HEALTHY), otherwise it waits for something that can observe health (a Helm Status
 * node, the health-check mutation) to record it. A machine wait — the stuck-suspended sweep bounds it.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Wait for Healthy",
    description = "Parks the run until the environment deployment reports HEALTHY, then passes it through.",
    group = "WorkOps",
    subgroup = "Environments",
    inputs = [
        InputSlot(
            name = "in", kind = SlotKind.OBJECT, type = EnvironmentDeployment::class, typeLabel = "Deployment",
            description = "The environment deployment to watch — a Deploy node's output.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.OBJECT, type = EnvironmentDeployment::class, typeLabel = "Deployment",
            description = "The deployment, once it reported HEALTHY.",
        ),
        OutputSlot(
            name = "unhealthy", kind = SlotKind.ANY, error = true, typeLabel = "Unhealthy",
            description = "Routes here when the wait fails — wire it to a Rollback (its 'after' port), else the run fails.",
        ),
    ],
)
@Serializable
@SerialName("environment.waitHealthy")
class WaitForHealthyNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    // Parks on a purpose-built health-wait job: willSuspend + enqueue in doSuspend (the durable wait
    // pattern — never override run()).
    override val willSuspend: Boolean get() = true

    override suspend fun doSuspend(context: PipelineContext, inputs: NodeInputs) {
        val runId = context.runId ?: return
        val deployment = WaitForHealthyNodeSerializer.deserialize(context, inputs).`in`
        // Stage the pass-through output on the healthy port; a failed wait routes to the wired
        // `unhealthy` error port instead (e.g. into a Rollback's sequencing input).
        provide<PipelineRunResultStore>().put(runId, id, inputs.first?.encode(context.json) ?: JsonNull, port = HEALTHY_PORT)
        // KSP-generated typed enqueue: the correlated backing job as a child of the run job.
        WaitForHealthyJob(deployment.id).enqueue(context, id)
    }

    /**
     * Reached only when the run is not durable (no [PipelineContext.runId] to resume) — nothing to park
     * on, so pass the inbound deployment through, the same value a healthy resume would emit.
     */
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? =
        inputs.first?.onPort(HEALTHY_PORT)

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject { put("action", "waitForHealthy") })
        return inputs.first?.onPort(HEALTHY_PORT)
    }

    companion object {
        /** Port the deployment flows out on once healthy. */
        private const val HEALTHY_PORT = "out"
    }
}
