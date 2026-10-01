package bosca.chat.pipeline

import bosca.chat.events.ChatChannelInvitationSentEvent
import bosca.chat.events.ChatChannelJoinedEvent
import bosca.chat.events.ChatMessageReactionAddedEvent
import bosca.chat.events.ChatMessageSentEvent
import bosca.chat.model.ChatChannelInvitationStatus
import bosca.chat.model.ChatChannelType
import bosca.chat.service.ChatChannelInvitationService
import bosca.chat.service.ChatService
import bosca.communications.model.Message
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.model.PushAction
import bosca.communications.model.PushAttachment
import bosca.communications.model.PushConversation
import bosca.communications.model.PushOptions
import bosca.communications.model.PushRichContent
import bosca.communications.service.MessageOutboxService
import bosca.content.metadata.service.MetadataService
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
import bosca.profile.configuration.SocialNotificationConfiguration
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Event-pipeline action that resolves the current audience for a chat message and queues the
 * configured BML message channels. The event remains cheap to publish; roster, profile, and
 * attachment lookups happen asynchronously in the triggered pipeline.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Chat Message Notification",
    description = "Queues email and/or push for active channel members.",
    group = "Social",
    subgroup = "Chat",
    inputs = [
        InputSlot(
            name = "event",
            kind = SlotKind.OBJECT,
            type = ChatMessageSentEvent::class,
            typeLabel = "Chat message sent event",
        ),
    ],
    settings = [
        SettingSlot(
            name = "project",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.MESSAGE_PROJECT,
            label = "Message Project",
            default = DEFAULT_MESSAGE_PROJECT,
            required = true,
            mono = true,
        ),
        SettingSlot(
            name = "template",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.MESSAGE_TEMPLATE,
            label = "Template",
            default = DEFAULT_CHAT_MESSAGE_TEMPLATE,
            required = true,
            mono = true,
        ),
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
@SerialName("chat.notification.message")
class ChatMessageNotificationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = DEFAULT_MESSAGE_PROJECT,
    val template: String = DEFAULT_CHAT_MESSAGE_TEMPLATE,
    val notificationType: String = NotificationTypeKeys.SOCIAL_ACTIVITY,
    val email: Boolean = true,
    val push: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val event = ChatMessageNotificationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendChatMessageNotification")
            put("email", email)
            put("push", push)
            event?.let {
                put("channelId", it.channelId.toString())
                put("sequence", it.sequence)
            }
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        deliver(
            ChatMessageNotificationNodeSerializer.deserialize(context, inputs).event,
            provide(),
            provide(),
            provide(),
            provide(),
            provide(),
        )
        return null
    }

    internal suspend fun deliver(
        event: ChatMessageSentEvent,
        chatService: ChatService,
        profileService: ProfileService,
        metadataService: MetadataService,
        messageOutboxService: MessageOutboxService,
        notificationConfiguration: SocialNotificationConfiguration,
    ) {
        val channels = notificationChannels(event.content)
        if (channels.isEmpty()) return
        val channel = chatService.getById(event.channelId) ?: return
        val recipients = mutableListOf<UUID>()
        var offset = 0L
        while (true) {
            val page = chatService.getMembers(event.channelId, offset, MEMBER_BATCH_SIZE)
            val memberIds = page.map { it.profileId }.filterNot { it == event.senderId }
            val activeMemberIds = profileService.getAllByIds(memberIds)
                .filterNot { it.isDeleted }
                .mapTo(mutableSetOf()) { it.id }
            recipients += memberIds.filter { it in activeMemberIds }
            if (page.size < MEMBER_BATCH_SIZE) break
            offset += page.size
        }
        if (recipients.isEmpty()) return

        val sender = profileService.getById(event.senderId)
        val direct = channel.type == ChatChannelType.DIRECT
        val currentMessageText = messageText(event.content)
        val imageAttachment = currentImageAttachment(event.content, metadataService, notificationConfiguration)
        val actionUrl = notificationConfiguration
            .url("communications/channels?channelId=${event.channelId}&sequence=${event.sequence}")
        val payload = buildJsonObject {
            put("senderName", sender.name)
            if (!direct) put("channelName", channel.name)
            put("direct", direct)
            put("channelId", event.channelId.toString())
            put("sequence", event.sequence)
            put("actionUrl", actionUrl)
        }
        val pushOptions = PushOptions(
            defaultAction = PushAction(id = "open-chat", url = actionUrl),
            sound = "default",
            threadId = "chat-${event.channelId}",
            category = "CHAT_MESSAGE",
            data = mapOf(
                "event" to "chat_message",
                "channel_id" to event.channelId.toString(),
                "sender_id" to event.senderId.toString(),
                "sequence" to event.sequence.toString(),
            ),
            richContent = PushRichContent(
                attachments = listOfNotNull(imageAttachment),
                conversation = PushConversation(
                    id = event.channelId.toString(),
                    title = if (direct) sender.name else channel.name,
                    messageId = event.sequence.toString(),
                    senderId = event.senderId.toString(),
                    senderName = sender.name,
                    body = currentMessageText.orEmpty().truncateUtf8(MAX_PUSH_MESSAGE_BYTES),
                    sentAtEpochMilliseconds = event.sentAt.toInstant().toEpochMilli(),
                    groupConversation = !direct,
                ),
            ),
        )
        messageOutboxService.enqueueOnce(
            notificationId(event),
            Message(
                channels = channels,
                sender = event.senderId,
                recipients = recipients,
                pushOptions = pushOptions,
                type = notificationType,
                bmlTemplate = MessageBmlTemplate(project, template, payload),
            ),
        )
    }

    internal fun messageText(content: List<MessageContent>): String? = content
        .firstOrNull { it.type == MessageContentType.TEXT }
        ?.content
        ?.takeIf { it.isNotBlank() }

    internal fun attachmentCount(content: List<MessageContent>): Int = content.count {
        when (it.type) {
            MessageContentType.TEXT,
            MessageContentType.HTML,
            MessageContentType.MENTION -> false
            else -> true
        }
    }

    internal fun notificationChannels(content: List<MessageContent>): List<MessageChannel> {
        val channels = mutableListOf<MessageChannel>()
        if (email) channels += MessageChannel.EMAIL
        val pushSafe = messageText(content) != null || attachmentCount(content) > 0 || content.none {
            it.type == MessageContentType.HTML
        }
        if (push && pushSafe) channels += MessageChannel.PUSH
        return channels
    }

    internal fun notificationId(event: ChatMessageSentEvent): UUID = deterministicId(
        "bosca.chat.message.notification:${event.channelId}:${event.sequence}",
    )

    private suspend fun currentImageAttachment(
        content: List<MessageContent>,
        metadataService: MetadataService,
        notificationConfiguration: SocialNotificationConfiguration,
    ): PushAttachment? {
        for (item in content) {
            if (item.type != MessageContentType.METADATA && item.type != MessageContentType.IMAGE) continue
            val metadataId = metadataId(item) ?: continue
            val metadata = metadataService.getById(metadataId)
                ?.takeIf { it.contentType.startsWith("image/") }
                ?: continue
            val resized = resizedImage(metadata.attributes as? JsonObject)
            val path = buildString {
                append("content/image/")
                append(metadata.id)
                resized?.let { append('.').append(it.format) }
                resized?.let {
                    append("?key=")
                    append(URLEncoder.encode(it.key, StandardCharsets.UTF_8))
                }
            }
            return PushAttachment(
                id = metadata.id.toString(),
                url = notificationConfiguration.url(path),
                mediaType = resized?.mediaType ?: metadata.contentType,
                altText = metadata.name,
            )
        }
        return null
    }

    private fun metadataId(content: MessageContent): UUID? {
        runCatching { UUID.parse(content.content) }.getOrNull()?.let { return it }
        if (content.type == MessageContentType.IMAGE) {
            val path = runCatching { URI(content.content).path }.getOrNull() ?: return null
            val id = path.substringAfterLast("/content/image/", missingDelimiterValue = "")
                .substringBefore('.')
            return runCatching { UUID.parse(id) }.getOrNull()
        }
        return runCatching {
            Json.parseToJsonElement(content.content).jsonObject["id"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let(UUID::parse)
        }.getOrNull()
    }

    private fun resizedImage(attributes: JsonObject?): ResizedImage? {
        if (attributes == null) return null
        for ((format, mediaType) in listOf("webp" to "image/webp", "jpeg" to "image/jpeg")) {
            val variants = attributes[format] as? JsonObject ?: continue
            for (size in listOf("small", "medium")) {
                val key = (variants[size] as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: continue
                return ResizedImage(format, mediaType, key)
            }
        }
        return null
    }

    private data class ResizedImage(val format: String, val mediaType: String, val key: String)

    internal companion object {
        const val MAX_PUSH_MESSAGE_BYTES = 1024
        const val MEMBER_BATCH_SIZE = 100
    }
}

/** Queues a push notification for the author of a message after another profile reacts to it. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Chat Reaction Notification",
    description = "Queues push for the active author of a reacted-to chat message.",
    group = "Social",
    subgroup = "Chat",
    inputs = [
        InputSlot(
            name = "event",
            kind = SlotKind.OBJECT,
            type = ChatMessageReactionAddedEvent::class,
            typeLabel = "Chat message reaction added event",
        ),
    ],
    settings = [
        SettingSlot(
            name = "project",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.MESSAGE_PROJECT,
            label = "Message Project",
            default = DEFAULT_MESSAGE_PROJECT,
            required = true,
            mono = true,
        ),
        SettingSlot(
            name = "template",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.MESSAGE_TEMPLATE,
            label = "Template",
            default = DEFAULT_CHAT_REACTION_TEMPLATE,
            required = true,
            mono = true,
        ),
        SettingSlot(
            name = "notificationType",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.NOTIFICATION_TYPE,
            label = "Notification Type",
            default = NotificationTypeKeys.SOCIAL_ACTIVITY,
            required = true,
        ),
        SettingSlot(name = "push", control = SettingControl.BOOLEAN, label = "Send Push", default = "true"),
    ],
)
@Serializable
@SerialName("chat.notification.reaction")
class ChatMessageReactionNotificationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = DEFAULT_MESSAGE_PROJECT,
    val template: String = DEFAULT_CHAT_REACTION_TEMPLATE,
    val notificationType: String = NotificationTypeKeys.SOCIAL_ACTIVITY,
    val push: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val event = ChatMessageReactionNotificationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendChatMessageReactionNotification")
            put("push", push)
            event?.let {
                put("channelId", it.channelId.toString())
                put("sequence", it.sequence)
                put("messageAuthorId", it.messageAuthorId.toString())
            }
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        deliver(
            ChatMessageReactionNotificationNodeSerializer.deserialize(context, inputs).event,
            provide(),
            provide(),
            provide(),
            provide(),
        )
        return null
    }

    internal suspend fun deliver(
        event: ChatMessageReactionAddedEvent,
        chatService: ChatService,
        profileService: ProfileService,
        notificationConfiguration: SocialNotificationConfiguration,
        messageOutboxService: MessageOutboxService,
    ) {
        if (!push || event.reactorId == event.messageAuthorId) return
        val message = chatService.getMessage(event.channelId, event.sequence) ?: return
        if (message.senderId != event.messageAuthorId) return
        if (chatService.getMember(event.channelId, event.messageAuthorId) == null) return
        if (!chatService.canParticipate(event.messageAuthorId)) return
        if (!chatService.canParticipate(event.reactorId)) return

        val reactor = profileService.getById(event.reactorId)
        val actionUrl = notificationConfiguration
            .url("communications/channels?channelId=${event.channelId}&sequence=${event.sequence}")
        val payload = buildJsonObject {
            put("reactorName", reactor.name)
            put("channelId", event.channelId.toString())
            put("sequence", event.sequence)
            put("reactionId", event.reactionId.toString())
            put("actionUrl", actionUrl)
        }
        messageOutboxService.enqueueOnce(
            notificationId(event),
            Message(
                channels = listOf(MessageChannel.PUSH),
                sender = event.reactorId,
                recipients = listOf(event.messageAuthorId),
                pushOptions = PushOptions(
                    defaultAction = PushAction(id = "open-chat", url = actionUrl),
                    sound = "default",
                    threadId = "chat-${event.channelId}",
                    category = "CHAT_MESSAGE_REACTION",
                    data = mapOf(
                        "event" to "chat_message_reaction",
                        "channel_id" to event.channelId.toString(),
                        "sequence" to event.sequence.toString(),
                        "reactor_id" to event.reactorId.toString(),
                    ),
                ),
                type = notificationType,
                bmlTemplate = MessageBmlTemplate(project, template, payload),
            ),
        )
    }

    internal fun notificationId(event: ChatMessageReactionAddedEvent): UUID = deterministicId(
        "bosca.chat.reaction.notification:${event.reactionId}",
    )
}

/** Event-pipeline action that queues a notification while a channel invitation remains actionable. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Channel Invitation Notification",
    description = "Queues email and/or push for the eligible profile invited to a chat channel.",
    group = "Social",
    subgroup = "Chat",
    inputs = [
        InputSlot(
            name = "event",
            kind = SlotKind.OBJECT,
            type = ChatChannelInvitationSentEvent::class,
            typeLabel = "Channel invitation sent event",
        ),
    ],
    settings = [
        SettingSlot(
            name = "project",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.MESSAGE_PROJECT,
            label = "Message Project",
            default = DEFAULT_MESSAGE_PROJECT,
            required = true,
            mono = true,
        ),
        SettingSlot(
            name = "template",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.MESSAGE_TEMPLATE,
            label = "Template",
            default = DEFAULT_INVITATION_TEMPLATE,
            required = true,
            mono = true,
        ),
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
@SerialName("chat.notification.invitation")
class ChatChannelInvitationNotificationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = DEFAULT_MESSAGE_PROJECT,
    val template: String = DEFAULT_INVITATION_TEMPLATE,
    val notificationType: String = NotificationTypeKeys.SOCIAL_ACTIVITY,
    val email: Boolean = true,
    val push: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val event = ChatChannelInvitationNotificationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendChannelInvitationNotification")
            put("email", email)
            put("push", push)
            event?.let { put("invitationId", it.invitationId.toString()) }
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        deliver(
            ChatChannelInvitationNotificationNodeSerializer.deserialize(context, inputs).event,
            provide(),
            provide(),
            provide(),
            provide(),
            provide(),
        )
        return null
    }

    internal suspend fun deliver(
        event: ChatChannelInvitationSentEvent,
        invitationService: ChatChannelInvitationService,
        chatService: ChatService,
        profileService: ProfileService,
        notificationConfiguration: SocialNotificationConfiguration,
        messageOutboxService: MessageOutboxService,
    ) {
        val channels = selectedChannels(email, push)
        if (channels.isEmpty()) return
        val invitation = invitationService.getById(event.invitationId) ?: return
        if (invitation.status != ChatChannelInvitationStatus.PENDING) return
        if (!chatService.canParticipate(event.inviteeProfileId)) return
        val channel = chatService.getById(event.channelId) ?: return
        val inviter = profileService.getById(event.inviterProfileId)
        val actionUrl = notificationConfiguration
            .url("communications/channel-invitations?invitationId=${event.invitationId}")
        val payload = buildJsonObject {
            put("inviterName", inviter.name)
            put("channelName", channel.name)
            put("actionUrl", actionUrl)
        }
        messageOutboxService.enqueueOnce(
            notificationId(event.invitationId),
            Message(
                channels = channels,
                sender = event.inviterProfileId,
                recipients = listOf(event.inviteeProfileId),
                pushOptions = PushOptions(
                    defaultAction = PushAction(id = "view-invitation", url = actionUrl),
                    sound = "default",
                    threadId = "chat-invitation-${event.invitationId}",
                    category = "CHAT_CHANNEL_INVITATION",
                    data = mapOf(
                        "event" to "chat_channel_invitation",
                        "invitation_id" to event.invitationId.toString(),
                        "channel_id" to event.channelId.toString(),
                        "inviter_profile_id" to event.inviterProfileId.toString(),
                    ),
                ),
                type = notificationType,
                bmlTemplate = MessageBmlTemplate(project, template, payload),
            ),
        )
    }

    internal fun notificationId(invitationId: UUID): UUID = deterministicId(
        "bosca.chat.invitation.notification:$invitationId",
    )
}

/** Event-pipeline action that queues a notification for existing members after a profile joins. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Channel Joined Notification",
    description = "Queues email and/or push for active members after a profile joins a chat channel.",
    group = "Social",
    subgroup = "Chat",
    inputs = [
        InputSlot(
            name = "event",
            kind = SlotKind.OBJECT,
            type = ChatChannelJoinedEvent::class,
            typeLabel = "Channel joined event",
        ),
    ],
    settings = [
        SettingSlot(
            name = "project",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.MESSAGE_PROJECT,
            label = "Message Project",
            default = DEFAULT_MESSAGE_PROJECT,
            required = true,
            mono = true,
        ),
        SettingSlot(
            name = "template",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.MESSAGE_TEMPLATE,
            label = "Template",
            default = DEFAULT_JOINED_TEMPLATE,
            required = true,
            mono = true,
        ),
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
@SerialName("chat.notification.joined")
class ChatChannelJoinedNotificationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = DEFAULT_MESSAGE_PROJECT,
    val template: String = DEFAULT_JOINED_TEMPLATE,
    val notificationType: String = NotificationTypeKeys.SOCIAL_ACTIVITY,
    val email: Boolean = true,
    val push: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val event = ChatChannelJoinedNotificationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendChannelJoinedNotification")
            put("email", email)
            put("push", push)
            event?.let { put("joinId", it.joinId.toString()) }
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        deliver(
            ChatChannelJoinedNotificationNodeSerializer.deserialize(context, inputs).event,
            provide(),
            provide(),
            provide(),
            provide(),
        )
        return null
    }

    internal suspend fun deliver(
        event: ChatChannelJoinedEvent,
        chatService: ChatService,
        profileService: ProfileService,
        notificationConfiguration: SocialNotificationConfiguration,
        messageOutboxService: MessageOutboxService,
    ) {
        val channels = selectedChannels(email, push)
        if (channels.isEmpty()) return
        val channel = chatService.getById(event.channelId) ?: return
        if (chatService.getMember(event.channelId, event.profileId) == null) return
        if (!chatService.canParticipate(event.profileId)) return
        val recipients = mutableListOf<UUID>()
        var offset = 0L
        while (true) {
            val page = chatService.getMembers(event.channelId, offset, MEMBER_BATCH_SIZE)
            val memberIds = page.map { it.profileId }.filterNot { it == event.profileId }
            val activeMemberIds = profileService.getAllByIds(memberIds)
                .filterNot { it.isDeleted }
                .mapTo(mutableSetOf()) { it.id }
            recipients += memberIds.filter { it in activeMemberIds }
            if (page.size < MEMBER_BATCH_SIZE) break
            offset += page.size
        }
        if (recipients.isEmpty()) return

        val joinedProfile = profileService.getById(event.profileId)
        val actionUrl = notificationConfiguration.url("communications/channels?channelId=${event.channelId}")
        val payload = buildJsonObject {
            put("joinedProfileName", joinedProfile.name)
            put("channelName", channel.name)
            put("actionUrl", actionUrl)
        }
        messageOutboxService.enqueueOnce(
            notificationId(event.joinId),
            Message(
                channels = channels,
                sender = event.profileId,
                recipients = recipients,
                pushOptions = PushOptions(
                    defaultAction = PushAction(id = "open-chat", url = actionUrl),
                    sound = "default",
                    threadId = "chat-${event.channelId}",
                    category = "CHAT_CHANNEL_JOINED",
                    data = mapOf(
                        "event" to "chat_channel_joined",
                        "channel_id" to event.channelId.toString(),
                        "joined_profile_id" to event.profileId.toString(),
                    ),
                ),
                type = notificationType,
                bmlTemplate = MessageBmlTemplate(project, template, payload),
            ),
        )
    }

    internal fun notificationId(joinId: UUID): UUID = deterministicId(
        "bosca.chat.joined.notification:$joinId",
    )

    internal companion object {
        const val MEMBER_BATCH_SIZE = 100
    }
}

private fun selectedChannels(email: Boolean, push: Boolean): List<MessageChannel> = buildList {
    if (email) add(MessageChannel.EMAIL)
    if (push) add(MessageChannel.PUSH)
}

private fun deterministicId(key: String): UUID =
    UUID.parse(java.util.UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString())

private fun String.truncateUtf8(maxBytes: Int): String {
    if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this
    val result = StringBuilder()
    var offset = 0
    var bytes = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        val value = String(Character.toChars(codePoint))
        val encodedBytes = value.toByteArray(Charsets.UTF_8).size
        if (bytes + encodedBytes > maxBytes) break
        result.append(value)
        bytes += encodedBytes
        offset += Character.charCount(codePoint)
    }
    return result.toString()
}

private const val DEFAULT_MESSAGE_PROJECT = "bosca-messages"
private const val DEFAULT_CHAT_MESSAGE_TEMPLATE = "chat-message"
private const val DEFAULT_CHAT_REACTION_TEMPLATE = "chat-reaction"
private const val DEFAULT_INVITATION_TEMPLATE = "channel-invitation"
private const val DEFAULT_JOINED_TEMPLATE = "channel-joined"
