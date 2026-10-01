package bosca.pipelines.builtin

import bosca.di.provide
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.service.MessageService
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action: sends an email via the platform [MessageService] (resolved natively via `provide`) under
 * the run's principal. [recipients] are profile ids. A side-effect node — returns no output.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Email",
    description = "Sends an email to the selected profiles — a side effect, no output.",
    group = "Core",
    subgroup = "Messaging",
    settings = [
        SettingSlot(name = "recipients", control = SettingControl.LIST, label = "Recipients (profile IDs, comma-separated)", mono = true),
        SettingSlot(name = "subject", control = SettingControl.TEXT, label = "Subject"),
        SettingSlot(name = "body", control = SettingControl.TEXTAREA, label = "Body"),
        SettingSlot(name = "html", control = SettingControl.BOOLEAN, label = "HTML body", default = "true"),
    ],
)
@Serializable
@SerialName("sendEmail")
class SendEmailNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val recipients: List<UUID> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val html: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendEmail")
            put("recipients", JsonArray(recipients.map { JsonPrimitive(it.toString()) }))
            put("subject", subject)
            put("html", html)
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        provide<MessageService>().send(
            Message(
                channels = listOf(MessageChannel.EMAIL),
                subject = subject,
                recipients = recipients,
                content = listOf(
                    MessageContent(
                        type = if (html) MessageContentType.HTML else MessageContentType.TEXT,
                        content = body,
                    ),
                ),
            ),
        )
        return null
    }
}
