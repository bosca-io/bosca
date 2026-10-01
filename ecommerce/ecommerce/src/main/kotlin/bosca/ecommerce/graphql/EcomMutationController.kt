package bosca.ecommerce.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves the [EcomMutation] mutation namespace (`Mutation.ecom`). Per-collection namespaces are
 * added here by their respective areas; each area also declares the matching SDL on
 * `type EcomMutation`.
 */
@TypeController
class EcomMutationController : GraphQLController<EcomMutation> {

    @Field
    fun companies(): CompaniesMutation = CompaniesMutation

    @Field
    fun customers(): CustomersMutation = CustomersMutation

    @Field
    fun accounts(): AccountsMutation = AccountsMutation

    @Field
    fun catalogs(): CatalogsMutation = CatalogsMutation

    @Field
    fun manufacturers(): ManufacturersMutation = ManufacturersMutation

    @Field
    fun products(): ProductsMutation = ProductsMutation

    @Field
    fun catalogProducts(): CatalogProductsMutation = CatalogProductsMutation

    @Field
    fun stores(): StoresMutation = StoresMutation

    @Field
    fun providers(): ProvidersMutation = ProvidersMutation

    @Field
    fun fulfillment(): FulfillmentMutation = FulfillmentMutation

    @Field
    fun carts(): CartsMutation = CartsMutation

    @Field
    fun promotions(): PromotionsMutation = PromotionsMutation

    @Field
    fun payments(): PaymentsMutation = PaymentsMutation

    @Field
    fun plans(): PlansMutation = PlansMutation

    @Field
    fun subscriptions(): SubscriptionsMutation = SubscriptionsMutation

    @Field
    fun containers(): ContainersMutation = ContainersMutation

    @Field
    fun returns(): ReturnsMutation = ReturnsMutation

    @Field
    fun iap(): IapMutation = IapMutation
}
