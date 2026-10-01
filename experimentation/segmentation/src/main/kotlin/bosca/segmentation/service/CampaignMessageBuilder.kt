package bosca.segmentation.service

import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.PushOptions
import bosca.communications.model.PushAction
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.EmailCampaignContent
import bosca.segmentation.model.NotificationChannel
import bosca.segmentation.model.PushCampaignContent
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Builds channel-appropriate [Message] instances from campaign content and recipient profiles.
 * Translates campaign content JSON into the correct message format based on the campaign's
 * delivery channel (email, push, or banner).
 */
class CampaignMessageBuilder(
    private val json: Json
) {

    /**
     * Validates content submitted for a new or edited email campaign. New campaign revisions
     * must reference a hosted BML project and template; the legacy body fallback exists only
     * for already-persisted campaigns that may still be scheduled for delivery.
     */
    fun validateEmailCampaignContent(content: JsonElement?) {
        val email = decodeEmailContent(content)
        require(!email.project.isNullOrBlank()) { "Email campaign must specify a BML project" }
        require(!email.templateKey.isNullOrBlank()) { "Email campaign must specify a BML templateKey" }
    }

    /**
     * Builds a channel-appropriate [Message] for the given campaign and recipient profiles.
     *
     * For PUSH campaigns, the content JSON is deserialized into [PushCampaignContent] and
     * platform-specific fields are mapped to a [PushOptions] instance on the message.
     *
     * For EMAIL campaigns, the content JSON is deserialized into [EmailCampaignContent]. BML
     * template references are forwarded to the communications service for send-time rendering;
     * legacy text and HTML bodies remain supported for campaigns stored before that migration.
     *
     * Returns `null` for banner campaigns which are displayed in-app and don't produce messages.
     */
    fun buildCampaignMessage(campaign: Campaign, profileIds: List<UUID>): Message? {
        return when (campaign.channel) {
            NotificationChannel.EMAIL -> {
                val email = decodeEmailContent(campaign.content)
                val project = email.project?.takeIf { it.isNotBlank() }
                val templateKey = email.templateKey?.takeIf { it.isNotBlank() }
                when {
                    project != null && templateKey != null -> Message(
                        channels = listOf(MessageChannel.EMAIL),
                        recipients = profileIds,
                        bmlTemplate = MessageBmlTemplate(
                            project = project,
                            templateKey = templateKey,
                            payload = email.payload
                        )
                    )

                    project == null && templateKey == null -> {
                        val textBody = email.textBody
                        val htmlBody = email.htmlBody
                        val contentList = buildList {
                            if (!textBody.isNullOrEmpty()) {
                                add(MessageContent(type = MessageContentType.TEXT, content = textBody))
                            }
                            if (!htmlBody.isNullOrEmpty()) {
                                add(MessageContent(type = MessageContentType.HTML, content = htmlBody))
                            }
                            if (isEmpty()) {
                                add(MessageContent(type = MessageContentType.TEXT, content = ""))
                            }
                        }
                        Message(
                            channels = listOf(MessageChannel.EMAIL),
                            subject = email.subject ?: error("Legacy email campaign must have a subject"),
                            recipients = profileIds,
                            content = contentList
                        )
                    }

                    else -> error("Email campaign must specify both BML project and templateKey")
                }
            }

            NotificationChannel.PUSH -> {
                val push = campaign.content?.let {
                    json.decodeFromJsonElement(PushCampaignContent.serializer(), it)
                } ?: error("Push campaign must have content")
                val pushOptions = PushOptions(
                    defaultAction = push.defaultAction?.let { action ->
                        PushAction(action.id, action.label, action.url, action.destructive, action.data)
                    },
                    actions = push.actions.map { action ->
                        PushAction(action.id, action.label, action.url, action.destructive, action.data)
                    },
                    priority = push.priority,
                    sound = push.sound,
                    badge = push.badge,
                    ttl = push.ttl,
                    androidChannelId = push.androidChannelId,
                    androidTag = push.androidTag,
                    collapseKey = push.collapseKey,
                    threadId = push.threadId,
                    category = push.category,
                    interruptionLevel = push.interruptionLevel,
                    relevanceScore = push.relevanceScore,
                    mutableContent = push.mutableContent,
                    contentAvailable = push.contentAvailable,
                    data = push.data
                ).takeIf { it != PushOptions() }
                Message(
                    channels = listOf(MessageChannel.PUSH),
                    subject = push.title ?: error("Push campaign must have a title"),
                    recipients = profileIds,
                    content = listOf(MessageContent(type = MessageContentType.TEXT, content = push.body ?: "")),
                    pushOptions = pushOptions
                )
            }

            NotificationChannel.BANNER -> null
        }
    }

    private fun decodeEmailContent(content: JsonElement?): EmailCampaignContent = content?.let {
        json.decodeFromJsonElement(EmailCampaignContent.serializer(), it)
    } ?: error("Email campaign must have content")
}
