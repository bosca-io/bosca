package bosca.search.pipeline

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
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action contributed by `search`: writes a **prebuilt** search document into a target index via the
 * low-level [SearchService.index]. The inbound value is JSON — typically from a Build Search Document
 * node, optionally reshaped by a JSONata node in between — which can be:
 *  - a single document object → upserted,
 *  - a JSON array of documents → each upserted (e.g. a collection's per-variant documents), or
 *  - a removal signal (see [SearchDocumentPipeline]) → the carried content id is deleted from the
 *    index, so an entity that became non-visible is removed.
 *
 * Unlike the all-in-one [IndexNode] (which takes the typed entity and routes by type, applying
 * visibility itself), this node indexes exactly what it is given into the configured [index] —
 * visibility and document shape are decided upstream by the Build node, keeping indexing fully
 * pipeline-driven. The value is passed through unchanged so the node can sit mid-pipeline.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Index Document",
    description = "Writes a prebuilt search document (or array of documents) into a search index; a removal signal deletes its content id instead. Enable Replace to clear an entity's existing documents first.",
    group = "Search",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Search document (JSON)",
            description = "The search document(s) to index — typically from a Build Search Document node.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Search document (JSON)",
            description = "The same document, passed through so you can chain more steps after indexing.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "index", control = SettingControl.REFERENCE, reference = ReferenceSource.SEARCH_INDEX, label = "Target index",
            placeholder = "Default Search Index",
        ),
        SettingSlot(
            name = "replace", control = SettingControl.BOOLEAN, label = "Replace existing documents", default = "false",
            description = "Enable when one entity produces several documents (e.g. a collection's language variants) so ones no longer present are cleared.",
        ),
    ],
)
@Serializable
@SerialName("search.indexDocument")
class IndexDocumentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The target search index name (matched by name; resolves the physical index). */
    val index: String = SearchDocumentPipeline.DEFAULT_INDEX,
    /**
     * Remove every existing document for each inbound document's `contentId` before indexing. Needed
     * when one entity produces multiple documents — e.g. a collection's per-language variants — so a
     * variant that is no longer present (now unpublished or deleted) is cleared rather than left as a
     * stale hit. For single-document entities (metadata, profile) it is unnecessary: indexing already
     * replaces a document by its primary key.
     */
    val replace: Boolean = false,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    private fun resolveDocument(context: PipelineContext, inputs: NodeInputs): Pair<PipelineValue, JsonElement> {
        val input = inputs.first
            ?: error("Index Document node '${name.ifBlank { id }}' requires a search document")
        val element = input.encode(context.json)
        return input to element
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val (input, element) = resolveDocument(context, inputs)
        // Parsing of the removal signal / content ids lives with the protocol in SearchDocumentPipeline.
        val removeContentId = SearchDocumentPipeline.removalContentId(element)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", if (removeContentId != null) "delete" else "index")
            put("index", index)
            if (removeContentId == null && replace) put("replace", true)
            if (removeContentId != null) put("contentId", removeContentId)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val (input, element) = resolveDocument(context, inputs)
        // Parsing of the removal signal / content ids lives with the protocol in SearchDocumentPipeline.
        val removeContentId = SearchDocumentPipeline.removalContentId(element)

        val system = IndexStorageSystem(name = index)
        val search = provide<SearchService>()
        if (removeContentId != null) {
            search.deleteByFilter(system, SearchFilter.eq(SearchDocumentPipeline.CONTENT_ID_FIELD, removeContentId))
            return input
        }
        // Replace: drop each entity's existing documents first, so variants dropped from the new set are cleared.
        if (replace) {
            SearchDocumentPipeline.contentIds(element).forEach {
                search.deleteByFilter(system, SearchFilter.eq(SearchDocumentPipeline.CONTENT_ID_FIELD, it))
            }
        }
        when (element) {
            is JsonArray -> if (element.isNotEmpty()) search.index(system, element.toList())
            else -> search.index(system, element)
        }
        return input
    }
}
