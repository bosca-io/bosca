package bosca.content.transformations.pipeline

import bosca.category.service.CategoryService
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.collection.service.getCategories
import bosca.content.transformations.CollectionToSearchDocument
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
import bosca.search.model.CollectionSearchContext
import bosca.search.pipeline.SearchDocumentPipeline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Builds the search document(s) for a [Collection] and a target index — the same context build the
 * index job executors use ([CollectionToSearchDocument.toContext], which expands the collection's
 * language variants), then a configurable JSONata expression. The default collection expression emits
 * one document per *eligible* variant (published/advertised on a public index, all on the admin
 * index), so the output is typically a JSON **array**.
 *
 * - [expression] blank → the platform's configured `search` collection expression.
 * - [index] selects the target index, driving per-variant eligibility (admin vs. public) in the
 *   expression.
 * - The visibility toggles and the optional [indexWhen] predicate decide *whether* to index the
 *   collection; a gated-out collection emits a removal signal so the Index Document node removes any
 *   stale documents. Turn the public/published requirements off for an Admin index branch.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Build Collection Search Document",
    description = "Builds a Collection's search document(s) as JSON (one per eligible language variant), applying a configurable JSONata expression. Pair with Index Document.",
    group = "Content",
    subgroup = "Search Documents",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Collection",
            type = Collection::class,
            description = "A Collection (e.g. from Get Collection) to build search documents for.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Search document(s) (JSON)",
            description = "A JSON array of the collection's per-variant search documents, or a removal signal when it is not visible for the target index.",
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
@SerialName("search.buildCollectionDocument")
class BuildCollectionSearchDocumentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** JSONata applied to the built context; blank uses the platform's configured collection expression. */
    val expression: String = "",
    /** The target search index name; drives per-variant eligibility in the expression. */
    val index: String = SearchDocumentPipeline.DEFAULT_INDEX,
    /** Only index when the collection is public. */
    val requirePublic: Boolean = true,
    /** Only index when the collection is published. */
    val requirePublished: Boolean = true,
    /** Only index when the collection is searchable. */
    val requireSearchable: Boolean = true,
    /** Never index a deleted collection. */
    val excludeDeleted: Boolean = true,
    /** Optional JSONata predicate over the collection; when set and false, the collection is not indexed. */
    val indexWhen: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val collection = BuildCollectionSearchDocumentNodeSerializer.deserialize(context, inputs).`in`
        val visible = ContentVisibility.passes(
            public = collection.public, published = collection.isPublished,
            searchable = collection.isSearchable, deleted = collection.deleted,
            requirePublic = requirePublic, requirePublished = requirePublished,
            requireSearchable = requireSearchable, excludeDeleted = excludeDeleted,
        ) && (indexWhen.isBlank() || ContentVisibility.matches(
            indexWhen, context.json.encodeToJsonElement(Collection.serializer(), collection),
        ))
        if (!visible) return PipelineValue.ofJson(SearchDocumentBuild.removalSignal(collection.id.toString()))
        val system = IndexStorageSystem(name = index)
        val categories = provide<CollectionService>().getCategories(collection.id, provide<CategoryService>())
        val searchContext = provide<CollectionToSearchDocument>().toContext(system, collection, categories)
        val element = context.json.encodeToJsonElement(CollectionSearchContext.serializer(), searchContext)
        return PipelineValue.ofJson(SearchDocumentBuild.shape(context, element, expression) { it.collection })
    }
}
