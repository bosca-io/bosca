package bosca.chat.graphql

import bosca.chat.model.MessageReactionEvent
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class MessageReactionEventController : GraphQLController<MessageReactionEvent> {

    @Field
    fun channelId(event: MessageReactionEvent) = event.channelId

    @Field
    fun sequence(event: MessageReactionEvent) = event.sequence

    @Field
    fun profileId(event: MessageReactionEvent) = event.profileId

    @Field
    fun emoji(event: MessageReactionEvent) = event.emoji

    @Field
    fun added(event: MessageReactionEvent) = event.added
}
