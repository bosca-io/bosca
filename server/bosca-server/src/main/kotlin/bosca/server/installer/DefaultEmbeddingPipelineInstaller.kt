package bosca.server.installer

import bosca.content.metadata.events.MetadataSetReady
import bosca.content.metadata.pipeline.EmbedMetadataNode
import bosca.content.metadata.pipeline.MetadataEventToMetadataNode
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.builtin.GetIdNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodePosition
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID
import org.slf4j.LoggerFactory

/**
 * Seeds the platform's default **content embedding** pipeline: when a content item becomes ready
 * (`MetadataSetReady`), the engine runs `Input → Get Id → Get Metadata → Embed Metadata`, computing a
 * semantic embedding for the item and storing it in its pgvector column for the recommender's content
 * tower. The graph is data; the nodes are code — `Get Id` and `Get Metadata` are platform resolvers and
 * `Embed Metadata` is contributed by `content`.
 *
 * Best-effort: the Embed node is gated on `embedding.enabled` and never fails the run, so the pipeline
 * is cheap-inert when embeddings are disabled. Registered in the **server** composition — the only place
 * that sees the builtin `Get Id` node together with content's `Get Metadata` / `Embed Metadata` nodes.
 *
 * Idempotent: the pipeline is created only when none with its name exists yet, so an operator's edits or
 * deletion are never clobbered on re-install.
 */
class DefaultEmbeddingPipelineInstaller(
    private val pipelineService: PipelineService,
) : PackageInstaller {

    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        if (pipelineService.getAll().any { it.name == PIPELINE_NAME }) {
            log.info("default embedding pipeline '{}' already present; skipping", PIPELINE_NAME)
            return
        }
        val fqdn = MetadataSetReady::class.qualifiedName
            ?: error("event ${MetadataSetReady::class} has no qualified name")
        val pipeline = Pipeline(
            id = UUID.NIL,
            name = PIPELINE_NAME,
            description = DESCRIPTION,
            acceptedInputType = fqdn,
            triggered = true,
            nodes = listOf(
                InputNode(id = INPUT, acceptedType = fqdn, position = NodePosition(40.0, 140.0)),
                GetIdNode(id = GET_ID, position = NodePosition(260.0, 140.0)),
                MetadataEventToMetadataNode(id = GET, position = NodePosition(480.0, 140.0)),
                EmbedMetadataNode(id = EMBED, position = NodePosition(720.0, 140.0)),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = INPUT, target = GET_ID),
                PipelineEdge(id = "e2", source = GET_ID, target = GET),
                PipelineEdge(id = "e3", source = GET, target = EMBED),
            ),
        )
        log.info("creating default embedding pipeline '{}' for {}", PIPELINE_NAME, fqdn)
        pipelineService.save(
            id = UUID.NIL,
            name = PIPELINE_NAME,
            description = DESCRIPTION,
            acceptedInputType = fqdn,
            triggered = true,
            version = 0,
            graph = pipelineService.graphAsJsonElement(pipeline),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DefaultEmbeddingPipelineInstaller::class.java)

        const val NAME = "default-embedding-pipeline"
        private const val PIPELINE_NAME = "Content Embeddings"
        private const val INPUT = "input"
        private const val GET_ID = "getId"
        private const val GET = "get"
        private const val EMBED = "embed"
        private const val DESCRIPTION =
            "Default platform pipeline that computes a semantic embedding for content as it becomes ready, " +
                "storing it for recommendations. Edit or disable it to change embedding behavior."
    }
}
