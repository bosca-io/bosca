package bosca.chat.graphql

import bosca.chat.model.ChatMessageEvent
import bosca.chat.model.MessageReactionEvent
import bosca.chat.model.PresenceUpdateEvent
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatService
import bosca.db.withConnectionManager
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Handles real-time chat subscription fields on the GraphQL `Subscription` type.
 * Only active when the chat feature is enabled — the chat schema registrar and
 * dispatcher registrar are gated behind `Features.chat`, so these fields are
 * absent from the schema when chat is disabled.
 */
@TypeController(type = "Subscription")
class ChatSubscriptionController(
    private val chatService: ChatService,
    private val chatChannelPermissionEvaluator: ChatChannelPermissionEvaluator,
    private val profileService: ProfileService,
    private val groupEvaluator: GroupEvaluator,
    private val chatObjectPermissionEvaluator: ChatObjectPermissionEvaluator,
) : GraphQLController<Chat> {

    @Field
    fun chatMessage(
        authenticationContext: AuthenticationContext,
        channelId: UUID,
    ): Flow<ChatMessageEvent> = flow {
        val profileId = withConnectionManager {
            groupEvaluator.verifyHasMessagingAccess(authenticationContext)
            val channel = chatService.getById(channelId) ?: throw IllegalArgumentException("Channel not found")
            chatObjectPermissionEvaluator.verifyAllowed(authenticationContext, channel)
            chatChannelPermissionEvaluator.verifyAllowed(authenticationContext, channel, PermissionAction.VIEW)
            authenticatedChatProfile(authenticationContext).id
        }
        emitAll(chatService.subscribe(channelId, profileId).map { ChatMessageEvent(channelId = channelId, message = it) })
    }

    @Field
    fun onUserTyping(authenticationContext: AuthenticationContext, channelId: UUID) = flow {
        val profileId = withConnectionManager {
            groupEvaluator.verifyHasMessagingAccess(authenticationContext)
            val channel = chatService.getById(channelId) ?: throw IllegalArgumentException("Channel not found")
            chatObjectPermissionEvaluator.verifyAllowed(authenticationContext, channel)
            chatChannelPermissionEvaluator.verifyAllowed(authenticationContext, channel, PermissionAction.VIEW)
            authenticatedChatProfile(authenticationContext).id
        }
        emitAll(chatService.subscribeTyping(channelId, profileId))
    }

    @Field
    fun onPresenceUpdate(authenticationContext: AuthenticationContext, channelId: UUID) = flow<PresenceUpdateEvent> {
        val profileId = withConnectionManager {
            groupEvaluator.verifyHasMessagingAccess(authenticationContext)
            val channel = chatService.getById(channelId) ?: throw IllegalArgumentException("Channel not found")
            chatObjectPermissionEvaluator.verifyAllowed(authenticationContext, channel)
            chatChannelPermissionEvaluator.verifyAllowed(authenticationContext, channel, PermissionAction.VIEW)
            authenticatedChatProfile(authenticationContext).id
        }
        emitAll(chatService.subscribePresence(channelId, profileId))
    }

    @Field
    fun onReaction(authenticationContext: AuthenticationContext, channelId: UUID): Flow<MessageReactionEvent> = flow {
        val profileId = withConnectionManager {
            groupEvaluator.verifyHasMessagingAccess(authenticationContext)
            val channel = chatService.getById(channelId) ?: throw IllegalArgumentException("Channel not found")
            chatObjectPermissionEvaluator.verifyAllowed(authenticationContext, channel)
            chatChannelPermissionEvaluator.verifyAllowed(authenticationContext, channel, PermissionAction.VIEW)
            authenticatedChatProfile(authenticationContext).id
        }
        emitAll(chatService.subscribeReactions(channelId, profileId))
    }

    private suspend fun authenticatedChatProfile(authentication: AuthenticationContext): Profile {
        val principal = authentication.principal()?.asPrincipal()
            ?: throw SecurityException("An authenticated chat profile is required")
        return profileService.getPrimaryProfile(principal)
            ?: throw SecurityException("An active primary chat profile is required")
    }
}
