package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
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
 * Action node contributed by `content`: controls whether an inbound [Metadata] appears in search
 * results via [MetadataService.setSearchable]. Unlike the routing-only Visibility Gate, this persists
 * the metadata's `searchable` flag. Passes the *refreshed* metadata through for chaining; under
 * [PipelineContext.dryRun] it records the intended action and mutates nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Metadata Searchable",
    description = "Sets whether a metadata appears in search results.",
    group = "Content",
    subgroup = "Metadata",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Metadata",
            type = Metadata::class,
            description = "A Metadata (e.g. from Get Metadata).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Metadata::class,
            typeLabel = "Metadata",
            description = "The metadata after the update, for chaining.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "searchable", control = SettingControl.BOOLEAN, label = "Searchable", default = "true",
            description = "On includes the metadata in search results; off excludes it.",
        ),
    ],
)
@Serializable
@SerialName("metadata.setSearchable")
class SetMetadataSearchableNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** True includes the metadata in search results; false excludes it. */
    val searchable: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Reads the raw inbound value only — a dry run traces whatever is wired so far, so a missing
        // required input must not fail it.
        val input = inputs.first
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "setMetadataSearchable")
            put("searchable", searchable)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadata = SetMetadataSearchableNodeSerializer.deserialize(context, inputs).`in`
        val label = name.ifBlank { id }
        val service = provide<MetadataService>()
        service.setSearchable(metadata.id, searchable)
        val fresh = service.getById(metadata.id)
            ?: error("Set Metadata Searchable node '$label': metadata ${metadata.id} not found after update")
        return SetMetadataSearchableNodeSerializer.serialize(fresh)
    }
}
