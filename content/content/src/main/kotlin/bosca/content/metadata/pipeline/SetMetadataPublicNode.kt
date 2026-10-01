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
import bosca.pipelines.annotation.SettingOption
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

/** The visibility targets [SetMetadataPublicNode] accepts; a file-level constant (never a companion)
 *  so the `@Serializable` companion KSP generates for `serializer()` is the only one. */
private val METADATA_PUBLIC_TARGETS = setOf("metadata", "content", "supplementary")

/**
 * Action node contributed by `content`: toggles the public visibility of an inbound [Metadata] via
 * [MetadataService]. The [target] chooses which visibility flag is set:
 *  - `metadata` — the entry itself (`setPublic`).
 *  - `content` — its primary content payload (`setPublicContent`).
 *  - `supplementary` — its supplementary content (`setPublicSupplementary`).
 *
 * The node passes the *refreshed* metadata through its `out` port so chained nodes see the new flag.
 * Under [PipelineContext.dryRun] it records the intended action and mutates nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Metadata Public",
    description = "Sets the public visibility of a metadata, its content, or its supplementary content.",
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
            name = "target", control = SettingControl.ENUM, label = "Visibility of", default = "metadata",
            options = [
                SettingOption("metadata", "The metadata"),
                SettingOption("content", "Its content"),
                SettingOption("supplementary", "Its supplementary content"),
            ],
            description = "Which visibility flag to set.",
        ),
        SettingSlot(
            name = "public", control = SettingControl.BOOLEAN, label = "Public", default = "true",
            description = "On makes the chosen target publicly visible; off restricts it.",
        ),
    ],
)
@Serializable
@SerialName("metadata.setPublic")
class SetMetadataPublicNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** Which visibility flag to set: `metadata`, `content`, or `supplementary`. */
    val target: String = "metadata",
    /** True makes the chosen target publicly visible; false restricts it. */
    val public: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Reads the raw inbound value only — a dry run traces whatever is wired so far, so a missing
        // required input must not fail it.
        val input = inputs.first
        val t = resolveTarget()
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "setMetadataPublic")
            put("target", t)
            put("public", public)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadata = SetMetadataPublicNodeSerializer.deserialize(context, inputs).`in`
        val label = name.ifBlank { id }
        val service = provide<MetadataService>()
        when (resolveTarget()) {
            "metadata" -> service.setPublic(metadata, public)
            "content" -> service.setPublicContent(metadata, public)
            else -> service.setPublicSupplementary(metadata, public)
        }
        val fresh = service.getById(metadata.id)
            ?: error("Set Metadata Public node '$label': metadata ${metadata.id} not found after update")
        return SetMetadataPublicNodeSerializer.serialize(fresh)
    }

    private fun resolveTarget(): String {
        val t = target.trim().lowercase()
        require(t in METADATA_PUBLIC_TARGETS) {
            "Set Metadata Public node '${name.ifBlank { id }}': unknown target '$target' (expected metadata, content, or supplementary)"
        }
        return t
    }
}
