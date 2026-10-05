package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
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
 * Action node contributed by `content`: directly transitions an inbound [Collection] to a workflow
 * [state] via [CollectionService.setState], recording [status] and (when present) the run's principal.
 * This is the immediate, synchronous transition — distinct from "Set Collection Ready", which is the
 * editorial-approval gate. The node passes the *refreshed* collection through for chaining; under
 * [PipelineContext.dryRun] it records the intended action and mutates nothing.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Transition Collection",
    description = "Moves a collection directly to a workflow state.",
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
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Collection::class,
            typeLabel = "Collection",
            description = "The collection in its new state, for chaining.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "state", control = SettingControl.TEXT, label = "To state", required = true, mono = true,
            placeholder = "published",
            description = "The workflow state id to move the collection to.",
        ),
        SettingSlot(
            name = "status", control = SettingControl.TEXT, label = "Status note",
            placeholder = "Transitioned by pipeline",
            description = "A short message recorded in the transition history.",
        ),
    ],
)
@Serializable
@SerialName("collection.transition")
class TransitionCollectionNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The workflow state id to move the collection to. */
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
            put("action", "transitionCollection")
            put("state", toState)
            put("status", status)
        })
        return input
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val collection = TransitionCollectionNodeSerializer.deserialize(context, inputs).`in`
        val label = name.ifBlank { id }
        val toState = resolveState()
        val principal = context.authentication.principal()?.asPrincipal()
        val service = provide<CollectionService>()
        // setState returns the ICollection; re-fetch to emit a concrete Collection for the typed out port.
        service.setState(collection, toState, status, principal)
        val fresh = service.getById(collection.id)
            ?: error("Transition Collection node '$label': collection ${collection.id} not found after transition")
        return TransitionCollectionNodeSerializer.serialize(fresh)
    }

    private fun resolveState(): String {
        val toState = state.trim()
        require(toState.isNotBlank()) {
            "Transition Collection node '${name.ifBlank { id }}' requires a target state"
        }
        return toState
    }
}
