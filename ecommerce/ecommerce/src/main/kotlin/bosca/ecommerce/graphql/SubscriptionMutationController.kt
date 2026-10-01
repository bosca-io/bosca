package bosca.ecommerce.graphql

import bosca.ecommerce.model.Subscription
import bosca.ecommerce.service.SubscriptionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Operations on one subscription. Admin-gated. */
@TypeController
class SubscriptionMutationController(
    private val subscriptionService: SubscriptionService,
    private val groups: GroupEvaluator,
) : GraphQLController<SubscriptionMutation> {

    @Field
    suspend fun cancel(authentication: AuthenticationContext, source: SubscriptionMutation): Subscription {
        groups.verifyEcomAdmin(authentication)
        return subscriptionService.cancel(source.id, authentication.principal()?.id)
    }

    @Field
    suspend fun changePlan(authentication: AuthenticationContext, source: SubscriptionMutation, planId: UUID): Subscription {
        groups.verifyEcomAdmin(authentication)
        // The scheduled successor inherits the current subscription's extras (its saved payment
        // method), so renewals keep working after the plan change.
        val current = subscriptionService.get(source.id) ?: error("subscription ${source.id} not found")
        return subscriptionService.changePlans(source.id, planId, current.extras, authentication.principal()?.id)
    }
}
