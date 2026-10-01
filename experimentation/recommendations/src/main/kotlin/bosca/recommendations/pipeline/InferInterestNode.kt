package bosca.recommendations.pipeline

import bosca.content.metadata.service.MetadataService
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
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Learned-attribute inference (Phase 4). On a `ProfileRatingAdded` event, a *high* rating is
 * taken as evidence that the rater is interested in the rated content's category, so this node writes a
 * **learned** interest attribute (`bosca.recommendations.learned_interest`) — with `confidence < 100` and
 * `source = "learned"` — that the Personalization Signals compute picks up subject to each signal's own
 * confidence gate (its JSONata `confidence >= N ? … : undefined`). The write path is the same
 * [ProfileAttributeService] one [bosca.profile.profile.pipeline.SetProfileAttributeNode] uses; the domain
 * inference (which category, what confidence, dedupe-per-category) is encapsulated here.
 *
 * A simple, honest heuristic — not a trained model: a low/absent rating, a collection rating (no metadata),
 * or content without categories infers nothing. Repeat ratings of the same category reinforce the confidence
 * (still capped below certainty). The event class lives in the profile impl module, so the node reads it from
 * its JSON form (the generated codec's `deserializePartial`, via the carried serializer), like the
 * signal-compute node.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Infer Learned Interest",
    description = "On a high content rating, records a learned (confidence < 100) interest in the rated content's category.",
    group = "Recommendations",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Profile Rating Event",
            description = "A ProfileRatingAdded event.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Profile Rating Event",
            description = "The event, passed through.",
        ),
    ],
)
@Serializable
@SerialName("recommendations.inferInterest")
class InferInterestNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject { put("action", "inferLearnedInterest") })
        return inputs.first
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        // deserializePartial: a missing input stays a no-op pass-through, not a failure.
        val event = InferInterestNodeSerializer.deserializePartial(context, inputs).`in`
        if (event != null) infer(event)
        return inputs.first ?: PipelineValue.ofJson(JsonNull)
    }

    private suspend fun infer(event: JsonObject) {
        val rating = (event["rating"] as? JsonPrimitive)?.intOrNull ?: return
        if (rating < HIGH_RATING) return // only a positive rating is evidence of interest
        val profileId = event.uuid("profileId") ?: return
        val metadataId = event.uuid("metadataId") ?: return // collection ratings carry no content category

        val category = provide<MetadataService>().getCategories(metadataId).firstOrNull() ?: return
        val attributes = provide<ProfileAttributeService>()

        // Dedupe per category: reinforce an existing learned interest in this category, else record a new one.
        val existing = attributes.getAttributesByProfile(profileId).firstOrNull {
            it.typeId == LEARNED_INTEREST_TYPE &&
                (it.attributes as? JsonObject)?.get("category_id")?.let { c -> (c as? JsonPrimitive)?.contentOrNull } == category.id.toString()
        }
        val confidence = if (existing != null) {
            minOf(existing.confidence + REINFORCEMENT, MAX_LEARNED_CONFIDENCE)
        } else if (rating >= STRONG_RATING) {
            STRONG_CONFIDENCE
        } else {
            BASE_CONFIDENCE
        }
        attributes.addAttributes(
            profileId,
            listOf(
                ProfileAttributeInput(
                    id = existing?.id ?: UUID.NIL, // NIL inserts; an existing id reinforces in place
                    typeId = LEARNED_INTEREST_TYPE,
                    visibility = ProfileVisibility.USER,
                    confidence = confidence,
                    priority = 0,
                    source = LEARNED_SOURCE,
                    attributes = buildJsonObject {
                        put("interest", category.name)
                        put("category_id", category.id.toString())
                    },
                ),
            ),
        )
    }

    private fun JsonObject.uuid(key: String): UUID? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.let { runCatching { UUID.parse(it) }.getOrNull() }

    companion object {
        /** The profile attribute type learned interests are written under. */
        const val LEARNED_INTEREST_TYPE = "bosca.recommendations.learned_interest"
        private const val LEARNED_SOURCE = "learned"
        private const val HIGH_RATING = 4          // ratings below this infer nothing
        private const val STRONG_RATING = 5
        private const val BASE_CONFIDENCE = 55     // a 4-star rating
        private const val STRONG_CONFIDENCE = 70   // a 5-star rating
        private const val REINFORCEMENT = 5        // each repeat rating of a category
        private const val MAX_LEARNED_CONFIDENCE = 90 // learned attributes never reach certainty (100)
    }
}
