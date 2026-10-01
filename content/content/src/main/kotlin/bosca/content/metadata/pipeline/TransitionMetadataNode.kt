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
 * Action node contributed by `content`: directly transitions an inbound [Metadata] to a workflow
 * [state] via [MetadataService.setState], recording [status] and (when present) the run's principal.
 * This is the immediate, synchronous transition — distinct from "Set Metadata Ready", which is the
 * editorial-approval gate. The node passes the *transitioned* metadata through for chaining; under
 * [PipelineContext.dryRun] it records the intended action and mutates nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Transition Metadata",
    description = "Moves a metadata directly to a workflow state.",
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
            description = "The metadata in its new state, for chaining.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "state", control = SettingControl.TEXT, label = "To state", required = true, mono = true,
            placeholder = "published",
            description = "The workflow state id to move the metadata to.",
        ),
        SettingSlot(
            name = "status", control = SettingControl.TEXT, label = "Status note",
            placeholder = "Transitioned by pipeline",
            description = "A short message recorded in the transition history.",
        ),
    ],
)
@Serializable
@SerialName("metadata.transition")
class TransitionMetadataNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The workflow state id to move the metadata to. */
    val state: String = "",
    /** A short message recorded in the transition history. */
    val status: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Reads the raw inbound value only — a dry run traces whatever is wired so far, so a missing
        // required input must not fail it.
        val input = inputs.first
        val toState = resolveState()
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "transitionMetadata")
            put("state", toState)
            put("status", status)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val metadata = TransitionMetadataNodeSerializer.deserialize(context, inputs).`in`
        val toState = resolveState()
        val principal = context.authentication.principal()?.asPrincipal()
        val updated = provide<MetadataService>().setState(metadata, toState, status, principal)
        return TransitionMetadataNodeSerializer.serialize(updated)
    }

    private fun resolveState(): String {
        val toState = state.trim()
        require(toState.isNotBlank()) {
            "Transition Metadata node '${name.ifBlank { id }}' requires a target state"
        }
        return toState
    }
}
