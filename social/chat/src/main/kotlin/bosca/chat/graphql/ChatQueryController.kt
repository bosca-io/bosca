package bosca.chat.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatObjectType
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Chat

@TypeController
class ChatQueryController(
    private val chatService: ChatService,
    private val chatChannelPermissionEvaluator: ChatChannelPermissionEvaluator,
    private val chatObjectPermissionEvaluator: ChatObjectPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Chat> {

    @Field
    suspend fun channel(authentication: AuthenticationContext, id: UUID): ChatChannel? {
        groupEvaluator.verifyHasMessagingAccess(authentication)
        val channel = chatService.getById(id) ?: return null
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.VIEW)
        return channel
    }

    @Field
    suspend fun objectChannel(
        authentication: AuthenticationContext,
        objectType: ChatObjectType,
        objectId: UUID
    ): ChatChannel? {
        groupEvaluator.verifyHasMessagingAccess(authentication)
        chatObjectPermissionEvaluator.verifyAllowed(authentication, objectType, objectId)
        val channel = chatService.getByObject(objectType, objectId) ?: return null
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.VIEW)
        return channel
    }

}
