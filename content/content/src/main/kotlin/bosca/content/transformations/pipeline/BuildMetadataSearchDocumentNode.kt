package bosca.content.transformations.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.transformations.MetadataToSearchDocument
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.search.IndexStorageSystem
import bosca.search.model.MetadataSearchContext
import bosca.search.pipeline.SearchDocumentPipeline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Builds the search document for a [Metadata] and a target index, the same way the index job
 * executors do — [MetadataToSearchDocument.toContext] for the rich context, then a JSONata
 * expression — but with the expression configurable on the node. Output is JSON you can manipulate
 * (e.g. with a JSONata node) before handing it to an Index Document node.
 *
 * - [expression] blank → the platform's configured `search` metadata expression (default parity with
 *   the executors); set it to customize exactly what goes into the document.
 * - [index] selects the target index and drives admin-vs-public context building (e.g. variant
 *   eligibility for collections).
 * - The visibility toggles ([requirePublic]/[requirePublished]/[requireSearchable]/[excludeDeleted])
 *   and the optional [indexWhen] JSONata predicate decide *whether* to index. When an entity fails the
 *   gate, the node emits a removal signal (see [SearchDocumentPipeline]) so the Index Document node
 *   removes any stale document. Turning the public/published requirements off is how the Admin index
 *   branch indexes unpublished content — no special-casing in the node.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Build Metadata Search Document",
    description = "Builds a Metadata's search document as JSON, applying a configurable JSONata expression. Pair with Index Document to index it.",
    group = "Content",
    subgroup = "Search Documents",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Metadata",
            type = Metadata::class,
            description = "A Metadata (e.g. from Get Metadata) to build a search document for.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Search document (JSON)",
            description = "The metadata's search document as JSON, or a removal signal when it is not visible for the target index.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "expression", control = SettingControl.CODE, language = "jsonata", label = "Search document expression (JSONata)",
            placeholder = "Leave blank to use the platform's default expression for this type",
            description = "Builds the search document from the entity, then applies this expression (blank uses the platform default). Output is JSON — send it to an Index Document node, or reshape it with a JSONata node first.",
        ),
        SettingSlot(
            name = "index", control = SettingControl.REFERENCE, reference = ReferenceSource.SEARCH_INDEX, label = "Target index",
            placeholder = "Default Search Index",
        ),
        SettingSlot(name = "requirePublic", control = SettingControl.BOOLEAN, label = "Public", default = "true", group = "Index only when"),
        SettingSlot(name = "requirePublished", control = SettingControl.BOOLEAN, label = "Published", default = "true", group = "Index only when"),
        SettingSlot(name = "requireSearchable", control = SettingControl.BOOLEAN, label = "Searchable", default = "true", group = "Index only when"),
        SettingSlot(name = "excludeDeleted", control = SettingControl.BOOLEAN, label = "Not deleted", default = "true", group = "Index only when"),
        SettingSlot(
            name = "indexWhen", control = SettingControl.TEXT, label = "Extra constraint (JSONata, optional)", mono = true,
            placeholder = "e.g. attributes.indexable = true",
        ),
    ],
)
@Serializable
@SerialName("search.buildMetadataDocument")
class BuildMetadataSearchDocumentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** JSONata applied to the built context; blank uses the platform's configured metadata expression. */
    val expression: String = "",
    /** The target search index name; drives admin-vs-public context building. */
    val index: String = SearchDocumentPipeline.DEFAULT_INDEX,
    /** Only index when the metadata is public. */
    val requirePublic: Boolean = true,
    /** Only index when the metadata is published. */
    val requirePublished: Boolean = true,
    /** Only index when the metadata is searchable. */
    val requireSearchable: Boolean = true,
    /** Never index deleted metadata. */
    val excludeDeleted: Boolean = true,
    /** Optional JSONata predicate over the metadata; when set and false, the metadata is not indexed. */
    val indexWhen: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadata = BuildMetadataSearchDocumentNodeSerializer.deserialize(context, inputs).`in`
        val visible = ContentVisibility.passes(
            public = metadata.public, published = metadata.isPublished,
            searchable = metadata.isSearchable, deleted = metadata.deleted,
            requirePublic = requirePublic, requirePublished = requirePublished,
            requireSearchable = requireSearchable, excludeDeleted = excludeDeleted,
        ) && (indexWhen.isBlank() || ContentVisibility.matches(
            indexWhen, context.json.encodeToJsonElement(Metadata.serializer(), metadata),
        ))
        if (!visible) return PipelineValue.ofJson(SearchDocumentBuild.removalSignal(metadata.id.toString()))
        val system = IndexStorageSystem(name = index)
        val searchContext = provide<MetadataToSearchDocument>().toContext(system, metadata)
        val element = context.json.encodeToJsonElement(MetadataSearchContext.serializer(), searchContext)
        return PipelineValue.ofJson(SearchDocumentBuild.shape(context, element, expression) { it.metadata })
    }
}
