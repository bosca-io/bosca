package bosca.artifacts.pipeline

import bosca.artifacts.model.*
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactPublicationService
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.*
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Publishes one selected artifact/destination pair using ordinary pipeline retries. */
@PipelineNodeType(
    category = NodeCategory.ACTION, label = "Publish GitHub Release",
    description = "Push a completed artifact to one selected GitHub release destination.",
    group = "Artifacts", subgroup = "Publication",
    inputs = [InputSlot(name = "target", kind = SlotKind.OBJECT, type = ArtifactPublicationTarget::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING, description = "Publication ID")],
    settings = [SettingSlot(name = "prerelease", control = SettingControl.BOOLEAN, label = "Prerelease", default = "false")],
)
@Serializable
@SerialName("artifactPublication")
class ArtifactPublicationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
    val prerelease: Boolean = false,
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val target = ArtifactPublicationNodeSerializer.deserialize(context, inputs).target
        val artifacts = provide<ArtifactRepositoryService>()
        val version = artifacts.getVersion(target.versionId)
            ?: return ArtifactPublicationNodeSerializer.serialize("DELETED")
        val repository = artifacts.getRepository(version.repositoryId) ?: throw NoSuchElementException("Artifact repository not found")
        val namespace = artifacts.getNamespace(repository.namespaceId) ?: throw NoSuchElementException("Artifact namespace not found")
        provide<ArtifactPermissionEvaluator>().verify(context.authentication, repository.type,
            namespace.name, repository.name, version.version, ArtifactAction.PUSH)
        val publications = provide<ArtifactPublicationService>()
        val publication = publications.prepare(target.destinationId, version.id, target.commitSha, prerelease)
        publications.publish(publication.id)
        return ArtifactPublicationNodeSerializer.serialize(publication.id.toString())
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val target = ArtifactPublicationNodeSerializer.deserializePartial(context, inputs).target
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "artifactPublication")
            put("destinationId", target?.destinationId?.toString())
            put("versionId", target?.versionId?.toString())
            put("prerelease", prerelease)
        })
        return ArtifactPublicationNodeSerializer.serialize("PREVIEW")
    }
}
