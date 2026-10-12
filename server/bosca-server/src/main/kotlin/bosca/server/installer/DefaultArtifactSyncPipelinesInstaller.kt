package bosca.server.installer

import bosca.artifacts.model.ArtifactSyncTarget
import bosca.artifacts.model.ArtifactTagPublished
import bosca.artifacts.model.ArtifactCompleted
import bosca.artifacts.model.ArtifactPublicationTarget
import bosca.artifacts.pipeline.ArtifactPublicationGetDestinations
import bosca.artifacts.pipeline.ArtifactPublicationNode
import bosca.artifacts.pipeline.ArtifactSyncGetDestinations
import bosca.artifacts.pipeline.ArtifactSyncNode
import bosca.artifacts.service.ArtifactSyncService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.builtin.ForEach
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID

/** Seeds editable Docker and raw artifact sync pipelines, preserving operator graphs on reinstall. */
class DefaultArtifactSyncPipelinesInstaller(private val pipelines: PipelineService) : PackageInstaller {
    override val version = "1.1.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existing = pipelines.getAll().associateBy { it.name }
        val body = existing[BODY_NAME] ?: save(Pipeline(
            id = UUID.NIL, name = BODY_NAME,
            description = "Copy one selected Docker tag to its configured GHCR destination.",
            acceptedInputType = ArtifactSyncTarget::class.qualifiedName.orEmpty(),
            nodes = listOf(
                InputNode("input", acceptedType = ArtifactSyncTarget::class.qualifiedName.orEmpty()),
                ArtifactSyncNode("sync"), OutputNode("output"),
            ),
            edges = listOf(
                PipelineEdge("input-sync", "input", "sync", targetPort = "target"),
                PipelineEdge("sync-output", "sync", "output"),
            ),
        ))
        if (TRIGGER_NAME !in existing) save(Pipeline(
            id = UUID.NIL, name = TRIGGER_NAME,
            description = "Sync published Docker tags to enabled artifact destinations. Edit or disable this pipeline to change syncing.",
            acceptedInputType = ArtifactTagPublished::class.qualifiedName.orEmpty(), triggered = true,
            nodes = listOf(
                InputNode("input", acceptedType = ArtifactTagPublished::class.qualifiedName.orEmpty()),
                ArtifactSyncGetDestinations("destinations"),
                ForEach("each", pipelineId = body.id, continueOnError = true), OutputNode("output"),
            ),
            edges = listOf(
                PipelineEdge("input-destinations", "input", "destinations", targetPort = "artifact"),
                PipelineEdge("destinations-each", "destinations", "each"),
                PipelineEdge("each-output", "each", "output"),
            ),
        ))
        val publicationBody = existing[PUBLICATION_BODY_NAME] ?: save(Pipeline(
            id = UUID.NIL, name = PUBLICATION_BODY_NAME,
            description = "Publish one completed raw artifact version to its configured GitHub release destination.",
            acceptedInputType = ArtifactPublicationTarget::class.qualifiedName.orEmpty(),
            nodes = listOf(
                InputNode("input", acceptedType = ArtifactPublicationTarget::class.qualifiedName.orEmpty()),
                ArtifactPublicationNode("publish"), OutputNode("output"),
            ),
            edges = listOf(
                PipelineEdge("input-publish", "input", "publish", targetPort = "target"),
                PipelineEdge("publish-output", "publish", "output"),
            ),
        ))
        if (PUBLICATION_TRIGGER_NAME !in existing) save(Pipeline(
            id = UUID.NIL, name = PUBLICATION_TRIGGER_NAME,
            description = "Publish completed raw artifact versions to enabled GitHub release destinations. Edit or disable this pipeline to change syncing.",
            acceptedInputType = ArtifactCompleted::class.qualifiedName.orEmpty(), triggered = true,
            nodes = listOf(
                InputNode("input", acceptedType = ArtifactCompleted::class.qualifiedName.orEmpty()),
                ArtifactPublicationGetDestinations("destinations"),
                ForEach("each", pipelineId = publicationBody.id, continueOnError = true), OutputNode("output"),
            ),
            edges = listOf(
                PipelineEdge("input-destinations", "input", "destinations", targetPort = "artifact"),
                PipelineEdge("destinations-each", "destinations", "each"),
                PipelineEdge("each-output", "each", "output"),
            ),
        ))
    }

    private suspend fun save(pipeline: Pipeline): Pipeline = pipelines.save(
        id = UUID.NIL, name = pipeline.name, description = pipeline.description,
        acceptedInputType = pipeline.acceptedInputType, triggered = pipeline.triggered, version = 0,
        graph = pipelines.graphAsJsonElement(pipeline),
    )

    companion object {
        const val NAME = "default-artifact-sync-pipelines"
        const val BODY_NAME = ArtifactSyncService.PUSH_PIPELINE_NAME
        const val TRIGGER_NAME = "Sync Published Docker Tags to GHCR"
        const val PUBLICATION_BODY_NAME = "Publish Raw Artifact to GitHub"
        const val PUBLICATION_TRIGGER_NAME = "Sync Completed Raw Artifacts to GitHub"
    }
}
