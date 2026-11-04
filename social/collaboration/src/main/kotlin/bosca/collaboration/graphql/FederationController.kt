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
