package bosca.pipelines.builtin

import bosca.di.provide
import bosca.forms.model.FormSchema
import bosca.forms.service.FormSchemaService
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Fetch: loads the full [FormSchema] for an inbound form UUID via the platform [FormSchemaService]
 * (resolved natively via `provide`), under the run's principal. Feed it a form id — e.g. a Get Id
 * step over a form submission's `formSchemaId` — and downstream nodes read what they need: the
 * form's `key`, `name`, the JSON `schema`/`uiSchema`, or the `published` state.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Form",
    description = "Loads the full form definition for a form id.",
    group = "Core",
    subgroup = "Forms",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Form id",
            description = "The form's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = FormSchema::class,
            typeLabel = "Form",
            description = "The full form definition.",
        ),
    ],
)
@Serializable
@SerialName("getForm")
class GetFormNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val formId = GetFormNodeSerializer.deserialize(context, inputs).`in`
        val form = provide<FormSchemaService>().getById(formId)
            ?: error("Get Form node '${name.ifBlank { id }}': form $formId not found")
        return GetFormNodeSerializer.serialize(form)
    }
}
