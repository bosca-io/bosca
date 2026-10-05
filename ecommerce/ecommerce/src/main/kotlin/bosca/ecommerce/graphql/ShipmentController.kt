package bosca.ecommerce.graphql

import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentLine
import bosca.ecommerce.model.ShipmentParcel
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.service.FulfillmentService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Field wiring for the `EcomShipment` GraphQL type (read view of a fulfillment shipment). Namespaced
 * to `EcomShipment` to avoid colliding in the gateway's global type namespace, like `EcomSubscription`.
 */
@TypeController(type = "EcomShipment")
class ShipmentController(
    private val fulfillmentService: FulfillmentService,
) : GraphQLController<Shipment> {
    @Field fun id(source: Shipment): UUID = source.id
    @Field fun cartId(source: Shipment): UUID = source.cartId
    @Field fun storeId(source: Shipment): UUID = source.storeId
    @Field fun companyId(source: Shipment): UUID = source.companyId
    @Field fun fulfillmentCenterId(source: Shipment): UUID = source.fulfillmentCenterId
    @Field suspend fun fulfillmentCenter(source: Shipment): FulfillmentCenter =
        fulfillmentService.getCenter(source.fulfillmentCenterId)
            ?: error("fulfillment center ${source.fulfillmentCenterId} not found")
    @Field fun status(source: Shipment): ShipmentStatus = source.status
    @Field fun parcels(source: Shipment): List<ShipmentParcel> = source.parcels
    @Field fun unpacked(source: Shipment): List<ShipmentLine> = source.unpacked
    @Field fun carrier(source: Shipment): String? = source.carrier
    @Field fun tracking(source: Shipment): String? = source.tracking
    @Field fun labelUrl(source: Shipment): String? = source.labelUrl
    @Field fun carrierStatus(source: Shipment): String? = source.carrierStatus
    @Field fun shipped(source: Shipment): OffsetDateTime? = source.shipped
    @Field fun delivered(source: Shipment): OffsetDateTime? = source.delivered
    @Field fun created(source: Shipment): OffsetDateTime = source.created
    @Field fun modified(source: Shipment): OffsetDateTime = source.modified
}

/** Field wiring for the `EcomShipmentParcel` GraphQL type (one packed box within a shipment). */
@TypeController(type = "EcomShipmentParcel")
class ShipmentParcelController : GraphQLController<ShipmentParcel> {
    @Field fun containerId(source: ShipmentParcel): UUID? = source.containerId
    @Field fun lines(source: ShipmentParcel): List<ShipmentLine> = source.lines
}

/** Field wiring for the `EcomShipmentLine` GraphQL type (one SKU + quantity within a parcel). */
@TypeController(type = "EcomShipmentLine")
class ShipmentLineController : GraphQLController<ShipmentLine> {
    @Field fun catalogProductId(source: ShipmentLine): UUID = source.catalogProductId
    @Field fun sku(source: ShipmentLine): String = source.sku
    @Field fun quantity(source: ShipmentLine): Int = source.quantity
}
