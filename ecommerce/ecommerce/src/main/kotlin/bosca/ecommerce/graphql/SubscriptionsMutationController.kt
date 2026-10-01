package bosca.ecommerce.graphql

import bosca.ecommerce.model.SubscribeInput
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.service.SubscriptionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Direct subscribe + per-subscription operations under `EcomMutation.subscriptions`. Admin-gated. */
@TypeController
class SubscriptionsMutationController(
    private val subscriptionService: SubscriptionService,
    private val groups: GroupEvaluator,
) : GraphQLController<SubscriptionsMutation> {

    @Field
    suspend fun subscribe(authentication: AuthenticationContext, input: SubscribeInput): Subscription {
        groups.verifyEcomAdmin(authentication)
        return subscriptionService.subscribe(input, authentication.principal()?.id)
    }

    @Field
    fun subscription(id: UUID): SubscriptionMutation = SubscriptionMutation(id)
}
