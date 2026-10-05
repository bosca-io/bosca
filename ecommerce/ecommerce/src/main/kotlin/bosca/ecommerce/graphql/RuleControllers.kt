package bosca.ecommerce.graphql

import bosca.ecommerce.model.AmountOffCartRule
import bosca.ecommerce.model.AmountOffSubscriptionRule
import bosca.ecommerce.model.BuyOneGetOneRule
import bosca.ecommerce.model.FreeShippingCartRule
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PercentOffCartRule
import bosca.ecommerce.model.PercentOffSubscriptionRule
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Field wiring for the cart-rule members of the `Rule` GraphQL union. Each carries its kotlinx
 * `type` discriminator so clients can branch without an inline fragment if they prefer. The union +
 * its `DispatcherTypeResolver` registration live in the SDL + server wiring.
 */
@TypeController
class PercentOffCartRuleController : GraphQLController<PercentOffCartRule> {
    @Field fun type(source: PercentOffCartRule): String = "cartPercentOff"
    @Field fun percent(source: PercentOffCartRule): Double = source.percent
}

@TypeController
class AmountOffCartRuleController : GraphQLController<AmountOffCartRule> {
    @Field fun type(source: AmountOffCartRule): String = "cartAmountOff"
    @Field fun amount(source: AmountOffCartRule): Money = source.amount
}

@TypeController
class FreeShippingCartRuleController : GraphQLController<FreeShippingCartRule> {
    @Field fun type(source: FreeShippingCartRule): String = "cartFreeShipping"
}

@TypeController
class BuyOneGetOneRuleController : GraphQLController<BuyOneGetOneRule> {
    @Field fun type(source: BuyOneGetOneRule): String = "cartBuyOneGetOne"
    @Field fun buyQuantity(source: BuyOneGetOneRule): Int = source.buyQuantity
    @Field fun getQuantity(source: BuyOneGetOneRule): Int = source.getQuantity
}

@TypeController
class PercentOffSubscriptionRuleController : GraphQLController<PercentOffSubscriptionRule> {
    @Field fun type(source: PercentOffSubscriptionRule): String = "subscriptionPercentOff"
    @Field fun percent(source: PercentOffSubscriptionRule): Double = source.percent
}

@TypeController
class AmountOffSubscriptionRuleController : GraphQLController<AmountOffSubscriptionRule> {
    @Field fun type(source: AmountOffSubscriptionRule): String = "subscriptionAmountOff"
    @Field fun amount(source: AmountOffSubscriptionRule): Money = source.amount
}
