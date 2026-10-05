package bosca.ecommerce.graphql

import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionStatus
import bosca.ecommerce.service.SubscriptionPlanService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `EcomSubscription` GraphQL type. Price/interval are signup snapshots. */
@TypeController(type = "EcomSubscription")
class SubscriptionController(
    private val planService: SubscriptionPlanService,
) : GraphQLController<Subscription> {
    @Field fun id(source: Subscription): UUID = source.id
    @Field suspend fun plan(source: Subscription): SubscriptionPlan =
        planService.getPlan(source.planId) ?: error("plan ${source.planId} not found")
    @Field fun status(source: Subscription): SubscriptionStatus = source.status
    @Field fun price(source: Subscription): Money = source.price
    @Field fun currency(source: Subscription): String = source.currency
    @Field fun interval(source: Subscription): Int = source.interval
    @Field fun intervalUnit(source: Subscription): IntervalUnit = source.intervalUnit
    @Field fun renews(source: Subscription): OffsetDateTime = source.renews
    @Field fun expires(source: Subscription): OffsetDateTime? = source.expires
    @Field fun paymentFailures(source: Subscription): Int = source.paymentFailures
    @Field fun renewals(source: Subscription): Int = source.renewals
    @Field fun cartId(source: Subscription): UUID? = source.cartId
    @Field fun created(source: Subscription): OffsetDateTime = source.created
    @Field fun modified(source: Subscription): OffsetDateTime = source.modified
}
