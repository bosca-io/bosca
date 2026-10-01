package bosca.ecommerce.graphql

import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `SubscriptionPlanGroup` GraphQL type. */
@TypeController
class SubscriptionPlanGroupController : GraphQLController<SubscriptionPlanGroup> {
    @Field fun id(source: SubscriptionPlanGroup): UUID = source.id
    @Field fun key(source: SubscriptionPlanGroup): String = source.key
    @Field fun name(source: SubscriptionPlanGroup): String = source.name
    @Field fun description(source: SubscriptionPlanGroup): String = source.description
    @Field fun paymentRetries(source: SubscriptionPlanGroup): Int = source.paymentRetries
    @Field fun created(source: SubscriptionPlanGroup): OffsetDateTime = source.created
    @Field fun modified(source: SubscriptionPlanGroup): OffsetDateTime = source.modified
}
