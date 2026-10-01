package bosca.chat.graphql

import bosca.chat.model.ChatMessageEvent
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class ChatMessageEventController : GraphQLController<ChatMessageEvent> {

    @Field
    fun channelId(event: ChatMessageEvent) = event.channelId

    @Field
    fun message(event: ChatMessageEvent) = event.message
}
