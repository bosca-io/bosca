package bosca.pipelines.builtin

import bosca.communications.model.EmailPreview
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.service.BmlMessageTemplateRendererService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.HasDeclaredInputs
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action: renders a BML message template's email channel with the inbound value as the payload and
 * outputs the rendered [EmailPreview] — project, template key, the artifact version that
 * rendered, subject, self-contained HTML (`bml-inline` images as `data:` URIs), and the
 * plain-text alternative. The render goes through the production resolution path (a registry
 * version pin applies; [version] overrides it).
 *
 * This node GENERATES the email; it sends nothing. To deliver, use `Send Message Template` —
 * which renders inside the send path so click tracking, unsubscribe links, and inline image
 * attachments all apply.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Render Message Template",
    description = "Renders the email channel of a BML message template and outputs the rendered email.",
    group = "Core",
    subgroup = "Messaging",
    inputs = [
        InputSlot(
            name = "payload",
            kind = SlotKind.ANY,
            typeLabel = "Payload",
            description = "The template payload (JSON), decoded by the template's typed contract.",
            required = false,
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = EmailPreview::class,
            typeLabel = "Rendered email",
            description = "The rendered email: subject, self-contained HTML, plain text, and the version that rendered.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "project", control = SettingControl.REFERENCE, reference = ReferenceSource.MESSAGE_PROJECT,
            label = "Message Project", required = true, mono = true,
            description = "The message project (hosted by the BML Message Server) the template lives in.",
        ),
        SettingSlot(
            name = "template", control = SettingControl.REFERENCE, reference = ReferenceSource.MESSAGE_TEMPLATE,
            label = "Template", required = true, mono = true,
            description = "The template within the chosen message project. Its typed payload contract is shown below the payload port.",
        ),
        SettingSlot(
            name = "version", control = SettingControl.TEXT, label = "Version (optional)", mono = true,
            description = "A specific published version; empty renders the pinned/active version.",
        ),
        SettingSlot(name = "recipientName", control = SettingControl.TEXT, label = "Recipient Name (optional)"),
        SettingSlot(name = "recipientEmail", control = SettingControl.TEXT, label = "Recipient Email (optional)"),
    ],
)
@Serializable
@SerialName("renderEmailTemplate")
class RenderEmailTemplateNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = "",
    val template: String = "",
    val version: String = "",
    val recipientName: String = "",
    val recipientEmail: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode(), HasDeclaredInputs {

    /** With a template picked, the `payload` port requires that template's typed payload contract — see [SendEmailTemplateNode.declaredInputTypes]. */
    override val declaredInputTypes: Map<String, String>
        get() = templateReferenceOrNull(project, template)
            ?.let { mapOf("payload" to "email:$it") }
            ?: emptyMap()

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Rendering has no side effects, but it needs a reachable message server — a dry run
        // records the would-be render instead of depending on one.
        val slots = RenderEmailTemplateNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "renderEmailTemplate")
            if (project.isNotBlank()) put("project", project)
            put("template", template)
            if (version.isNotBlank()) put("version", version)
            put("payload", slots.payload?.encode(context.json) ?: JsonNull)
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val slots = RenderEmailTemplateNodeSerializer.deserialize(context, inputs)
        val reference = resolveTemplate(project, template, "Render Message Template node '${name.ifBlank { id }}'")
        val rendered = provide<BmlMessageTemplateRendererService>().preview(
            MessageBmlTemplate(reference.first, reference.second, slots.payload?.encode(context.json)),
            version = version.takeIf { it.isNotBlank() },
            recipientName = recipientName.takeIf { it.isNotBlank() },
            recipientEmail = recipientEmail.takeIf { it.isNotBlank() },
        )
        return RenderEmailTemplateNodeSerializer.serialize(rendered)
    }
}
