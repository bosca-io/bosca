package bosca.community.graphql

import bosca.chat.model.ChatChannel
import bosca.community.model.CommunityGroup
import bosca.community.service.CommunityService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Adds community-specific resolvers to the [ChatChannel] GraphQL type.
 * Currently provides the parent community group lookup for channels that
 * are scoped to a community group via [ChatChannel.groupId].
 */
@TypeController
class CommunityChatChannelExtension(
    private val communityService: CommunityService,
) : GraphQLController<ChatChannel> {

    @Field
    suspend fun group(channel: ChatChannel): CommunityGroup? =
        channel.groupId?.let { communityService.getGroup(it) }
}
