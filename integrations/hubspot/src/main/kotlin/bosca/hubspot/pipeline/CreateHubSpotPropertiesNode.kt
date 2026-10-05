package bosca.hubspot.pipeline

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.di.provide
import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.hubspot.transformer.HubSpotEntityTransformer
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
import bosca.profile.model.ProfileType
import bosca.serialization.JsonConverter.toJsonElement
import com.dashjoin.jsonata.Jsonata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Transform contributed by `integrations/hubspot`: maps a profile to the HubSpot object properties
 * that represent it. Takes a profile [bosca.serialization.UUID] on `in`, resolves the profile's full
 * HubSpot view (profile + attributes + organization/memberships + principal) via
 * [HubSpotEntityTransformer], then evaluates the configured JSONata expression over it — the
 * `generic` expression for a person, the `organization` expression for a company (per the profile
 * type) — to produce HubSpot property name/value pairs. The output is the entity JSON, ready to wire
 * into a [HubSpotAddNode]'s or [HubSpotUpdateNode]'s `properties`.
 *
 * Reuses the exact mapping the legacy sync job performed, so a pipeline and the job produce identical
 * HubSpot entities. Pure read + transform with no side effect, so it runs unchanged in a dry run.
 *
 * Contributed to the engine purely by the `@PipelineNodeType` annotation — no engine changes.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Create HubSpot Properties",
    description = "Map a profile to HubSpot object properties via the configured JSONata expression.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Profile id",
            description = "The profile to map to a HubSpot entity — a UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "HubSpot JSON Properties",
            type = JsonElement::class,
            description = "The HubSpot property name/value pairs representing the profile.",
        ),
    ]
)
@Serializable
@SerialName("hubspotProperties")
class CreateHubSpotPropertiesNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val profileId = CreateHubSpotPropertiesNodeSerializer.deserialize(context, inputs).`in`
        val configurationService = provide<ConfigurationService>()
        val json = context.json
        val profile = provide<HubSpotEntityTransformer>().transform(Unit, profileId)
        val configuration = configurationService.getValueAs<HubSpotConfiguration>("hubspot", json) ?: error("Hubspot configuration not found")
        val jsonata = if (profile.type == ProfileType.GENERIC) {
            Jsonata.jsonata(configuration.expressions.generic)
        } else {
            Jsonata.jsonata(configuration.expressions.organization)
        }
        val properties = jsonata.evaluate(profile.data).toJsonElement().jsonObject
        return CreateHubSpotPropertiesNodeSerializer.serialize(properties)
    }
}
