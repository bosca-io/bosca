package bosca.recommendations.pipeline

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.recommendations.service.ProfileSignalComputeService
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Action node contributed by `recommendations`: on a profile-attribute change event, recomputes
 * + caches the affected attributes' Personalization Signals via [ProfileSignalComputeService].
 *
 * A stored triggered pipeline wires `Input(event) → this`. The event's typed classes live in the profile
 * **impl** module (off this module's classpath), so the input slot is a typeless `OBJECT` and the node reads
 * the event from its JSON form (the generated codec's `deserializePartial`, bridging through the value's
 * carried serializer) rather than casting to the class. It handles the two shapes those events take:
 *  - `ProfileAttributesAdded` / `ProfileAttributesUpdated` — carry `attributeIds`, recomputed directly.
 *  - `ProfileAttributesVerified` — carries `profileId` + `typeId` (a `verified`-gated signal must update when
 *    control is proven), recomputed for that profile's attributes of the type.
 *
 * The event is passed through to chain; under a dry run it records the intended action and skips.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Compute Personalization Signals",
    description = "Recomputes the cached Personalization Signals for the attributes named in a profile-attribute event.",
    group = "Recommendations",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Profile Attribute Event",
            description = "A ProfileAttributesAdded / ProfileAttributesUpdated / ProfileAttributesVerified event.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Profile Attribute Event",
            description = "The event, passed through.",
        ),
    ],
)
@Serializable
@SerialName("recommendations.computeProfileSignals")
class ComputeProfileSignalsNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // deserializePartial (not deserialize): a dry run traces whatever is wired so far, so a
        // missing input must not fail it.
        val payload = ComputeProfileSignalsNodeSerializer.deserializePartial(context, inputs).`in`
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "computeProfileSignals")
            put("attributes", attributeIds(payload).size)
        })
        return inputs.first
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        // deserializePartial: a missing input stays a no-op pass-through, not a failure.
        ComputeProfileSignalsNodeSerializer.deserializePartial(context, inputs).`in`?.let { recompute(it) }
        return inputs.first ?: PipelineValue.ofJson(JsonNull)
    }

    /** Recomputes for whatever attributes the event references: by id (Added/Updated) or profile+type (Verified). */
    private suspend fun recompute(payload: JsonObject) {
        val service = provide<ProfileSignalComputeService>()
        val ids = attributeIds(payload)
        if (ids.isNotEmpty()) {
            service.computeForAttributes(ids)
            return
        }
        val profileId = payload.uuid("profileId")
        val typeId = (payload["typeId"] as? JsonPrimitive)?.contentOrNull
        if (profileId != null && typeId != null) service.computeForProfileType(profileId, typeId)
    }

    private fun attributeIds(payload: JsonObject?): List<UUID> {
        val ids = payload?.get("attributeIds") as? JsonArray ?: return emptyList()
        return ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { s -> runCatching { UUID.parse(s) }.getOrNull() } }
    }

    private fun JsonObject.uuid(key: String): UUID? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.let { runCatching { UUID.parse(it) }.getOrNull() }
}
