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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Copies a selected image through the pipeline engine's ordinary durable action and retries. */
@PipelineNodeType(
    category = NodeCategory.ACTION, label = "Sync Docker Image to GHCR",
    description = "Copy a published Docker tag to one selected GHCR destination.",
    group = "Artifacts", subgroup = "Sync",
    inputs = [InputSlot(name = "target", kind = SlotKind.OBJECT, type = ArtifactSyncTarget::class)],
    outputs = [OutputSlot(name = "out", kind = SlotKind.STRING, description = "Sync ID or SKIPPED")],
)
@Serializable
@SerialName("artifactSync")
class ArtifactSyncNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val target = ArtifactSyncNodeSerializer.deserialize(context, inputs).target
        val artifacts = provide<ArtifactRepositoryService>()
        val version = artifacts.getVersion(target.versionId) ?: return ArtifactSyncNodeSerializer.serialize("SKIPPED")
        val repository = artifacts.getRepository(version.repositoryId) ?: throw NoSuchElementException("Artifact repository not found")
        val namespace = artifacts.getNamespace(repository.namespaceId) ?: throw NoSuchElementException("Artifact namespace not found")
        provide<ArtifactPermissionEvaluator>().verify(context.authentication, repository.type,
            namespace.name, repository.name, target.tagName, ArtifactAction.PUSH)
        val syncing = provide<ArtifactSyncService>()
        val sync = syncing.prepare(target) ?: return ArtifactSyncNodeSerializer.serialize("SKIPPED")
        syncing.sync(sync.id)
        return ArtifactSyncNodeSerializer.serialize(sync.id.toString())
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val target = ArtifactSyncNodeSerializer.deserializePartial(context, inputs).target
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "artifactSync")
            put("destinationId", target?.destinationId?.toString())
            put("versionId", target?.versionId?.toString())
            put("tagName", target?.tagName)
        })
        return ArtifactSyncNodeSerializer.serialize("PREVIEW")
    }
}
