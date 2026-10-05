package bosca.chat.graphql

import bosca.chat.model.ChatMessage
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService

@TypeController
class ChatMessageController(
    private val profileService: ProfileService
) : GraphQLController<ChatMessage> {

    @Field
    fun parentSequence(message: ChatMessage) = message.parentSequence

    @Field
    fun sequence(message: ChatMessage) = message.sequence

    @Field
    fun timestamp(message: ChatMessage) = message.timestamp

    @Field
    fun senderId(message: ChatMessage) = message.senderId

    @Field
    fun clientId(message: ChatMessage) = message.clientId

    @Field
    suspend fun sender(message: ChatMessage): Profile {
        return profileService.getById(message.senderId)
    }

    @Field
    fun content(message: ChatMessage) = message.content

    @Field
    fun attributes(message: ChatMessage) = message.attributes

    @Field
    fun reactions(message: ChatMessage) = message.reactions

    @Field
    fun deleted(message: ChatMessage) = message.deleted
}
