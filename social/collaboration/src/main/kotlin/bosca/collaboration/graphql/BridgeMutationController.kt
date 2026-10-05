package bosca.collaboration.graphql

import bosca.collaboration.bridge.BridgeBinding
import bosca.collaboration.bridge.BridgeIdentityMapping
import bosca.collaboration.bridge.BridgePlatform
import bosca.collaboration.bridge.BridgeService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

object BridgeMutation

/**
 * Input mirror of the GraphQL `CreateBridgeBindingInput` used to create a new
 * bridge binding linking a Bosca channel to an external platform channel.
 */
@Serializable
data class CreateBridgeBindingInput(
    val channelId: UUID,
    val platform: BridgePlatform,
    val externalChannelId: String,
    val workspaceId: String,
    val webhookUrl: String? = null,
    val botToken: String? = null,
)

/**
 * Input mirror of the GraphQL `MapBridgeIdentityInput` used to create or
 * update a single (external user, workspace) -> Bosca profile mapping.
 */
@Serializable
data class MapBridgeIdentityInput(
    val platform: BridgePlatform,
    val externalUserId: String,
    val workspaceId: String,
    val displayName: String,
    val email: String? = null,
    val profileId: UUID? = null,
)

@TypeController
class BridgeMutationController(
    private val bridgeService: BridgeService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<BridgeMutation> {

    @Field
    suspend fun createBinding(
        authentication: AuthenticationContext,
        input: CreateBridgeBindingInput,
    ): BridgeBinding {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val binding = bridgeService.createBinding(
            channelId = input.channelId,
            platform = input.platform,
            externalChannelId = input.externalChannelId,
            workspaceId = input.workspaceId,
            webhookUrl = input.webhookUrl,
        )
        val token = input.botToken?.takeIf { it.isNotBlank() }
        if (token != null) {
            bridgeService.setBotToken(binding.id, token)
        }
        return binding
    }

    @Field
    suspend fun deactivateBinding(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        bridgeService.deactivateBinding(id)
        bridgeService.setBotToken(id, null)
        return true
    }

    @Field
    suspend fun setBotToken(authentication: AuthenticationContext, id: UUID, token: String?): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        bridgeService.setBotToken(id, token?.takeIf { it.isNotBlank() })
        return true
    }

    @Field
    suspend fun mapIdentity(
        authentication: AuthenticationContext,
        input: MapBridgeIdentityInput,
    ): BridgeIdentityMapping {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return bridgeService.mapIdentity(
            platform = input.platform,
            externalUserId = input.externalUserId,
            workspaceId = input.workspaceId,
            displayName = input.displayName,
            email = input.email,
            profileId = input.profileId,
        )
    }

    @Field
    suspend fun deleteIdentity(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        bridgeService.deleteIdentity(id)
        return true
    }
}
