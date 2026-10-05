package bosca.ecommerce.graphql

import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PlanConfiguration
import bosca.ecommerce.model.PlanStatus
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.ecommerce.service.SubscriptionPlanService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `SubscriptionPlan` GraphQL type. */
@TypeController
class SubscriptionPlanController(
    private val planService: SubscriptionPlanService,
) : GraphQLController<SubscriptionPlan> {
    @Field fun id(source: SubscriptionPlan): UUID = source.id
    @Field suspend fun group(source: SubscriptionPlan): SubscriptionPlanGroup =
        planService.getGroup(source.planGroupId) ?: error("plan group ${source.planGroupId} not found")
    @Field fun key(source: SubscriptionPlan): String = source.key
    @Field fun name(source: SubscriptionPlan): String = source.name
    @Field fun description(source: SubscriptionPlan): String = source.description
    @Field fun status(source: SubscriptionPlan): PlanStatus = source.status
    @Field fun price(source: SubscriptionPlan): Money = source.price
    @Field fun interval(source: SubscriptionPlan): Int = source.interval
    @Field fun intervalUnit(source: SubscriptionPlan): IntervalUnit = source.intervalUnit
    @Field fun configuration(source: SubscriptionPlan): PlanConfiguration = source.configuration
    @Field fun expires(source: SubscriptionPlan): OffsetDateTime? = source.expires
    @Field fun created(source: SubscriptionPlan): OffsetDateTime = source.created
    @Field fun modified(source: SubscriptionPlan): OffsetDateTime = source.modified
}
