package bosca.chat.graphql

import bosca.chat.model.ChatChannelMember
import bosca.chat.service.ChatService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService

@TypeController
class ChatChannelMemberController(
    private val profileService: ProfileService,
    private val chatService: ChatService,
) : GraphQLController<ChatChannelMember> {

    @Field
    fun channelId(member: ChatChannelMember) = member.channelId

    @Field
    fun profileId(member: ChatChannelMember) = member.profileId

    @Field
    fun role(member: ChatChannelMember) = member.role

    @Field
    suspend fun profile(member: ChatChannelMember): Profile {
        return profileService.getById(member.profileId)
    }

    /**
     * Last-read sequence is now sourced from the `chat-read-state` NATS
     * KV bucket via [ChatService.getLastRead] rather than the row's
     * (now-deprecated) `last_read_sequence` column. Returns null when
     * the user has never recorded a read in this channel.
     */
    @Field
    suspend fun lastReadSequence(member: ChatChannelMember): Long? =
        chatService.getLastRead(member.channelId, member.profileId)

    @Field
    fun attributes(member: ChatChannelMember) = member.attributes
}
