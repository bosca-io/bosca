package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject

/**
 * Fan-in: merges several inbound branches into one JSON object, keyed by the **target port** the
 * operator named on each edge (e.g. `{ "profile": …, "attributes": … }`). Each branch value is
 * encoded to JSON via its own carried serializer (native-safe). Output is a `JsonObject` ready for a
 * downstream `JSONata` node to shape/extract.
 */
@PipelineNodeType(
    category = NodeCategory.COMBINE,
    label = "Objects → Map",
    description = "Merges multiple inbound branches into one JSON object, keyed by each edge's port name.",
    group = "Core",
    subgroup = "Transform",
)
@Serializable
@SerialName("objectsToMap")
class ObjectsToMapNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val obj = buildJsonObject {
            for ((port, value) in inputs.asMap()) {
                put(port, value.encode(context.json))
            }
        }
        return PipelineValue.ofJson(obj)
    }
}
