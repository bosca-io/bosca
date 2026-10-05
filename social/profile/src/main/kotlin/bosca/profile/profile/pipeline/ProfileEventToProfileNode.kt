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
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `social/profile`: loads the full [Profile] for an inbound profile
 * [UUID] via [ProfileService], under the run's principal. Output carries `Profile.serializer()` so
 * downstream nodes can bridge it to JSON natively. Contributed to the engine purely by the
 * `@PipelineNodeType` annotation — no engine changes.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Profile",
    description = "Loads the full Profile for a profile id.",
    group = "Social",
    subgroup = "Profiles",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Profile id",
            description = "The profile's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Profile::class,
            typeLabel = "Profile",
            description = "The full Profile.",
        ),
    ],
)
@Serializable
@SerialName("profile.fromEvent")
class ProfileEventToProfileNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = ProfileEventToProfileNodeSerializer.deserialize(context, inputs)
        val profile = provide<ProfileService>().getById(input.`in`)
        return ProfileEventToProfileNodeSerializer.serialize(profile)
    }
}
