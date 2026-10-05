package bosca.ecommerce.graphql

import bosca.ecommerce.model.IapRedeemInput
import bosca.ecommerce.model.IapRedemptionResult
import bosca.ecommerce.service.IapEntitlementService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** In-app-purchase redemption under `EcomMutation.iap`. Admin-gated (buyer self-service is a follow-up). */
@TypeController
class IapMutationController(
    private val iapEntitlementService: IapEntitlementService,
    private val groups: GroupEvaluator,
) : GraphQLController<IapMutation> {

    @Field
    suspend fun redeem(authentication: AuthenticationContext, source: IapMutation, input: IapRedeemInput): IapRedemptionResult {
        groups.verifyEcomAdmin(authentication)
        return iapEntitlementService.redeem(input, authentication.principal()?.id)
    }
}
