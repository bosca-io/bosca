package bosca.artifacts.pipeline

import bosca.artifacts.model.*
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactPublicationService
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.*
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Gets matching publication destinations without finalizing the version or preparing publications. */
@PipelineNodeType(
    category = NodeCategory.FETCH, label = "Get Artifact Publication Destinations",
    description = "Get enabled publication destinations for a completed artifact.",
    group = "Artifacts", subgroup = "Publication",
    inputs = [InputSlot(name = "artifact", kind = SlotKind.OBJECT, type = ArtifactCompleted::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.ARRAY, type = ArtifactPublicationTarget::class,
        description = "Artifact/destination pairs for For Each")],
)
@Serializable
@SerialName("artifactPublicationGetDestinations")
class ArtifactPublicationGetDestinations(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val artifact = ArtifactPublicationGetDestinationsSerializer.deserialize(context, inputs).artifact
        val artifacts = provide<ArtifactRepositoryService>()
        val version = artifacts.getVersion(artifact.versionId)
            ?: return ArtifactPublicationGetDestinationsSerializer.serialize(emptyList())
        val repository = artifacts.getRepository(version.repositoryId) ?: throw NoSuchElementException("Artifact repository not found")
        val namespace = artifacts.getNamespace(repository.namespaceId) ?: throw NoSuchElementException("Artifact namespace not found")
        provide<ArtifactPermissionEvaluator>().verify(context.authentication, repository.type,
            namespace.name, repository.name, version.version, ArtifactAction.PUSH)
        val destinations = provide<ArtifactPublicationService>().destinations(repository.id).filter { it.enabled }
        if (destinations.isEmpty()) return ArtifactPublicationGetDestinationsSerializer.serialize(emptyList())
        return ArtifactPublicationGetDestinationsSerializer.serialize(destinations.map {
            ArtifactPublicationTarget(it.id, version.id, artifact.commitSha)
        })
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue =
        ArtifactPublicationGetDestinationsSerializer.serialize(emptyList())
}
