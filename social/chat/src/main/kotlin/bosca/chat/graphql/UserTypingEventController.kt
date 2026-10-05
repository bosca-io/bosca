package bosca.chat.graphql

import bosca.chat.model.UserTypingEvent
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService

@TypeController
class UserTypingEventController(
    private val profileService: ProfileService
) : GraphQLController<UserTypingEvent> {

    @Field
    fun channelId(event: UserTypingEvent) = event.channelId

    @Field
    fun profileId(event: UserTypingEvent) = event.profileId

    @Field
    suspend fun profile(event: UserTypingEvent) = profileService.getById(event.profileId)

    @Field
    fun isTyping(event: UserTypingEvent) = event.isTyping
}
