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

object Bridge

@TypeController
class BridgeController(
    private val bridgeService: BridgeService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Bridge> {

    @Field
    suspend fun bindings(authentication: AuthenticationContext, channelId: UUID): List<BridgeBinding> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return bridgeService.getBindingsForChannel(channelId)
    }

    @Field
    suspend fun identities(
        authentication: AuthenticationContext,
        platform: BridgePlatform,
        workspaceId: String,
    ): List<BridgeIdentityMapping> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return bridgeService.listIdentities(platform, workspaceId)
    }
}
