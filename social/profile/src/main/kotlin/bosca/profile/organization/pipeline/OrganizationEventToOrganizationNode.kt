package bosca.profile.organization.pipeline

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
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `social/profile`: loads the full [Organization] for an inbound
 * organization [UUID] via [OrganizationService], under the run's principal. Output carries
 * `Organization.serializer()`.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Organization",
    description = "Loads the full Organization for an organization id.",
    group = "Social",
    subgroup = "Organizations",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Organization id",
            description = "The organization's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Organization::class,
            typeLabel = "Organization",
            description = "The full Organization.",
        ),
    ],
)
@Serializable
@SerialName("organization.fromEvent")
class OrganizationEventToOrganizationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = OrganizationEventToOrganizationNodeSerializer.deserialize(context, inputs)
        val organization = provide<OrganizationService>().getOrganization(input.`in`)
        return OrganizationEventToOrganizationNodeSerializer.serialize(organization)
    }
}
