package bosca.chat.graphql

import bosca.chat.model.MessageReaction
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves fields for the [MessageReaction] GraphQL type, which represents
 * an emoji reaction attached to a chat message.
 */
@TypeController
class MessageReactionController : GraphQLController<MessageReaction> {

    @Field
    fun id(reaction: MessageReaction) = reaction.id

    @Field
    fun emoji(reaction: MessageReaction) = reaction.emoji

    @Field
    fun profileId(reaction: MessageReaction) = reaction.profileId
}
