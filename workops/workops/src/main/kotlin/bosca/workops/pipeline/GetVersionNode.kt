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
import bosca.workops.model.version.Version
import bosca.workops.service.VersionService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resolver node contributed by `workops`: loads a project [Version] by id, so a release relay can read its
 * name — e.g. `pv → Get Version → JSONata(name)` to build a tag. The version id comes from a bare inbound
 * UUID, or an object's `versionId` (a `ReleaseProjectVersion`) or `id` (a `Version`). FETCH — pure, no side
 * effect.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Version",
    description = "Loads a project version by id — e.g. to read its name for a tag.",
    group = "WorkOps",
    subgroup = "Releases",
    inputs = [
        InputSlot(
            name = "in", kind = SlotKind.UUID, typeLabel = "Version id",
            description = "The version's UUID, or an object carrying it (a ReleaseProjectVersion's versionId, or a Version's id).",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.OBJECT, type = Version::class, typeLabel = "Version",
            description = "The resolved version.",
        ),
    ],
)
@Serializable
@SerialName("version.get")
class GetVersionNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        val versionId = resolveVersionId(context, inputs)
            ?: error("Get Version node '$label' requires a version id (a UUID, or an object with versionId/id)")
        val version = provide<VersionService>().getById(versionId)
            ?: error("Get Version node '$label': version $versionId not found")
        return GetVersionNodeSerializer.serialize(version)
    }

    /** The version id from an object's `versionId` (a ReleaseProjectVersion) or `id` (a Version), or a bare UUID. */
    private fun resolveVersionId(context: PipelineContext, inputs: NodeInputs): UUID? {
        val element = inputs.first?.encode(context.json)
        if (element is JsonObject) {
            val raw = (element["versionId"] ?: element["id"]) as? JsonPrimitive
            return raw?.contentOrNull?.takeIf { it.isNotBlank() }?.let { UUID.parse(it) }
        }
        return inputs.first?.uuid(context.json)
    }
}
