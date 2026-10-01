package bosca.collaboration.pipeline

import bosca.collaboration.events.ChatMentionEvent
import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.service.MessageOutboxService
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
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.serialization.UUID
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Queues configured notification channels for profiles mentioned outside the active channel. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Chat Mention Notification",
    description = "Queues email and/or push for eligible profiles mentioned outside the active channel.",
    group = "Social",
    subgroup = "Chat",
    inputs = [
        InputSlot(
            name = "event",
            kind = SlotKind.OBJECT,
            type = ChatMentionEvent::class,
            typeLabel = "Chat mention event",
        ),
    ],
    settings = [
        SettingSlot(
            name = "notificationType",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.NOTIFICATION_TYPE,
            label = "Notification Type",
            default = NotificationTypeKeys.SOCIAL_ACTIVITY,
            required = true,
        ),
        SettingSlot(name = "email", control = SettingControl.BOOLEAN, label = "Send Email", default = "true"),
        SettingSlot(name = "push", control = SettingControl.BOOLEAN, label = "Send Push", default = "true"),
    ],
)
@Serializable
@SerialName("collaboration.notification.chatMention")
class ChatMentionNotificationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val notificationType: String = NotificationTypeKeys.SOCIAL_ACTIVITY,
    val email: Boolean = true,
    val push: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val event = ChatMentionNotificationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendChatMentionNotification")
            put("email", email)
            put("push", push)
            event?.let {
                put("channelId", it.channelId.toString())
                put("sequence", it.sequence)
                put("recipientCount", it.recipientProfileIds.size)
            }
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        deliver(
            ChatMentionNotificationNodeSerializer.deserialize(context, inputs).event,
            provide(),
        )
        return null
    }

    internal suspend fun deliver(event: ChatMentionEvent, messageOutboxService: MessageOutboxService) {
        val channels = buildList {
            if (email) add(MessageChannel.EMAIL)
            if (push) add(MessageChannel.PUSH)
        }
        if (channels.isEmpty() || event.recipientProfileIds.isEmpty()) return
        val preview = renderPreview(event.content)
        messageOutboxService.enqueueOnce(
            notificationId(event),
            Message(
                channels = channels,
                subject = "${event.senderName} mentioned you in ${event.channelName}",
                sender = event.senderId,
                recipients = event.recipientProfileIds,
                content = listOf(MessageContent(MessageContentType.TEXT, preview)),
                type = notificationType,
            ),
        )
    }

    internal fun renderPreview(content: List<MessageContent>): String {
        val first = content.firstOrNull { it.type == MessageContentType.TEXT } ?: return "(media message)"
        val raw = first.content.trim()
        return if (raw.length <= PREVIEW_LIMIT) raw else raw.substring(0, PREVIEW_LIMIT - 1) + "…"
    }

    internal fun notificationId(event: ChatMentionEvent): UUID {
        val key = "bosca.collaboration.mention.notification:${event.channelId}:${event.sequence}"
        return UUID.parse(java.util.UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString())
    }

    companion object {
        const val PREVIEW_LIMIT = 280
    }
}
