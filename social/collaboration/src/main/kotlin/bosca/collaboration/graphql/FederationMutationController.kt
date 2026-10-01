package bosca.collaboration.graphql

import bosca.collaboration.federation.FederationPeer
import bosca.collaboration.federation.FederationService
import bosca.collaboration.federation.FederationSyncDirection
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

object FederationMutation

@Serializable
data class RegisterFederationPeerInput(
    val name: String,
    val natsUrl: String,
    val apiUrl: String,
    val sharedSecret: String,
)

@Serializable
data class FederateChannelInput(
    val localChannelId: UUID,
    val peerId: UUID,
    val remoteChannelId: UUID,
    val syncDirection: FederationSyncDirection? = null,
)

@TypeController
class FederationMutationController(
    private val federationService: FederationService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<FederationMutation> {

    @Field
    suspend fun registerPeer(
        authentication: AuthenticationContext,
        input: RegisterFederationPeerInput,
    ): FederationPeer {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return federationService.registerPeer(
            name = input.name,
            natsUrl = input.natsUrl,
            apiUrl = input.apiUrl,
            sharedSecret = input.sharedSecret,
        )
    }

    @Field
    suspend fun deactivatePeer(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        federationService.deactivatePeer(id)
        return true
    }

    @Field
    suspend fun federateChannel(
        authentication: AuthenticationContext,
        input: FederateChannelInput,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        federationService.federateChannel(
            localChannelId = input.localChannelId,
            peerId = input.peerId,
            remoteChannelId = input.remoteChannelId,
            direction = input.syncDirection ?: FederationSyncDirection.BIDIRECTIONAL,
        )
        return true
    }

    @Field
    suspend fun unfederateChannel(
        authentication: AuthenticationContext,
        localChannelId: UUID,
        peerId: UUID,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        federationService.unfederateChannel(localChannelId, peerId)
        return true
    }

    @Field
    suspend fun setSharedSecret(
        authentication: AuthenticationContext,
        id: UUID,
        secret: String?,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        if (federationService.getPeer(id) == null) return false
        federationService.setSharedSecret(id, secret?.takeIf { it.isNotBlank() })
        return true
    }
}
