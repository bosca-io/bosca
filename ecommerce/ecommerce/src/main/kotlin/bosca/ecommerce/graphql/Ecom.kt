package bosca.ecommerce.graphql

/**
 * GraphQL namespace markers for the ecommerce domain. All commerce reads hang off [Ecom]
 * (`Query.ecom`) and all writes off [EcomMutation] (`Mutation.ecom`) — nothing leaks onto the
 * global Query/Mutation roots. The root fields are declared in `ecom.graphqls`
 * (`extend type Query/Mutation`) and resolved by the server's QueryController/MutationController
 * (`fun ecom() = Ecom` / `= EcomMutation`); field resolution within the namespaces is handled by
 * [EcomController] / [EcomMutationController] and the per-area controllers.
 */
object Ecom

object EcomMutation
