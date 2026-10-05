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
    label = "Add a HubSpot Object",
    description = "Add a HubSpot object",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(
            name = "properties",
            kind = SlotKind.OBJECT,
            typeLabel = "HubSpot JSON Properties",
            type = JsonElement::class,
            description = "An object of HubSpot property name/value pairs to write as the object's properties.",
        )
    ],
    outputs = [
        OutputSlot(
            "out",
            kind = SlotKind.STRING,
            typeLabel = "HubSpot Object ID",
            description = "The created object — its id.",
        ),
        OutputSlot(
            "alreadyExists",
            kind = SlotKind.STRING,
            typeLabel = "HubSpot Object ID",
            description = "The created object — its id.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "objectType", control = SettingControl.TEXT, label = "Object type", mono = true, placeholder = "contacts",
            description = "The HubSpot object type to create (e.g. contacts, companies, deals). Feed 'properties' the object's HubSpot property values; routes to 'out' with the new id, or 'alreadyExists' when a match was found.",
        ),
    ],
)
@Serializable
@SerialName("hubspotAdd")
class HubSpotAddNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val objectType: String = "contacts",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        // A dry run traces whatever is wired so far, so a missing 'properties' input must not fail
        // it — hence the lenient deserializePartial.
        val properties = HubSpotAddNodeSerializer.deserializePartial(context, inputs).properties as? JsonObject
            ?: JsonObject(emptyMap())
        context.trace?.actions?.set(id, buildJsonObject {
            put("action", "hubspotAdd")
            put("objectType", objectType)
            put("properties", properties)
        })
        return HubSpotAddNodeSerializer.serializeOut("new-hubspot-id")
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val properties = HubSpotAddNodeSerializer.deserialize(context, inputs).properties as? JsonObject
            ?: JsonObject(emptyMap())
        if (properties.isEmpty()) error("No properties provided")
        val result = provide<HubSpot>().add(objectType, properties, null, "Existing ID: ")
        return if (result.added) {
            HubSpotAddNodeSerializer.serializeOut(result.id)
        } else {
            HubSpotAddNodeSerializer.serializeAlreadyExists(result.id)
        }
    }
}
