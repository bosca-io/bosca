package bosca.server.installer

import bosca.content.collection.events.CollectionCreated
import bosca.content.collection.events.CollectionDeleted
import bosca.content.collection.events.CollectionLanguageVariantDeleted
import bosca.content.collection.events.CollectionStateChangedComplete
import bosca.content.collection.events.CollectionSupplementaryAdded
import bosca.content.collection.events.CollectionSupplementaryUpdated
import bosca.content.collection.events.CollectionSupplementedUpdatedEvent
import bosca.content.collection.events.CollectionUpdated
import bosca.content.collection.pipeline.CollectionEventToCollectionNode
import bosca.content.metadata.events.MetadataCreated
import bosca.content.metadata.events.MetadataDeleted
import bosca.content.metadata.events.MetadataStateChangeComplete
import bosca.content.metadata.events.MetadataSupplementedUpdatedEvent
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.events.MetadataUploadCleared
import bosca.content.metadata.events.MetadataUploadedEvent
import bosca.content.metadata.pipeline.MetadataEventToMetadataNode
import bosca.content.transformations.pipeline.BuildCollectionSearchDocumentNode
import bosca.content.transformations.pipeline.BuildMetadataSearchDocumentNode
import bosca.content.transformations.pipeline.BuildProfileSearchDocumentNode
import bosca.events.Event
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.builtin.GetIdNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.service.PipelineService
import bosca.profile.profile.events.ProfileCreatedEvent
import bosca.profile.profile.events.ProfileDeletedEvent
import bosca.profile.profile.events.ProfileUpdatedEvent
import bosca.profile.profile.pipeline.ProfileEventToProfileNode
import bosca.search.pipeline.IndexDocumentNode
import bosca.search.pipeline.RemoveFromIndexNode
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.serialization.UUID
import kotlin.reflect.KClass
import org.slf4j.LoggerFactory

/**
 * Seeds the platform's default "keep the search index in sync" pipelines: one **triggered** pipeline
 * per content/collection/profile event that previously enqueued an index job via `@JobEvent`.
 *
 * An **index** event's pipeline is `Input(event) → Get Id → Get <Entity>`, then a fan-out: for each
 * content index (Default and Admin) a `Build <Entity> Search Document → Index Document` branch. The
 * Build node produces the index-specific search document (or a removal signal when the entity is not
 * visible for that index, applying the same visibility gate the executors used), and the Index
 * Document node writes it. Indexing — including *what goes into the document* — is now editable in
 * Studio: change a Build node's JSONata expression, or add/remove an index branch.
 *
 * A **delete** event's pipeline is `Input(event) → Get Id → Get <Entity> → Remove from Index`, which
 * removes the entity from every content index.
 *
 * Registered in the **server** composition (drives `/system/packages` and runs at server startup).
 *
 * Idempotent: a pipeline is created only when none with its name exists yet, so an operator's edits or
 * deletions are never clobbered on re-install.
 */
class DefaultIndexPipelinesInstaller(
    private val pipelineService: PipelineService,
) : PackageInstaller {

    override val version: String = "1.1.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existingNames = pipelineService.getAll().map { it.name }.toSet()
        for (spec in SPECS) {
            val name = spec.pipelineName
            if (name in existingNames) {
                log.info("default index pipeline '{}' already present; skipping", name)
                continue
            }
            val fqdn = spec.eventClass.qualifiedName
                ?: error("event ${spec.eventClass} has no qualified name")
            val pipeline = Pipeline(
                id = UUID.NIL,
                name = name,
                description = DESCRIPTION,
                acceptedInputType = fqdn,
                triggered = true,
                nodes = spec.nodes(fqdn),
                edges = spec.edges(),
            )
            log.info("creating default index pipeline '{}' for {}", name, fqdn)
            pipelineService.save(
                id = UUID.NIL,
                name = name,
                description = DESCRIPTION,
                acceptedInputType = fqdn,
                triggered = true,
                version = 0,
                graph = pipelineService.graphAsJsonElement(pipeline),
            )
        }
    }

    /** One default index/remove pipeline to seed: which event fires it, which Get node resolves the entity. */
    private class Spec(
        val eventClass: KClass<out Event>,
        val entity: String,
        val label: String,
        val remove: Boolean,
        private val getNode: (String, NodePosition) -> PipelineNode,
        /**
         * Builds the entity-specific Build Search Document node for a target index; null for remove
         * specs. `admin` relaxes the visibility toggles so the Admin index branch also indexes
         * unpublished/non-public content.
         */
        private val buildNode: ((id: String, position: NodePosition, index: String, admin: Boolean) -> PipelineNode)?,
        /** Public/searchable branch target; profiles use their dedicated index instead of Default. */
        private val primaryIndex: String = SearchDocumentPipeline.DEFAULT_INDEX,
        /**
         * Whether the Index Document nodes should replace the entity's existing documents before
         * indexing — true for collections (one entity → many per-variant documents) so a variant that
         * is no longer present is cleared; unnecessary for single-document metadata/profile.
         */
        private val replaceOnIndex: Boolean = false,
    ) {
        val pipelineName: String
            get() = if (remove) "Remove $entity from Index — $label" else "Index $entity — $label"

        fun nodes(fqdn: String): List<PipelineNode> {
            val input = InputNode(id = INPUT, acceptedType = fqdn, position = NodePosition(40.0, 140.0))
            val getId = GetIdNode(id = GET_ID, position = NodePosition(260.0, 140.0))
            val get = getNode(GET, NodePosition(480.0, 140.0))
            if (remove) {
                return listOf(input, getId, get, RemoveFromIndexNode(id = SINK, position = NodePosition(720.0, 140.0)))
            }
            val build = requireNotNull(buildNode) { "index spec for $entity is missing a Build node factory" }
            return listOf(
                input,
                getId,
                get,
                build(BUILD_DEFAULT, NodePosition(720.0, 70.0), primaryIndex, false),
                IndexDocumentNode(id = INDEX_DEFAULT, index = primaryIndex, replace = replaceOnIndex, position = NodePosition(960.0, 70.0)),
                build(BUILD_ADMIN, NodePosition(720.0, 210.0), SearchDocumentPipeline.ADMIN_INDEX, true),
                IndexDocumentNode(id = INDEX_ADMIN, index = SearchDocumentPipeline.ADMIN_INDEX, replace = replaceOnIndex, position = NodePosition(960.0, 210.0)),
            )
        }

        fun edges(): List<PipelineEdge> {
            val base = listOf(
                PipelineEdge(id = "e1", source = INPUT, target = GET_ID),
                PipelineEdge(id = "e2", source = GET_ID, target = GET),
            )
            if (remove) {
                return base + PipelineEdge(id = "e3", source = GET, target = SINK)
            }
            return base + listOf(
                PipelineEdge(id = "e3", source = GET, target = BUILD_DEFAULT),
                PipelineEdge(id = "e4", source = BUILD_DEFAULT, target = INDEX_DEFAULT),
                PipelineEdge(id = "e5", source = GET, target = BUILD_ADMIN),
                PipelineEdge(id = "e6", source = BUILD_ADMIN, target = INDEX_ADMIN),
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DefaultIndexPipelinesInstaller::class.java)

        const val NAME = "default-index-pipelines"
        private const val INPUT = "input"
        private const val GET_ID = "getId"
        private const val GET = "get"
        private const val SINK = "sink"
        private const val BUILD_DEFAULT = "buildDefault"
        private const val INDEX_DEFAULT = "indexDefault"
        private const val BUILD_ADMIN = "buildAdmin"
        private const val INDEX_ADMIN = "indexAdmin"
        private const val DESCRIPTION =
            "Default platform pipeline that keeps the search index in sync. Edit or disable it to change indexing."

        private val SPECS: List<Spec> = buildList {
            // Metadata — Get Metadata resolves the entity; Build Metadata Search Document builds per index.
            fun metadata(event: KClass<out Event>, label: String, remove: Boolean = false) =
                add(
                    Spec(
                        event, "Metadata", label, remove,
                        getNode = { id, pos -> MetadataEventToMetadataNode(id = id, position = pos) },
                        buildNode = if (remove) null else { id, pos, index, admin ->
                            BuildMetadataSearchDocumentNode(
                                id = id, index = index, position = pos,
                                requirePublic = !admin, requirePublished = !admin, requireSearchable = !admin,
                            )
                        },
                    ),
                )
            metadata(MetadataCreated::class, "Created")
            metadata(MetadataUpdated::class, "Updated")
            metadata(MetadataStateChangeComplete::class, "State Change")
            metadata(MetadataUploadCleared::class, "Upload Cleared")
            metadata(MetadataUploadedEvent::class, "Uploaded")
            metadata(MetadataSupplementedUpdatedEvent::class, "Supplement Updated")
            metadata(MetadataDeleted::class, "Deleted", remove = true)

            // Collection — Get Collection resolves the entity; Build Collection Search Document builds per index.
            fun collection(event: KClass<out Event>, label: String, remove: Boolean = false) =
                add(
                    Spec(
                        event, "Collection", label, remove,
                        getNode = { id, pos -> CollectionEventToCollectionNode(id = id, position = pos) },
                        buildNode = if (remove) null else { id, pos, index, admin ->
                            BuildCollectionSearchDocumentNode(
                                id = id, index = index, position = pos,
                                requirePublic = !admin, requirePublished = !admin, requireSearchable = !admin,
                            )
                        },
                        // A collection yields one document per language variant, so replace its set on each index.
                        replaceOnIndex = true,
                    ),
                )
            collection(CollectionCreated::class, "Created")
            collection(CollectionUpdated::class, "Updated")
            collection(CollectionSupplementaryAdded::class, "Supplementary Added")
            collection(CollectionSupplementaryUpdated::class, "Supplementary Updated")
            collection(CollectionStateChangedComplete::class, "State Change")
            collection(CollectionSupplementedUpdatedEvent::class, "Supplement Updated")
            collection(CollectionDeleted::class, "Deleted", remove = true)
            collection(CollectionLanguageVariantDeleted::class, "Language Variant Deleted", remove = true)

            // Profile — Get Profile resolves the entity; Build Profile Search Document builds per index.
            fun profile(event: KClass<out Event>, label: String, remove: Boolean = false) =
                add(
                    Spec(
                        event, "Profile", label, remove,
                        getNode = { id, pos -> ProfileEventToProfileNode(id = id, position = pos) },
                        buildNode = if (remove) null else { id, pos, index, admin ->
                            BuildProfileSearchDocumentNode(
                                id = id, index = index, position = pos,
                                requirePublic = !admin, requirePublished = !admin, requireSearchable = !admin,
                            )
                        },
                        primaryIndex = SearchDocumentPipeline.PROFILE_INDEX,
                    ),
                )
            profile(ProfileCreatedEvent::class, "Created")
            profile(ProfileUpdatedEvent::class, "Updated")
            profile(ProfileDeletedEvent::class, "Deleted", remove = true)
        }
    }
}
