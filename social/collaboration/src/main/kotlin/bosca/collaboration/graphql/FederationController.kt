package bosca.collaboration.graphql

import bosca.collaboration.federation.FederatedChannel
import bosca.collaboration.federation.FederationPeer
import bosca.collaboration.federation.FederationService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Federation

@TypeController
class FederationController(
    private val federationService: FederationService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Federation> {

    @Field
    suspend fun peers(authentication: AuthenticationContext): List<FederationPeer> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return federationService.getPeers()
    }

    @Field
    suspend fun peer(authentication: AuthenticationContext, id: UUID): FederationPeer? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return federationService.getPeer(id)
    }

    @Field
    suspend fun channelLinks(authentication: AuthenticationContext, localChannelId: UUID): List<FederatedChannel> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return federationService.getFederatedChannels(localChannelId)
    }
}

@TypeController
class FederationPeerController(
    private val federationService: FederationService,
) : GraphQLController<FederationPeer> {

    @Field
    suspend fun federatedChannels(peer: FederationPeer): List<FederatedChannel> {
        // The service exposes per-local-channel queries; the per-peer rollup
        // isn't part of the interface yet, so this resolver currently returns
        // an empty list. We'll add it when peer-level dashboards need it.
        return emptyList()
    }
}

@TypeController
class FederatedChannelController(
    private val federationService: FederationService,
) : GraphQLController<FederatedChannel> {

    @Field
    suspend fun peer(channel: FederatedChannel): FederationPeer? =
        federationService.getPeer(channel.peerId)
}
