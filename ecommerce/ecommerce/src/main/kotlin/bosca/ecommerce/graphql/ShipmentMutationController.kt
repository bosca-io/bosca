package bosca.ecommerce.graphql

import bosca.db.transaction
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.service.CartService
import bosca.ecommerce.service.ShipmentService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/**
 * Operations on one shipment (the id carried by [ShipmentMutation]). Admin-gated.
 *
 * `ship` is atomic: the shipment dispatch (inventory draw-down + SHIPPED) and the order's fulfillment
 * advance run in one transaction, so an order never lingers with a shipped shipment but an un-advanced
 * status. The nested service transactions join this outer one.
 */
@TypeController
class ShipmentMutationController(
    private val shipmentService: ShipmentService,
    private val cartService: CartService,
    private val groups: GroupEvaluator,
) : GraphQLController<ShipmentMutation> {

    @Field
    suspend fun ship(
        authentication: AuthenticationContext,
        source: ShipmentMutation,
        carrier: String? = null,
        tracking: String? = null,
    ): Shipment {
        groups.verifyEcomAdmin(authentication)
        val principalId = authentication.principal()?.id
        return transaction {
            val shipment = shipmentService.ship(source.id, carrier, tracking, principalId)
            cartService.advanceFulfillment(shipment.cartId, principalId)
            shipment
        }
    }

    @Field
    suspend fun refreshTracking(authentication: AuthenticationContext, source: ShipmentMutation): Shipment {
        groups.verifyEcomAdmin(authentication)
        return shipmentService.refreshTracking(source.id, authentication.principal()?.id)
    }

    @Field
    suspend fun repack(authentication: AuthenticationContext, source: ShipmentMutation): Shipment {
        groups.verifyEcomAdmin(authentication)
        return shipmentService.repack(source.id, authentication.principal()?.id)
    }
}
