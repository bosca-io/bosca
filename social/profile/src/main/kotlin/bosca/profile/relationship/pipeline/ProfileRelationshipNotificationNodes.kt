package bosca.profile.relationship.pipeline

import bosca.communications.model.Message
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.MessageChannel
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.model.PushAction
import bosca.communications.model.PushOptions
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
import bosca.profile.configuration.SocialNotificationConfiguration
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.events.ProfileRelationshipRequestApproved
import bosca.profile.relationship.events.ProfileRelationshipRequested
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.relationship.service.ProfileRelationshipRequestService
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.serialization.UUID
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Queues configured notification channels while a relationship request remains actionable. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Relationship Request Notification",
    description = "Queues email and/or push for the target of a pending relationship request.",
    group = "Social",
    subgroup = "Relationships",
    inputs = [
        InputSlot(
            name = "event",
            kind = SlotKind.OBJECT,
            type = ProfileRelationshipRequested::class,
            typeLabel = "Profile relationship requested event",
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
            default = DEFAULT_REQUEST_TEMPLATE,
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
@SerialName("profile.relationship.notification.requested")
class ProfileRelationshipRequestedNotificationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = DEFAULT_MESSAGE_PROJECT,
    val template: String = DEFAULT_REQUEST_TEMPLATE,
    val notificationType: String = NotificationTypeKeys.SOCIAL_ACTIVITY,
    val email: Boolean = true,
    val push: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val event = ProfileRelationshipRequestedNotificationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendRelationshipRequestNotification")
            put("email", email)
            put("push", push)
            event?.let { put("requestId", it.requestId.toString()) }
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        deliver(
            ProfileRelationshipRequestedNotificationNodeSerializer.deserialize(context, inputs).event,
            provide(),
            provide(),
            provide(),
            provide(),
        )
        return null
    }

    internal suspend fun deliver(
        event: ProfileRelationshipRequested,
        requestService: ProfileRelationshipRequestService,
        profileService: ProfileService,
        notificationConfiguration: SocialNotificationConfiguration,
        messageOutboxService: MessageOutboxService,
    ) {
        val channels = selectedChannels(email, push)
        if (channels.isEmpty()) return
        val request = requestService.getById(event.requestId) ?: return
        if (request.status != ProfileRelationshipRequestStatus.PENDING) return
        val requester = profileService.getById(event.requesterProfileId)
        val target = profileService.getById(event.targetProfileId)
        if (requester.isDeleted || target.isDeleted) return
        val actionUrl = notificationConfiguration
            .profileUrl("relationships?requestId=${event.requestId}")
        val payload = buildJsonObject {
            put("requesterName", requester.name)
            put("actionUrl", actionUrl)
        }
        messageOutboxService.enqueueOnce(
            notificationId(event.requestId, "requested"),
            Message(
                channels = channels,
                sender = event.requesterProfileId,
                recipients = listOf(event.targetProfileId),
                pushOptions = PushOptions(
                    defaultAction = PushAction(id = "view-request", url = actionUrl),
                    sound = "default",
                    threadId = "relationship-request-${event.requestId}",
                    category = "RELATIONSHIP_REQUEST",
                    data = mapOf(
                        "event" to "relationship_request",
                        "request_id" to event.requestId.toString(),
                        "requester_profile_id" to event.requesterProfileId.toString(),
                        "relationship_type" to event.type,
                    ),
                ),
                type = notificationType,
                bmlTemplate = MessageBmlTemplate(project, template, payload),
            ),
        )
    }
}

/** Queues configured notification channels after a requested relationship is approved. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send Relationship Added Notification",
    description = "Queues email and/or push after a requested relationship is added.",
    group = "Social",
    subgroup = "Relationships",
    inputs = [
        InputSlot(
            name = "event",
            kind = SlotKind.OBJECT,
            type = ProfileRelationshipRequestApproved::class,
            typeLabel = "Profile relationship request approved event",
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
            default = DEFAULT_ADDED_TEMPLATE,
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
@SerialName("profile.relationship.notification.added")
class ProfileRelationshipAddedNotificationNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val project: String = DEFAULT_MESSAGE_PROJECT,
    val template: String = DEFAULT_ADDED_TEMPLATE,
    val notificationType: String = NotificationTypeKeys.SOCIAL_ACTIVITY,
    val email: Boolean = true,
    val push: Boolean = true,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val event = ProfileRelationshipAddedNotificationNodeSerializer.deserializePartial(context, inputs).event
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendRelationshipAddedNotification")
            put("email", email)
            put("push", push)
            event?.let { put("requestId", it.requestId.toString()) }
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        deliver(
            ProfileRelationshipAddedNotificationNodeSerializer.deserialize(context, inputs).event,
            provide(),
            provide(),
            provide(),
            provide(),
        )
        return null
    }

    internal suspend fun deliver(
        event: ProfileRelationshipRequestApproved,
        relationshipService: ProfileRelationshipService,
        profileService: ProfileService,
        notificationConfiguration: SocialNotificationConfiguration,
        messageOutboxService: MessageOutboxService,
    ) {
        val channels = selectedChannels(email, push)
        if (channels.isEmpty()) return
        val relationship = relationshipService.getRelationship(event.profileId1, event.profileId2, event.type)
            ?: return
        val recipientProfile = profileService.getById(event.profileId1)
        val relatedProfile = profileService.getById(event.profileId2)
        if (recipientProfile.isDeleted || relatedProfile.isDeleted) return
        val actionUrl = notificationConfiguration.url("audience/profiles/${event.profileId2}")
        val payload = buildJsonObject {
            put("relatedProfileName", relatedProfile.name)
            put("actionUrl", actionUrl)
        }
        messageOutboxService.enqueueOnce(
            notificationId(event.requestId, "approved"),
            Message(
                channels = channels,
                sender = event.profileId2,
                recipients = listOf(event.profileId1),
                pushOptions = PushOptions(
                    defaultAction = PushAction(id = "view-profile", url = actionUrl),
                    sound = "default",
                    threadId = "relationship-${event.profileId2}",
                    category = "RELATIONSHIP_ADDED",
                    data = mapOf(
                        "event" to "relationship_added",
                        "related_profile_id" to event.profileId2.toString(),
                        "relationship_type" to event.type,
                    ),
                ),
                type = notificationType,
                bmlTemplate = MessageBmlTemplate(project, template, payload),
            ),
        )
    }
}

private fun selectedChannels(email: Boolean, push: Boolean): List<MessageChannel> = buildList {
    if (email) add(MessageChannel.EMAIL)
    if (push) add(MessageChannel.PUSH)
}

private fun notificationId(requestId: UUID, state: String): UUID {
    val key = "bosca.profile.relationship.notification:$requestId:$state"
    return UUID.parse(java.util.UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString())
}

private const val DEFAULT_MESSAGE_PROJECT = "bosca-messages"
private const val DEFAULT_REQUEST_TEMPLATE = "relationship-request"
private const val DEFAULT_ADDED_TEMPLATE = "relationship-added"
