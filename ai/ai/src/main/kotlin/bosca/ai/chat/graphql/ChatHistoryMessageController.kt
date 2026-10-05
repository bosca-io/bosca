package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class ChatHistoryMessageController : GraphQLController<ChatHistoryMessage> {

    @Field
    fun id(message: ChatHistoryMessage) = message.id

    @Field
    fun sessionId(message: ChatHistoryMessage) = message.sessionId

    @Field
    fun author(message: ChatHistoryMessage): String = message.author

    @Field
    fun event(message: ChatHistoryMessage) = message.event

    @Field
    fun created(message: ChatHistoryMessage) = message.created
}
