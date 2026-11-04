package bosca.collaboration.graphql

import bosca.collaboration.federation.FederatedChannel
import bosca.collaboration.federation.FederationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class FederatedChannelController(private val federationService: FederationService) : GraphQLController<FederatedChannel> {
    @Field fun localChannelId(channel: FederatedChannel) = channel.localChannelId
    @Field fun peerId(channel: FederatedChannel) = channel.peerId
    @Field fun remoteChannelId(channel: FederatedChannel) = channel.remoteChannelId
    @Field fun syncDirection(channel: FederatedChannel) = channel.syncDirection
    @Field suspend fun peer(channel: FederatedChannel) = federationService.getPeer(channel.peerId)
}
