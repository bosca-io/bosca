package bosca.server.installer

import bosca.chat.events.ChatChannelInvitationSentEvent
import bosca.chat.events.ChatChannelJoinedEvent
import bosca.chat.events.ChatMessageReactionAddedEvent
import bosca.chat.events.ChatMessageSentEvent
import bosca.chat.pipeline.ChatChannelInvitationNotificationNode
import bosca.chat.pipeline.ChatChannelJoinedNotificationNode
import bosca.chat.pipeline.ChatMessageNotificationNode
import bosca.chat.pipeline.ChatMessageReactionNotificationNode
import bosca.collaboration.events.ChatMentionEvent
import bosca.collaboration.pipeline.ChatMentionNotificationNode
import bosca.community.events.PrayerCommentAddedEvent
import bosca.community.events.PrayerReactionAddedEvent
import bosca.community.pipeline.PrayerCommentNotificationNode
import bosca.community.pipeline.PrayerReactionNotificationNode
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.service.PipelineService
import bosca.profile.relationship.events.ProfileRelationshipRequestApproved
import bosca.profile.relationship.events.ProfileRelationshipRequested
import bosca.profile.relationship.pipeline.ProfileRelationshipAddedNotificationNode
import bosca.profile.relationship.pipeline.ProfileRelationshipRequestedNotificationNode
import bosca.serialization.UUID
import org.slf4j.LoggerFactory

/**
 * Seeds editable event-to-notification pipelines for first-party social activity. Existing pipelines
 * are never overwritten, so operators can independently change each action's available templates
 * and delivery-channel settings after the initial installation.
 */
class DefaultSocialNotificationPipelinesInstaller(
    private val pipelineService: PipelineService,
) : PackageInstaller {

    override val version: String = VERSION

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existingNames = pipelineService.getAll().mapTo(mutableSetOf()) { it.name }
        for (spec in specs()) {
            if (spec.name in existingNames) {
                log.info("default social notification pipeline '{}' already present; skipping", spec.name)
                continue
            }
            val pipeline = spec.pipeline()
            log.info("creating default social notification pipeline '{}' for {}", spec.name, spec.eventType)
            pipelineService.save(
                id = UUID.NIL,
                name = pipeline.name,
                description = pipeline.description,
                acceptedInputType = pipeline.acceptedInputType,
                tags = pipeline.tags,
                triggered = true,
                version = 0,
                graph = pipelineService.graphAsJsonElement(pipeline),
            )
        }
    }

    internal fun specs(): List<Spec> = listOf(
        Spec(
            name = CHAT_MESSAGE_PIPELINE,
            description = "Sends email and push to active channel members.",
            eventType = requireNotNull(ChatMessageSentEvent::class.qualifiedName),
            action = ChatMessageNotificationNode(
                id = SEND,
                name = "Send chat message notification",
                position = NodePosition(360.0, 140.0),
            ),
        ),
        Spec(
            name = CHAT_REACTION_PIPELINE,
            description = "Sends push to the active author of a message when another profile reacts.",
            eventType = requireNotNull(ChatMessageReactionAddedEvent::class.qualifiedName),
            action = ChatMessageReactionNotificationNode(
                id = SEND,
                name = "Send chat reaction notification",
                position = NodePosition(360.0, 140.0),
            ),
            tags = listOf("Social", "Notifications", "Push"),
        ),
        Spec(
            name = PRAYER_REACTION_PIPELINE,
            description = "Sends push to the prayer owner when another profile prays or likes.",
            eventType = requireNotNull(PrayerReactionAddedEvent::class.qualifiedName),
            action = PrayerReactionNotificationNode(
                id = SEND,
                name = "Send prayer reaction notification",
                position = NodePosition(360.0, 140.0),
            ),
            tags = listOf("Social", "Notifications", "Push"),
        ),
        Spec(
            name = PRAYER_COMMENT_PIPELINE,
            description = "Sends push to prayer owners and parent-comment authors after new comments.",
            eventType = requireNotNull(PrayerCommentAddedEvent::class.qualifiedName),
            action = PrayerCommentNotificationNode(
                id = SEND,
                name = "Send prayer comment notification",
                position = NodePosition(360.0, 140.0),
            ),
            tags = listOf("Social", "Notifications", "Push"),
        ),
        Spec(
            name = CHANNEL_INVITATION_PIPELINE,
            description = "Sends email and push while a chat channel invitation remains actionable.",
            eventType = requireNotNull(ChatChannelInvitationSentEvent::class.qualifiedName),
            action = ChatChannelInvitationNotificationNode(
                id = SEND,
                name = "Send channel invitation notification",
                position = NodePosition(360.0, 140.0),
            ),
        ),
        Spec(
            name = CHANNEL_JOINED_PIPELINE,
            description = "Sends email and push to active members after a profile joins a chat channel.",
            eventType = requireNotNull(ChatChannelJoinedEvent::class.qualifiedName),
            action = ChatChannelJoinedNotificationNode(
                id = SEND,
                name = "Send channel joined notification",
                position = NodePosition(360.0, 140.0),
            ),
        ),
        Spec(
            name = RELATIONSHIP_REQUESTED_PIPELINE,
            description = "Sends email and push while a profile relationship request remains actionable.",
            eventType = requireNotNull(ProfileRelationshipRequested::class.qualifiedName),
            action = ProfileRelationshipRequestedNotificationNode(
                id = SEND,
                name = "Send relationship request notification",
                position = NodePosition(360.0, 140.0),
            ),
        ),
        Spec(
            name = RELATIONSHIP_APPROVED_PIPELINE,
            description = "Sends email and push after a requested profile relationship is added.",
            eventType = requireNotNull(ProfileRelationshipRequestApproved::class.qualifiedName),
            action = ProfileRelationshipAddedNotificationNode(
                id = SEND,
                name = "Send relationship added notification",
                position = NodePosition(360.0, 140.0),
            ),
        ),
        Spec(
            name = CHAT_MENTION_PIPELINE,
            description = "Sends email and push to eligible profiles mentioned outside the active channel.",
            eventType = requireNotNull(ChatMentionEvent::class.qualifiedName),
            action = ChatMentionNotificationNode(
                id = SEND,
                name = "Send chat mention notification",
                position = NodePosition(360.0, 140.0),
            ),
        ),
    )

    internal data class Spec(
        val name: String,
        val description: String,
        val eventType: String,
        val action: PipelineNode,
        val tags: List<String> = listOf("Social", "Notifications", "Email", "Push"),
    ) {
        fun pipeline(): Pipeline = Pipeline(
            id = UUID.NIL,
            name = name,
            description = description,
            acceptedInputType = eventType,
            tags = tags,
            triggered = true,
            nodes = listOf(
                InputNode(id = INPUT, acceptedType = eventType, position = NodePosition(40.0, 140.0)),
                action,
            ),
            edges = listOf(PipelineEdge(id = "e1", source = INPUT, target = SEND, targetPort = "event")),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DefaultSocialNotificationPipelinesInstaller::class.java)

        const val NAME = "default-social-notification-pipelines"
        const val VERSION = "1.2.0"
        const val CHAT_MESSAGE_PIPELINE = "Social Notifications — Chat Messages"
        const val CHAT_REACTION_PIPELINE = "Social Notifications — Chat Reactions"
        const val PRAYER_REACTION_PIPELINE = "Social Notifications — Prayer Reactions"
        const val PRAYER_COMMENT_PIPELINE = "Social Notifications — Prayer Comments"
        const val CHANNEL_INVITATION_PIPELINE = "Social Notifications — Channel Invitations"
        const val CHANNEL_JOINED_PIPELINE = "Social Notifications — Channel Joins"
        const val RELATIONSHIP_REQUESTED_PIPELINE = "Social Notifications — Relationship Requests"
        const val RELATIONSHIP_APPROVED_PIPELINE = "Social Notifications — Relationship Approvals"
        const val CHAT_MENTION_PIPELINE = "Social Notifications — Chat Mentions"

        private const val INPUT = "input"
        private const val SEND = "send"
    }
}
