package bosca.ecommerce.graphql

import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.FulfillmentCenterInput
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.InventoryInput
import bosca.ecommerce.service.FulfillmentService
import bosca.ecommerce.service.InventoryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Fulfillment-center creation, inventory creation, and the per-inventory accessor. Admin-gated. */
@TypeController
class FulfillmentMutationController(
    private val fulfillmentService: FulfillmentService,
    private val inventoryService: InventoryService,
    private val groups: GroupEvaluator,
) : GraphQLController<FulfillmentMutation> {

    @Field
    suspend fun addCenter(authentication: AuthenticationContext, input: FulfillmentCenterInput): FulfillmentCenter {
        groups.verifyEcomAdmin(authentication)
        return fulfillmentService.addCenter(input, authentication.principal()?.id)
    }

    @Field
    suspend fun editCenter(authentication: AuthenticationContext, id: UUID, input: FulfillmentCenterInput): FulfillmentCenter {
        groups.verifyEcomAdmin(authentication)
        return fulfillmentService.editCenter(id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun addInventory(authentication: AuthenticationContext, input: InventoryInput): Inventory {
        groups.verifyEcomAdmin(authentication)
        return inventoryService.addInventory(input, authentication.principal()?.id)
    }

    @Field
    fun inventory(id: UUID): InventoryMutation = InventoryMutation(id)

    @Field
    fun shipment(id: UUID): ShipmentMutation = ShipmentMutation(id)
}
