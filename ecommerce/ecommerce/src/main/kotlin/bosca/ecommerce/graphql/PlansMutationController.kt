package bosca.ecommerce.graphql

import bosca.ecommerce.model.PlanGroupInput
import bosca.ecommerce.model.PlanInput
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.ecommerce.service.SubscriptionPlanService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Subscription plan group + plan admin CRUD under `EcomMutation.plans`. Admin-gated. */
@TypeController
class PlansMutationController(
    private val planService: SubscriptionPlanService,
    private val groups: GroupEvaluator,
) : GraphQLController<PlansMutation> {

    @Field
    suspend fun addGroup(authentication: AuthenticationContext, input: PlanGroupInput): SubscriptionPlanGroup {
        groups.verifyEcomAdmin(authentication)
        return planService.createGroup(input, authentication.principal()?.id)
    }

    @Field
    suspend fun addPlan(authentication: AuthenticationContext, input: PlanInput): SubscriptionPlan {
        groups.verifyEcomAdmin(authentication)
        return planService.createPlan(input, authentication.principal()?.id)
    }
}
