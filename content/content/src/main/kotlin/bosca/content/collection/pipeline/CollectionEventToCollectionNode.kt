package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `content`: loads the [Collection] for an inbound collection [UUID]
 * via [CollectionService], under the run's principal. Output carries `Collection.serializer()`.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Collection",
    description = "Loads the full Collection for a collection id.",
    group = "Content",
    subgroup = "Collections",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Collection id",
            description = "The collection's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Collection::class,
            typeLabel = "Collection",
            description = "The full Collection.",
        ),
    ],
)
@Serializable
@SerialName("collection.fromEvent")
class CollectionEventToCollectionNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val collectionId = CollectionEventToCollectionNodeSerializer.deserialize(context, inputs).`in`
        val collection = provide<CollectionService>().getById(collectionId)
            ?: error("Collection node '${name.ifBlank { id }}': collection $collectionId not found")
        return CollectionEventToCollectionNodeSerializer.serialize(collection)
    }
}
