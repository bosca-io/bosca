package bosca.ecommerce.graphql

import bosca.ecommerce.model.IapRedemptionResult
import bosca.ecommerce.model.IapRedemptionStatus
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.service.SubscriptionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Type-wiring for `IapRedemptionResult`; resolves the granted/extended subscription by id. */
@TypeController
class IapRedemptionResultController(
    private val subscriptionService: SubscriptionService,
) : GraphQLController<IapRedemptionResult> {

    @Field
    fun status(source: IapRedemptionResult): IapRedemptionStatus = source.status

    @Field
    fun transactionId(source: IapRedemptionResult): String? = source.transactionId

    @Field
    suspend fun subscription(source: IapRedemptionResult): Subscription? = source.subscriptionId?.let { subscriptionService.get(it) }
}
