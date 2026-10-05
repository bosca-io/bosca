package bosca.hubspot.pipeline

import bosca.di.provide
import bosca.hubspot.client.HubSpot
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Update HubSpot Object",
    description = "Update a HubSpot object",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(
            name = "properties",
            kind = SlotKind.OBJECT,
            typeLabel = "HubSpot JSON Properties",
            type = JsonElement::class,
            description = "An object of HubSpot property name/value pairs to write as the object's properties.",
        ),
        InputSlot(
            name = "hubspotId",
            kind = SlotKind.STRING,
            typeLabel = "HubSpot ID",
            description = "A HubSpot ID",
        )
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.STRING,
        )
    ],
    settings = [
        SettingSlot(
            name = "objectType", control = SettingControl.TEXT, label = "Object type", mono = true, placeholder = "contacts",
            description = "The HubSpot object type to update (e.g. contacts, companies). Feed the object's id on 'hubspotId' and its new values on 'properties'.",
        ),
    ],
)
@Serializable
@SerialName("hubspotUpdate")
class HubSpotUpdateNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val objectType: String = "contacts",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        // A dry run traces whatever is wired so far, so missing required inputs must not fail it —
        // hence the lenient deserializePartial.
        val properties = HubSpotUpdateNodeSerializer.deserializePartial(context, inputs).properties as? JsonObject
            ?: JsonObject(emptyMap())
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "hubspotUpdate")
            put("objectType", objectType)
            put("properties", properties)
        })
        return HubSpotUpdateNodeSerializer.serialize("existing-hubspot-id")
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = HubSpotUpdateNodeSerializer.deserialize(context, inputs)
        val properties = input.properties as? JsonObject ?: JsonObject(emptyMap())
        provide<HubSpot>().update(objectType, input.hubspotId, properties)
        return HubSpotUpdateNodeSerializer.serialize(input.hubspotId)
    }
}
