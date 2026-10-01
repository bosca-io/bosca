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
 * Action node contributed by `content`: marks an inbound [Metadata] ready for publishing (or revokes
 * that readiness) via [MetadataService].
 *
 * When [ready] is true it calls `setReady`, recording the run's principal as the approver — so the run
 * must carry an authenticated principal; when false it calls `setNotReady`. The node passes the
 * *refreshed* metadata through its `out` port (the post-update version, with the new `ready`
 * timestamp), so a downstream node — e.g. a Transition to "published" — sees the current state rather
 * than the stale input. Under [PipelineContext.dryRun] it records the intended action and mutates
 * nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Set Metadata Ready",
    description = "Marks a metadata ready for publishing, or revokes its ready status.",
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
            name = "ready", control = SettingControl.BOOLEAN, label = "Ready", default = "true",
            description = "On marks the metadata ready for publishing (recording the run's principal as approver); off revokes ready.",
        ),
    ],
)
@Serializable
@SerialName("metadata.setReady")
class SetMetadataReadyNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** True marks the metadata ready (recording the run principal); false revokes ready. */
    val ready: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Reads the raw inbound value only — a dry run traces whatever is wired so far, so a missing
        // required input must not fail it.
        val input = inputs.first
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "setMetadataReady")
            put("ready", ready)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadata = SetMetadataReadyNodeSerializer.deserialize(context, inputs).`in`
        val label = name.ifBlank { id }
        val service = provide<MetadataService>()
        val updated = if (ready) {
            val principal = context.authentication.principal()?.asPrincipal()
                ?: error("Set Metadata Ready node '$label' requires an authenticated principal to mark content ready")
            service.setReady(metadata, principal)
        } else {
            service.setNotReady(metadata)
            service.getById(metadata.id)
                ?: error("Set Metadata Ready node '$label': metadata ${metadata.id} not found after update")
        }
        return SetMetadataReadyNodeSerializer.serialize(updated)
    }
}
