package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.trigger.PipelineDispatchJob
import bosca.pipelines.trigger.enqueue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action: dispatches the catalogued event [eventName] with the node's inbound value as its payload,
 * driving the platform's Event → Pipeline triggers — every pipeline that triggers on [eventName] runs
 * with this payload as its input (decoded downstream via the event's catalogued serializer).
 *
 * This is the producer side of the trigger system: it enqueues the same [PipelineDispatchJob] the
 * framework's own dispatcher does, so a pipeline can emit an event to chain or fan out into other
 * pipelines. The inbound [PipelineValue] is encoded to JSON via its own serializer, so shape it to the
 * event's fields with a JSONata node first; with no inbound value an empty object is dispatched.
 *
 * Fire-and-forget — no output, downstream nodes are skipped. It drives pipeline triggers only; it does
 * not publish to PubSub subscribers or enqueue the event's `@JobEvent` jobs (those can't be
 * reconstructed from an event name + JSON alone).
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Dispatch Event",
    description = "Dispatches a catalogued event with the inbound value as its payload — every pipeline that triggers on the event runs with that payload.",
    group = "Core",
    subgroup = "Actions",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Payload",
            description = "The value dispatched as the event's payload (shape it to the event's fields with a JSONata node first); an empty object when not connected.",
            required = false,
        ),
    ],
    settings = [
        SettingSlot(
            name = "eventName", control = SettingControl.REFERENCE, reference = ReferenceSource.EVENT,
            label = "Event", required = true, placeholder = "Pick an event…",
            description = "The catalogued event to dispatch. Pipelines triggered by this event receive the inbound value as their input.",
        ),
    ],
)
@Serializable
@SerialName("dispatchEvent")
class DispatchEventNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val eventName: String,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    /** The inbound value encoded as the event's JSON payload, or an empty object when not connected. */
    private fun payload(context: PipelineContext, inputs: NodeInputs): JsonElement =
        inputs.first?.encode(context.json) ?: buildJsonObject { }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "dispatchEvent")
            put("eventName", eventName)
            put("payload", payload(context, inputs))
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        PipelineDispatchJob(eventName = eventName, eventPayload = payload(context, inputs)).enqueue()
        return null
    }
}
