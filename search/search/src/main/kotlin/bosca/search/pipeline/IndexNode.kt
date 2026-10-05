package bosca.search.pipeline

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.search.Indexable
import bosca.search.service.SearchService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action contributed by `search`: adds or updates the inbound entity in the search index via
 * [SearchService.index], which routes by type (Metadata, Collection, Profile) and applies each
 * entity's own visibility (public/published/searchable) at index time.
 *
 * The input must be a **typed** entity — e.g. straight from a Get Metadata/Collection/Profile node —
 * not a shape-changed JSON value, because indexing needs the object itself (a Metadata carries the
 * `version` the index keys on). The entity is passed through unchanged so the node can sit
 * mid-pipeline (index, then continue).
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Index",
    description = "Adds or updates the inbound entity (Metadata, Collection, or Profile) in the search index.",
    group = "Search",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Indexable entity",
            description = "A Metadata, Collection, or Profile object (e.g. from a Get node) to index.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Indexed entity",
            description = "The same entity, passed through so you can chain more steps after indexing.",
        ),
    ],
)
@Serializable
@SerialName("search.index")
class IndexNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    private fun resolveInput(inputs: NodeInputs): PipelineValue {
        val input = inputs.first ?: error("Index node '${name.ifBlank { id }}' requires an entity to index")
        input.value as? Indexable ?: error(
            "Index node '${name.ifBlank { id }}' requires a Metadata, Collection, or Profile " +
                "(got ${input.typeName ?: "a non-indexable value"})",
        )
        return input
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = resolveInput(inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "index")
            input.typeName?.let { put("type", it) }
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = resolveInput(inputs)
        val item = input.value as Indexable
        provide<SearchService>().index(item)
        return input
    }
}
