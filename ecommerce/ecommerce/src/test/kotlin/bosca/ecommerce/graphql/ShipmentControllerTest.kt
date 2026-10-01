package bosca.ecommerce.graphql

import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentLine
import bosca.ecommerce.model.ShipmentParcel
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.service.FulfillmentService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * EcomShipment field wiring: every resolver returns the matching source field, and `fulfillmentCenter`
 * resolves through the fulfillment service (erroring when the center is missing). Also covers the
 * EcomShipmentParcel and EcomShipmentLine field controllers.
 */
@OptIn(ExperimentalUuidApi::class)
class ShipmentControllerTest {

    private val fulfillmentService = mockk<FulfillmentService>()
    private val controller = ShipmentController(fulfillmentService)

    private val centerId = UUID.random()
    private val parcel = ShipmentParcel(
        containerId = UUID.random(),
        lines = listOf(ShipmentLine(catalogProductId = UUID.random(), inventoryId = UUID.random(), sku = "SKU-A", quantity = 2)),
    )
    private val unpackedLine = ShipmentLine(catalogProductId = UUID.random(), inventoryId = UUID.random(), sku = "SKU-B", quantity = 1)
    private val shipment = Shipment(
        id = UUID.random(), cartId = UUID.random(), storeId = UUID.random(), companyId = UUID.random(),
        fulfillmentCenterId = centerId, status = ShipmentStatus.SHIPPED,
        parcels = listOf(parcel), unpacked = listOf(unpackedLine),
        carrier = "UPS", tracking = "1Z999", labelUrl = "http://label", carrierStatus = "in transit",
        shipped = bosca.serialization.OffsetDateTime.now(), delivered = bosca.serialization.OffsetDateTime.now(),
    )

    @Test
    fun `every scalar field resolves from the source shipment`() {
        assertEquals(shipment.id, controller.id(shipment))
        assertEquals(shipment.cartId, controller.cartId(shipment))
        assertEquals(shipment.storeId, controller.storeId(shipment))
        assertEquals(shipment.companyId, controller.companyId(shipment))
        assertEquals(centerId, controller.fulfillmentCenterId(shipment))
        assertEquals(ShipmentStatus.SHIPPED, controller.status(shipment))
        assertEquals(listOf(parcel), controller.parcels(shipment))
        assertEquals(listOf(unpackedLine), controller.unpacked(shipment))
        assertEquals("UPS", controller.carrier(shipment))
        assertEquals("1Z999", controller.tracking(shipment))
        assertEquals("http://label", controller.labelUrl(shipment))
        assertEquals("in transit", controller.carrierStatus(shipment))
        assertEquals(shipment.shipped, controller.shipped(shipment))
        assertEquals(shipment.delivered, controller.delivered(shipment))
        assertEquals(shipment.created, controller.created(shipment))
        assertEquals(shipment.modified, controller.modified(shipment))
    }

    @Test
    fun `fulfillmentCenter resolves through the service`() = runTest {
        val center = FulfillmentCenter(
            id = centerId, companyId = UUID.random(), name = "DC", connectorKey = "manual",
            shippingProviderId = UUID.random(), address1 = "1", city = "C", state = "CA", country = "US", zip = "94000",
        )
        coEvery { fulfillmentService.getCenter(centerId) } returns center
        assertEquals(center, controller.fulfillmentCenter(shipment))
    }

    @Test
    fun `fulfillmentCenter errors when the center is missing`() = runTest {
        coEvery { fulfillmentService.getCenter(centerId) } returns null
        assertFailsWith<IllegalStateException> { controller.fulfillmentCenter(shipment) }
    }

    @Test
    fun `parcel field controller resolves containerId and lines`() {
        val parcelController = ShipmentParcelController()
        assertEquals(parcel.containerId, parcelController.containerId(parcel))
        assertEquals(parcel.lines, parcelController.lines(parcel))
    }

    @Test
    fun `line field controller resolves catalogProductId, sku and quantity`() {
        val lineController = ShipmentLineController()
        assertEquals(unpackedLine.catalogProductId, lineController.catalogProductId(unpackedLine))
        assertEquals("SKU-B", lineController.sku(unpackedLine))
        assertEquals(1, lineController.quantity(unpackedLine))
    }
}
