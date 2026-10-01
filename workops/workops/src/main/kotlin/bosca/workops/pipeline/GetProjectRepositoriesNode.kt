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
import bosca.pipelines.node.uuid
import bosca.serialization.UUID
import bosca.workops.model.project.ProjectRepository
import bosca.workops.service.ProjectRepositoryService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resolver node contributed by `workops`: lists the git repositories a project owns (its
 * [ProjectRepository]s), so a release relay reads a project's repos from data — to tag/build each —
 * instead of a repositoryId baked into the pipeline. The project id comes from a bare inbound UUID, or an
 * object's `id` (a `Project`) or `projectId` (a `ReleaseProjectVersion`). Output carries
 * `ListSerializer(ProjectRepository.serializer())`, for a downstream ForEach.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Git Repositories",
    description = "Lists the git repositories a project owns, for a downstream ForEach to tag/build.",
    group = "WorkOps",
    subgroup = "Planning",
    inputs = [
        InputSlot(
            name = "in", kind = SlotKind.ANY, typeLabel = "Project id",
            description = "The project's UUID, or an object carrying it (a Project's id, or a ReleaseProjectVersion's projectId).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.ARRAY, type = ProjectRepository::class,
            typeLabel = "Project repositories",
            description = "The project's owned git repositories.",
        ),
    ],
)
@Serializable
@SerialName("project.repositories")
class GetProjectRepositoriesNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        val projectId = resolveProjectId(context, inputs)
            ?: error("Get Git Repositories node '$label' requires a project id (a UUID, or an object with id/projectId)")
        val repos = provide<ProjectRepositoryService>().list(projectId)
        return GetProjectRepositoriesNodeSerializer.serialize(repos)
    }

    /** The project id from an object's `id` (a Project) or `projectId` (a ReleaseProjectVersion), or a bare UUID. */
    private fun resolveProjectId(context: PipelineContext, inputs: NodeInputs): UUID? {
        val element = inputs.first?.encode(context.json)
        if (element is JsonObject) {
            val raw = (element["projectId"] ?: element["id"]) as? JsonPrimitive
            return raw?.contentOrNull?.takeIf { it.isNotBlank() }?.let { UUID.parse(it) }
        }
        return inputs.first?.uuid(context.json)
    }
}
