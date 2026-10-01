package bosca.chat.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.graphql.ProfileChat
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

@TypeController
class ProfileChatController(
    private val chatService: ChatService,
    private val chatChannelPermissionEvaluator: ChatChannelPermissionEvaluator,
    private val chatObjectPermissionEvaluator: ChatObjectPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ProfileChat> {

    @Field
    suspend fun channels(authentication: AuthenticationContext, chat: ProfileChat): List<ChatChannel> {
        groupEvaluator.verifyHasMessagingAccess(authentication)
        val channels = chatChannelPermissionEvaluator.filterAllowed(
            authentication,
            chatService.getChannels(chat.profile.id),
            PermissionAction.VIEW
        )
        return channels.filter { chatObjectPermissionEvaluator.isAllowed(authentication, it) }
    }
}
