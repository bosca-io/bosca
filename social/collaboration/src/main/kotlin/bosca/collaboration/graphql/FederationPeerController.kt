package bosca.collaboration.graphql

import bosca.collaboration.federation.FederationPeer
import bosca.collaboration.federation.FederationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class FederationPeerController(private val federationService: FederationService) : GraphQLController<FederationPeer> {
    @Field fun id(peer: FederationPeer) = peer.id
    @Field fun name(peer: FederationPeer) = peer.name
    @Field fun natsUrl(peer: FederationPeer) = peer.natsUrl
    @Field fun apiUrl(peer: FederationPeer) = peer.apiUrl
    @Field fun active(peer: FederationPeer) = peer.active
    @Field fun createdAt(peer: FederationPeer) = peer.createdAt

    @Field
    suspend fun federatedChannels(peer: FederationPeer) =
        federationService.getAllFederatedChannels().filter { it.peerId == peer.id }
}
