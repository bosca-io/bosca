package bosca.ecommerce.graphql

import bosca.ecommerce.model.Inventory
import bosca.ecommerce.service.InventoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Operations on one inventory row (the id carried by [InventoryMutation]). Admin-gated. */
@TypeController
class InventoryMutationController(
    private val inventoryService: InventoryService,
    private val groups: GroupEvaluator,
) : GraphQLController<InventoryMutation> {

    @Field
    suspend fun adjust(authentication: AuthenticationContext, source: InventoryMutation, quantity: Int): Inventory {
        groups.verifyEcomAdmin(authentication)
        return inventoryService.adjust(source.id, quantity, authentication.principal()?.id)
    }

    @Field
    suspend fun ship(authentication: AuthenticationContext, source: InventoryMutation, quantity: Int): Inventory {
        groups.verifyEcomAdmin(authentication)
        return inventoryService.ship(source.id, quantity, authentication.principal()?.id)
    }
}
