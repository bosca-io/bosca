package bosca.chat.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatMessage
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

@TypeController
class ChatChannelController(
    private val chatService: ChatService,
    private val chatChannelPermissionEvaluator: ChatChannelPermissionEvaluator,
    private val chatObjectPermissionEvaluator: ChatObjectPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ChatChannel> {

    @Field
    fun id(channel: ChatChannel) = channel.id

    @Field
    fun name(channel: ChatChannel) = channel.name

    @Field
    fun type(channel: ChatChannel) = channel.type

    @Field
    fun attributes(channel: ChatChannel) = channel.attributes

    @Field
    fun objectType(channel: ChatChannel) = channel.objectType

    @Field
    fun objectId(channel: ChatChannel) = channel.objectId

    @Field
    suspend fun messages(
        authentication: AuthenticationContext,
        channel: ChatChannel,
        before: Long?,
        after: Long?,
        limit: Int?
    ): List<ChatMessage> {
        groupEvaluator.verifyHasMessagingAccess(authentication)
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.VIEW)
        return chatService.getMessages(channel.id, before, after, limit ?: 50)
    }

    @Field
    suspend fun members(
        authentication: AuthenticationContext,
        channel: ChatChannel
    ): List<ChatChannelMember> {
        groupEvaluator.verifyHasMessagingAccess(authentication)
        chatObjectPermissionEvaluator.verifyAllowed(authentication, channel)
        chatChannelPermissionEvaluator.verifyAllowed(authentication, channel, PermissionAction.VIEW)
        return chatService.getMembers(channel.id)
    }
}
