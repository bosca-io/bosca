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
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.service.EnvironmentService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * records that a pending environment deployment (from a Deploy or Promote node)
 * has actually landed: marks it DEPLOYED via [EnvironmentService.markDeployed]. This is the honest "it
 * is live now" transition, deliberately separate from creating the deployment — Deploy/Promote only
 * record intent (PENDING); place this node once the real outcome is known (a health check passed, an
 * approval, or the target-specific distribution step succeeded). Outputs the DEPLOYED deployment.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Mark Deployment Deployed",
    description = "Marks a pending environment deployment DEPLOYED, once it has actually landed.",
    group = "WorkOps",
    subgroup = "Environments",
    inputs = [
        InputSlot(
            name = "in", kind = SlotKind.OBJECT, type = EnvironmentDeployment::class, typeLabel = "Deployment",
            description = "The environment deployment to confirm — from a Deploy or Promote node.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.OBJECT, type = EnvironmentDeployment::class, typeLabel = "Deployment",
            description = "The deployment, now DEPLOYED.",
        ),
    ],
)
@Serializable
@SerialName("environment.markDeployed")
class MarkDeploymentDeployedNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val label = name.ifBlank { id }
        val principal = context.authentication.principal()
            ?: error("Mark Deployment Deployed node '$label' requires an authenticated principal")
        val principalId = principal.id
        val deployment = MarkDeploymentDeployedNodeSerializer.deserialize(context, inputs).`in`
        val deployed = provide<EnvironmentService>().markDeployed(deployment.id, principalId, deployment.version)
        return MarkDeploymentDeployedNodeSerializer.serialize(deployed)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject { put("action", "markDeploymentDeployed") })
        return inputs.first
    }
}
