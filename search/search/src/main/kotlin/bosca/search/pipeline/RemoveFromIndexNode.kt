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
 * Action contributed by `search`: removes the inbound entity from the search index via
 * [SearchService.delete], the counterpart to [IndexNode] — wire it from a delete event (e.g. Metadata
 * Deleted → Get Metadata → Remove from Index). Like [IndexNode], the input must be a typed entity
 * (Metadata, Collection, or Profile), which is passed through unchanged so the node can sit
 * mid-pipeline.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Remove from Index",
    description = "Removes the inbound entity (Metadata, Collection, or Profile) from the search index.",
    group = "Search",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Indexable entity",
            description = "A Metadata, Collection, or Profile object (e.g. from a Get node) to remove from the index.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Removed entity",
            description = "The same entity, passed through so you can chain more steps after removal.",
        ),
    ],
)
@Serializable
@SerialName("search.remove")
class RemoveFromIndexNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    private fun resolveInput(inputs: NodeInputs): PipelineValue {
        val input = inputs.first ?: error("Remove from Index node '${name.ifBlank { id }}' requires an entity to remove")
        input.value as? Indexable ?: error(
            "Remove from Index node '${name.ifBlank { id }}' requires a Metadata, Collection, or Profile " +
                "(got ${input.typeName ?: "a non-indexable value"})",
        )
        return input
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = resolveInput(inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "removeFromIndex")
            input.typeName?.let { put("type", it) }
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = resolveInput(inputs)
        val item = input.value as Indexable
        provide<SearchService>().delete(item)
        return input
    }
}
