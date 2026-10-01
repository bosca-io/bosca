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
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.release.Release
import bosca.workops.service.EnvironmentService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * records the **intent** to promote: every version currently DEPLOYED in the
 * `source` [Environment] gets a new PENDING deployment on the `target` (staging → production, alpha track
 * → production track). It promotes nothing — the create / do / confirm triad for promotions is this node
 * (record intent), the Promote node (the real rollout through each project's channel adapter), and Mark
 * Deployment Deployed (confirm each landed target deployment). Use this node for channels with no
 * adapter yet.
 *
 * Both environments are typed and resolved upstream by Get Environment nodes; the optional `release` port
 * stamps the created deployments so the release's "what's where" view tracks them
 * ([EnvironmentService.deploymentsByRelease]). The move is gated by the promotion graph —
 * [EnvironmentService.createPromotionDeployment] refuses it unless the source is a configured promotion
 * source of the target. Outputs the new (PENDING) target [EnvironmentDeployment]s.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Create Environment Promotion",
    description = "Promotes the versions deployed in a source environment into a target environment, recording it against the release.",
    group = "WorkOps",
    subgroup = "Environments",
    inputs = [
        InputSlot(
            name = "source", kind = SlotKind.OBJECT, type = Environment::class, typeLabel = "From environment",
            description = "The environment to promote from — its currently-deployed versions are moved. From a Get Environment node.",
        ),
        InputSlot(
            name = "target", kind = SlotKind.OBJECT, type = Environment::class, typeLabel = "To environment",
            description = "The environment to promote into. Must list the source as a promotion source. From a Get Environment node.",
        ),
        InputSlot(
            name = "release", kind = SlotKind.OBJECT, type = Release::class, typeLabel = "Release", required = false,
            description = "The release to record the promotion against (its \"what's where\" view).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.ARRAY, type = EnvironmentDeployment::class, typeLabel = "Deployments",
            description = "The new target-environment deployments.",
        ),
    ],
)
@Serializable
@SerialName("environment.createPromotion")
class CreateEnvironmentPromotionNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val label = name.ifBlank { id }
        val principal = context.authentication.principal()
            ?: error("Create Environment Promotion node '$label' requires an authenticated principal")
        val principalId = principal.id
        val input = CreateEnvironmentPromotionNodeSerializer.deserialize(context, inputs)

        val created = provide<EnvironmentService>()
            .createPromotionDeployment(input.source.id, input.target.id, input.release?.id, principalId)
        return CreateEnvironmentPromotionNodeSerializer.serialize(created)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // A dry run traces whatever is wired so far, so missing required inputs must not fail it —
        // hence the lenient deserializePartial, not deserialize.
        val input = CreateEnvironmentPromotionNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(
            id,
            buildJsonObject {
                put("action", "promoteToEnvironment")
                put("from", input.source?.name ?: "")
                put("to", input.target?.name ?: "")
            },
        )
        return null
    }
}
