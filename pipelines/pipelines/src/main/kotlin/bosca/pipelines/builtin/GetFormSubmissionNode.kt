package bosca.pipelines.builtin

import bosca.di.provide
import bosca.forms.model.FormSubmission
import bosca.forms.service.FormSubmissionService
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
 * Fetch: loads the full [FormSubmission] for an inbound submission UUID via the platform
 * [FormSubmissionService] (resolved natively via `provide`), under the run's principal. Feed it a
 * submission id — e.g. a Get Id step over a triggering form-submission event — and downstream nodes
 * read what they need: the submitted `attributes` (via a JSONata step), the `status`, or the
 * `formSchemaId`/`profileId` for further resolvers.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Form Submission",
    description = "Loads the full form submission for a submission id.",
    group = "Core",
    subgroup = "Forms",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Form submission id",
            description = "The form submission's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = FormSubmission::class,
            typeLabel = "Form submission",
            description = "The full form submission.",
        ),
    ],
)
@Serializable
@SerialName("getFormSubmission")
class GetFormSubmissionNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val submissionId = GetFormSubmissionNodeSerializer.deserialize(context, inputs).`in`
        val submission = provide<FormSubmissionService>().getById(submissionId)
            ?: error("Get Form Submission node '${name.ifBlank { id }}': form submission $submissionId not found")
        return GetFormSubmissionNodeSerializer.serialize(submission)
    }
}
