package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action node contributed by `content`: soft-deletes an inbound [Collection] via
 * [CollectionService.markDeleted] (it remains in storage, marked deleted). A side-effect sink — it
 * produces no output, so it ends its branch. Under [PipelineContext.dryRun] it records the intended
 * action and mutates nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Delete Collection",
    description = "Soft-deletes a collection (marks it deleted; it remains in storage).",
    group = "Content",
    subgroup = "Collections",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Collection",
            type = Collection::class,
            description = "A Collection (e.g. from Get Collection).",
        ),
    ],
)
@Serializable
@SerialName("collection.delete")
class DeleteCollectionNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // A dry run traces whatever is wired so far, so a missing required input must not fail it —
        // hence the lenient deserializePartial, not deserialize.
        val collection = DeleteCollectionNodeSerializer.deserializePartial(context, inputs).`in`
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "deleteCollection")
            put("collectionId", collection?.id?.toString() ?: "")
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val collection = DeleteCollectionNodeSerializer.deserialize(context, inputs).`in`
        provide<CollectionService>().markDeleted(collection.id)
        return null
    }
}
