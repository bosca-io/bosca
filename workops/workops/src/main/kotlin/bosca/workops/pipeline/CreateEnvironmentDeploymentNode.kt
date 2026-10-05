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
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.environment.DeployInput
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.release.Release
import bosca.workops.service.EnvironmentService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * records a **pending** deployment of an [ArtifactPublication] into a release
 * [Environment]. Everything it needs is typed and resolved upstream: the `artifact` port supplies the
 * publication (its project + version), the `environment` port the target (resolved by a Get Environment
 * node), and the optional `release` port the release to stamp — so the release's "what artifact/version
 * is in what environment" view tracks it ([EnvironmentService.deploymentsByRelease]).
 *
 * It does NOT mark the deployment DEPLOYED — this node only *records the intent*; it has no idea whether
 * the artifact actually landed on the environment's external channel (a Play track, TestFlight, an API
 * slot — see [Environment] `targetType`), which is a separate target-specific push. Once that outcome is
 * known, a Mark Deployment Deployed node (or the distribution step) flips it to DEPLOYED / FAILED.
 * Outputs the created (PENDING) [EnvironmentDeployment].
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Create Environment Deployment",
    description = "Deploys an artifact publication into a release environment, recording it against the release.",
    group = "WorkOps",
    subgroup = "Environments",
    inputs = [
        InputSlot(
            name = "artifact", kind = SlotKind.OBJECT, type = ArtifactPublication::class, typeLabel = "Artifact",
            description = "The artifact publication to deploy (its project + version). From an Associate Artifact node.",
        ),
        InputSlot(
            name = "environment", kind = SlotKind.OBJECT, type = Environment::class, typeLabel = "Environment",
            description = "The target environment. From a Get Environment node.",
        ),
        InputSlot(
            name = "release", kind = SlotKind.OBJECT, type = Release::class, typeLabel = "Release", required = false,
            description = "The release to record this deployment against (its \"what's where\" view).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.OBJECT, type = EnvironmentDeployment::class, typeLabel = "Deployment",
            description = "The recorded environment deployment (DEPLOYED).",
        ),
    ],
)
@Serializable
@SerialName("environment.createDeployment")
class CreateEnvironmentDeploymentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val label = name.ifBlank { id }
        val principal = context.authentication.principal()
            ?: error("Create Environment Deployment node '$label' requires an authenticated principal")
        val principalId = principal.id
        val input = CreateEnvironmentDeploymentNodeSerializer.deserialize(context, inputs)

        val deployment = provide<EnvironmentService>().createDeployment(
            DeployInput(
                environmentId = input.environment.id,
                projectId = input.artifact.projectId,
                versionId = input.artifact.versionId,
                releaseId = input.release?.id,
                artifactPublicationId = input.artifact.id,
            ),
            principalId,
        )
        // Deliberately NOT marked DEPLOYED — this node records the intent; the real outcome is recorded
        // later (a Mark Deployment Deployed node, or the target-specific distribution step).
        return CreateEnvironmentDeploymentNodeSerializer.serialize(deployment)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // A dry run traces whatever is wired so far, so missing required inputs must not fail it —
        // hence the lenient deserializePartial.
        val input = CreateEnvironmentDeploymentNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(
            id,
            buildJsonObject {
                put("action", "deployToEnvironment")
                put("environment", input.environment?.name ?: "")
            },
        )
        return null
    }
}
