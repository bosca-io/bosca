package bosca.community.pipeline

import bosca.communications.model.Message
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.MessageChannel
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.model.PushAction
import bosca.communications.model.PushOptions
import bosca.communications.service.MessageOutboxService
import bosca.community.events.PrayerCommentAddedEvent
import bosca.community.events.PrayerReactionAddedEvent
import bosca.community.events.PrayerReactionType
import bosca.community.service.PrayerService
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
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Queues push for the active owner of a prayer after another profile reacts to it. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Prayer Reaction Notification",
    description = "Queues push for the active prayer owner after another profile prays or likes.",
    group = "Social",
    subgroup = "Prayer",
    inputs = [
        InputSlot(
            name = "event",
            kind = SlotKind.OBJECT,
            type = PrayerReactionAddedEvent::class,
            typeLabel = "Prayer reaction added event",
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
            default = DEFAULT_PRAYER_REACTION_TEMPLATE,
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
@SerialName("prayer.notification.reaction")
class PrayerReactionNotificationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = DEFAULT_MESSAGE_PROJECT,
    val template: String = DEFAULT_PRAYER_REACTION_TEMPLATE,
    val notificationType: String = NotificationTypeKeys.SOCIAL_ACTIVITY,
    val push: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val event = PrayerReactionNotificationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendPrayerReactionNotification")
            put("push", push)
            event?.let {
                put("prayerId", it.prayerId.toString())
                put("reactorId", it.reactorId.toString())
                put("reaction", it.reaction.name)
            }
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        deliver(
            PrayerReactionNotificationNodeSerializer.deserialize(context, inputs).event,
            provide(),
            provide(),
            provide(),
            provide(),
        )
        return null
    }

    internal suspend fun deliver(
        event: PrayerReactionAddedEvent,
        prayerService: PrayerService,
        profileService: ProfileService,
        notificationConfiguration: SocialNotificationConfiguration,
        messageOutboxService: MessageOutboxService,
    ) {
        if (!push) return
        val prayer = prayerService.getRequest(event.prayerId) ?: return
        if (event.reactorId == prayer.profileId) return
        val reactionStillPresent = when (event.reaction) {
            PrayerReactionType.PRAYED -> prayerService.hasPrayed(event.prayerId, event.reactorId)
            PrayerReactionType.LIKED -> prayerService.hasLiked(event.prayerId, event.reactorId)
        }
        if (!reactionStillPresent) return
        val recipient = profileService.getAllByIds(listOf(prayer.profileId))
            .firstOrNull { it.id == prayer.profileId && !it.isDeleted }
            ?: return
        val reactor = profileService.getById(event.reactorId)
        if (reactor.isDeleted || recipient.id == reactor.id) return

        val actionUrl = notificationConfiguration.url("prayers/${event.prayerId}")
        val payload = buildJsonObject {
            put("actorName", reactor.name)
            put("reaction", event.reaction.name)
            put("actionUrl", actionUrl)
        }
        messageOutboxService.enqueueOnce(
            notificationId(event),
            Message(
                channels = listOf(MessageChannel.PUSH),
                sender = event.reactorId,
                recipients = listOf(recipient.id),
                pushOptions = PushOptions(
                    defaultAction = PushAction(id = "open-prayer", url = actionUrl),
                    sound = "default",
                    threadId = "prayer-${event.prayerId}",
                    category = "PRAYER_REACTION",
                    data = mapOf(
                        "event" to "prayer_reaction",
                        "prayer_id" to event.prayerId.toString(),
                        "reactor_id" to event.reactorId.toString(),
                        "reaction" to event.reaction.name.lowercase(),
                    ),
                ),
                type = notificationType,
                bmlTemplate = MessageBmlTemplate(project, template, payload),
            ),
        )
    }

    internal fun notificationId(event: PrayerReactionAddedEvent): UUID = deterministicId(
        "bosca.community.prayer.reaction.notification:${event.activityId}",
    )
}

/** Queues push for a prayer owner and, for replies, the active parent-comment author. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Prayer Comment Notification",
    description = "Queues push for the prayer owner and the author of a replied-to comment.",
    group = "Social",
    subgroup = "Prayer",
    inputs = [
        InputSlot(
            name = "event",
            kind = SlotKind.OBJECT,
            type = PrayerCommentAddedEvent::class,
            typeLabel = "Prayer comment added event",
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
            default = DEFAULT_PRAYER_COMMENT_TEMPLATE,
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
@SerialName("prayer.notification.comment")
class PrayerCommentNotificationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = DEFAULT_MESSAGE_PROJECT,
    val template: String = DEFAULT_PRAYER_COMMENT_TEMPLATE,
    val notificationType: String = NotificationTypeKeys.SOCIAL_ACTIVITY,
    val push: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val event = PrayerCommentNotificationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendPrayerCommentNotification")
            put("push", push)
            event?.let {
                put("prayerId", it.prayerId.toString())
                put("commentId", it.commentId)
                put("commenterId", it.commenterId.toString())
                it.parentId?.let { parentId -> put("parentId", parentId) }
            }
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        deliver(
            PrayerCommentNotificationNodeSerializer.deserialize(context, inputs).event,
            provide(),
            provide(),
            provide(),
            provide(),
        )
        return null
    }

    internal suspend fun deliver(
        event: PrayerCommentAddedEvent,
        prayerService: PrayerService,
        profileService: ProfileService,
        notificationConfiguration: SocialNotificationConfiguration,
        messageOutboxService: MessageOutboxService,
    ) {
        if (!push) return
        val prayer = prayerService.getRequest(event.prayerId) ?: return
        val comment = prayerService.getComment(event.commentId) ?: return
        if (
            comment.deleted ||
            comment.prayerId != event.prayerId ||
            comment.profileId != event.commenterId ||
            comment.parentId != event.parentId
        ) return

        val commenter = profileService.getById(event.commenterId)
        if (commenter.isDeleted) return
        val audiences = linkedMapOf<UUID, Boolean>()
        if (prayer.profileId != event.commenterId) audiences[prayer.profileId] = false
        event.parentId?.let { parentId ->
            val parent = prayerService.getComment(parentId)
            if (parent != null && !parent.deleted && parent.prayerId == event.prayerId && parent.profileId != event.commenterId) {
                audiences[parent.profileId] = true
            }
        }
        if (audiences.isEmpty()) return
        val activeRecipients = profileService.getAllByIds(audiences.keys.toList())
            .filterNot { it.isDeleted }
            .mapTo(mutableSetOf()) { it.id }
        val actionUrl = notificationConfiguration.url("prayers/${event.prayerId}?commentId=${event.commentId}")
        for ((recipientId, reply) in audiences) {
            if (recipientId !in activeRecipients) continue
            val payload = buildJsonObject {
                put("actorName", commenter.name)
                put("reply", reply)
                put("actionUrl", actionUrl)
            }
            messageOutboxService.enqueueOnce(
                notificationId(event, reply),
                Message(
                    channels = listOf(MessageChannel.PUSH),
                    sender = event.commenterId,
                    recipients = listOf(recipientId),
                    pushOptions = PushOptions(
                        defaultAction = PushAction(id = "open-prayer", url = actionUrl),
                        sound = "default",
                        threadId = "prayer-${event.prayerId}",
                        category = "PRAYER_COMMENT",
                        data = buildMap {
                            put("event", "prayer_comment")
                            put("prayer_id", event.prayerId.toString())
                            put("comment_id", event.commentId.toString())
                            put("commenter_id", event.commenterId.toString())
                            put("reply", reply.toString())
                            event.parentId?.let { put("parent_id", it.toString()) }
                        },
                    ),
                    type = notificationType,
                    bmlTemplate = MessageBmlTemplate(project, template, payload),
                ),
            )
        }
    }

    internal fun notificationId(event: PrayerCommentAddedEvent, reply: Boolean): UUID = deterministicId(
        "bosca.community.prayer.comment.notification:${event.prayerId}:${event.commentId}:${if (reply) "reply" else "owner"}",
    )
}

private fun deterministicId(key: String): UUID =
    UUID.parse(java.util.UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString())

private const val DEFAULT_MESSAGE_PROJECT = "bosca-messages"
private const val DEFAULT_PRAYER_REACTION_TEMPLATE = "prayer-reaction"
private const val DEFAULT_PRAYER_COMMENT_TEMPLATE = "prayer-comment"
