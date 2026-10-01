package bosca.profile.profile.pipeline

import bosca.di.provide
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
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `social/profile`: loads a profile's `ProfileAttribute`s for an
 * inbound [Profile] via [ProfileService] (this is where the email attribute, `bosca.profiles.email`,
 * lives). Output carries an explicit `ListSerializer(ProfileAttribute…)` so it bridges to JSON
 * natively for a downstream `ObjectsToMap` / `JSONata`.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Profile Attributes",
    description = "Loads a profile's attributes (including email).",
    group = "Social",
    subgroup = "Profiles",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.OBJECT,
            typeLabel = "Profile",
            type = Profile::class,
            description = "A Profile (e.g. from Get Profile).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.ARRAY,
            type = ProfileAttribute::class,
            typeLabel = "List of ProfileAttribute",
            description = "The profile's attributes.",
        ),
    ],
)
@Serializable
@SerialName("profile.attributesFromEvent")
class ProfileEventToProfileAttributesNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = ProfileEventToProfileAttributesNodeSerializer.deserialize(context, inputs)
        val attributes = provide<ProfileService>().getAttributes(input.`in`.id)
        return ProfileEventToProfileAttributesNodeSerializer.serialize(attributes)
    }
}
