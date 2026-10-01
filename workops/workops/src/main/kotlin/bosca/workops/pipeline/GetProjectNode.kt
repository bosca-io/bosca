package bosca.workops.pipeline

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
import bosca.workops.model.project.Project
import bosca.workops.service.ProjectService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `workops`: loads the full [Project] for an inbound project [UUID] via
 * [ProjectService], under the run's principal. Output carries `Project.serializer()` so downstream
 * nodes can read its fields or convert it to JSON.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Project",
    description = "Loads the full Project for a project id.",
    group = "WorkOps",
    subgroup = "Planning",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Project id",
            description = "The project's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Project::class,
            typeLabel = "Project",
            description = "The full Project.",
        ),
    ],
)
@Serializable
@SerialName("project.get")
class GetProjectNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val projectId = GetProjectNodeSerializer.deserialize(context, inputs).`in`
        val project = provide<ProjectService>().getById(projectId)
            ?: error("Get Project node '${name.ifBlank { id }}': project $projectId not found")
        return GetProjectNodeSerializer.serialize(project)
    }
}
