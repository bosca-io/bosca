package bosca.artifacts.pipeline

import bosca.artifacts.model.*
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.ArtifactSyncService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.*
import bosca.pipelines.node.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Selects enabled destinations for a currently published tag without preparing or copying it. */
@PipelineNodeType(
    category = NodeCategory.FETCH, label = "Get Artifact Sync Destinations",
    description = "Get enabled destinations for a published Docker tag.",
    group = "Artifacts", subgroup = "Sync",
    inputs = [InputSlot(name = "artifact", kind = SlotKind.OBJECT, type = ArtifactTagPublished::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.ARRAY, type = ArtifactSyncTarget::class,
        description = "Image/destination pairs for For Each")],
)
@Serializable
@SerialName("artifactSyncGetDestinations")
class ArtifactSyncGetDestinations(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val event = ArtifactSyncGetDestinationsSerializer.deserialize(context, inputs).artifact
        val artifacts = provide<ArtifactRepositoryService>()
        val version = artifacts.getVersion(event.versionId)
            ?: return ArtifactSyncGetDestinationsSerializer.serialize(emptyList())
        require(version.repositoryId == event.repositoryId && version.version == event.manifestDigest) { "Published tag does not match its manifest" }
        val repository = artifacts.getRepository(version.repositoryId) ?: throw NoSuchElementException("Artifact repository not found")
        require(repository.type == ArtifactType.DOCKER.value) { "GHCR syncing requires a Docker repository" }
        val namespace = artifacts.getNamespace(repository.namespaceId) ?: throw NoSuchElementException("Artifact namespace not found")
        provide<ArtifactPermissionEvaluator>().verify(context.authentication, repository.type,
            namespace.name, repository.name, event.tagName, ArtifactAction.PUSH)
        if (artifacts.findTag(repository.id, event.tagName)?.manifestDigest != event.manifestDigest) {
            return ArtifactSyncGetDestinationsSerializer.serialize(emptyList())
        }
        val targets = provide<ArtifactSyncService>().destinations(repository.id).filter { it.enabled }.map {
            ArtifactSyncTarget(it.id, version.id, event.tagName, event.manifestDigest)
        }
        return ArtifactSyncGetDestinationsSerializer.serialize(targets)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue =
        ArtifactSyncGetDestinationsSerializer.serialize(emptyList())
}
