package bosca.pipelines.builtin

import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.service.MessageService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
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
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Action: sends a BML message template's email channel via the platform [MessageService]. Recipients and the
 * template payload flow in on input ports — this node is DATA-driven, which is how an event
 * becomes a templated email: the event triggers the pipeline, upstream nodes extract who and
 * shape what, this node sends. Wire a single [SlotKind.UUID] into `recipient` (from `Get Id`),
 * an array of profile ids into `recipients` (e.g. a JSONata result), or both — the send goes
 * to the union.
 *
 * The template renders INSIDE the send path, so everything a production send gets applies:
 * the registry's version pin, first-party click/open tracking, minted unsubscribe/preferences
 * links (single recipient), and `bml-inline` image attachments. A side-effect node — returns
 * no output.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Message Template",
    description = "Sends the email channel of a BML message template to the inbound recipients — a side effect, no output.",
    group = "Core",
    subgroup = "Messaging",
    inputs = [
        InputSlot(
            name = "recipient",
            kind = SlotKind.UUID,
            typeLabel = "Recipient (profile id)",
            description = "A single recipient profile id — wire it from Get Id. Combined with `recipients` when both are wired.",
            required = false,
        ),
        InputSlot(
            name = "recipients",
            kind = SlotKind.ARRAY,
            typeLabel = "Recipients (profile ids)",
            description = "An array of recipient profile ids (e.g. a JSONata result). Combined with `recipient` when both are wired.",
            required = false,
        ),
        InputSlot(
            name = "payload",
            kind = SlotKind.ANY,
            typeLabel = "Payload",
            description = "The template payload (JSON), decoded by the template's typed contract.",
            required = false,
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
        // NOT named "type": a setting name must not collide with the stored node's polymorphic
        // discriminator key ("type") — the flat node JSON has one namespace for both.
        SettingSlot(
            name = "notificationType", control = SettingControl.REFERENCE, reference = ReferenceSource.NOTIFICATION_TYPE,
            label = "Notification Type", required = true,
            description = "The notification-type key the preference gate and unsubscribe scoping use.",
        ),
    ],
)
@Serializable
@SerialName("sendEmailTemplate")
class SendEmailTemplateNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = "",
    val template: String = "",
    val notificationType: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode(), HasDeclaredInputs {

    /**
     * With a template picked, the `payload` port requires that template's typed payload contract
     * (`email:<project>/<template>`) — so an untyped wire is refused at connect time and the author
     * routes it through a Cast node (or declares a JSONata's output as the contract).
     */
    override val declaredInputTypes: Map<String, String>
        get() = templateReferenceOrNull(project, template)
            ?.let { mapOf("payload" to "email:$it") }
            ?: emptyMap()

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // deserializePartial (not deserialize): a dry run traces whatever is wired so far, so
        // missing recipients must not fail it.
        val slots = SendEmailTemplateNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendEmailTemplate")
            put("recipient", slots.recipient?.toString() ?: "")
            put("recipientCount", (slots.recipients?.size ?: 0) + (if (slots.recipient != null) 1 else 0))
            if (project.isNotBlank()) put("project", project)
            put("template", template)
            put("payload", slots.payload?.encode(context.json) ?: JsonNull)
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val who = "Send Message Template node '${name.ifBlank { id }}'"
        val slots = SendEmailTemplateNodeSerializer.deserialize(context, inputs)
        val recipients = (listOfNotNull(slots.recipient) + parseRecipients(slots.recipients, who)).distinct()
        if (recipients.isEmpty()) {
            error("$who: no recipients — wire a profile id into 'recipient' and/or an array into 'recipients'")
        }
        val reference = resolveTemplate(project, template, who)
        provide<MessageService>().send(
            Message(
                channels = listOf(MessageChannel.EMAIL),
                recipients = recipients,
                type = notificationType.takeIf { it.isNotBlank() },
                bmlTemplate = MessageBmlTemplate(
                    reference.first,
                    reference.second,
                    slots.payload?.encode(context.json),
                ),
            ),
        )
        return null
    }

    private fun parseRecipients(array: JsonArray?, who: String): List<UUID> =
        array.orEmpty().map { element ->
            runCatching { UUID.parse(element.jsonPrimitive.contentOrNull.orEmpty()) }.getOrElse {
                error("$who: 'recipients' element is not a UUID: $element")
            }
        }
}

/**
 * The `<project>/<template-key>` reference the node's settings resolve to, or `null` while the
 * template is not (fully) picked — the no-throw sibling of [resolveTemplate], for surfaces that
 * *derive* from the reference (the payload port's declared input type) rather than execute with it.
 */
internal fun templateReferenceOrNull(project: String, template: String): String? {
    val reference = when {
        '/' in template -> template
        project.isNotBlank() && template.isNotBlank() -> "$project/$template"
        else -> return null
    }
    val projectPart = reference.substringBefore('/', "")
    val keyPart = reference.substringAfter('/', "")
    return reference.takeIf { projectPart.isNotBlank() && keyPart.isNotBlank() }
}

/**
 * Resolves the `(project, template-key)` pair from the node's two settings. The authoring surface
 * stores them separately (a [project] picked from the hosted-project list plus a bare [template]
 * key); a [template] containing a slash is the legacy single-setting `project/template-key` form
 * and wins, so pipelines stored before the split keep executing unchanged.
 */
internal fun resolveTemplate(project: String, template: String, who: String): Pair<String, String> {
    if ('/' in template) return parseTemplate(template, who)
    if (project.isBlank() || template.isBlank()) {
        error("$who: pick a message project and a template (or set template to 'project/template-key')")
    }
    return project to template
}

/**
 * Splits a `project/template-key` reference. One key on the authoring surface, the two-part
 * artifact reference underneath.
 */
internal fun parseTemplate(template: String, who: String): Pair<String, String> {
    val project = template.substringBefore('/', "")
    val templateKey = template.substringAfter('/', "")
    if (project.isBlank() || templateKey.isBlank()) {
        error("$who: template must be 'project/template-key', got '$template'")
    }
    return project to templateKey
}
