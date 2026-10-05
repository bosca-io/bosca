package bosca.ecommerce.service

import bosca.db.transaction
import bosca.di.provide
import bosca.ecommerce.events.ShipmentCreated
import bosca.ecommerce.events.ShipmentShipped
import bosca.ecommerce.events.dispatch
import bosca.ecommerce.model.Address
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.Parcel
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentLabel
import bosca.ecommerce.model.ShipmentLine
import bosca.ecommerce.model.ShipmentParcel
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.WeightUnit
import bosca.ecommerce.repository.ShipmentRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory

/**
 * Order fulfillment. When a cart is paid, its physical lines are grouped by fulfillment center and the
 * [Packer] boxes each center's items for density — one shipment per packed box. Each
 * line's inventory reservation supplies the row to draw and its product the box footprint, so a
 * shipment records exactly the [ShipmentLine.inventoryId]s shipping will draw down, in the container it
 * was packed into.
 */
@ServiceImplementation
class ShipmentServiceImpl(
    private val shipmentRepository: ShipmentRepository,
    private val inventoryService: InventoryService,
    private val catalogProductService: CatalogProductService,
    private val productService: ProductService,
    private val packer: Packer,
    private val fulfillmentService: FulfillmentService,
    private val providerService: ProviderService,
    private val companyService: CompanyService,
    private val auditService: EcomAuditService,
) : ShipmentService {

    override suspend fun get(id: UUID): Shipment? = shipmentRepository.get(id)

    override suspend fun getByCart(cartId: UUID): List<Shipment> = shipmentRepository.getByCart(cartId)

    override suspend fun createForPaidCart(cart: Cart, principalId: UUID?): List<Shipment> = transaction {
        // Idempotent: a paid order is split exactly once. submit()'s !alreadyPaid guard already ensures
        // a single call, but guard here too so the service is safe to call directly / on redelivery.
        val existing = shipmentRepository.getByCart(cart.id)
        if (existing.isNotEmpty()) return@transaction existing

        // Each physical line's reservations become packable candidates grouped by fulfillment center; a
        // candidate carries the inventory row it draws plus the box footprint (product dims + weight).
        val byCenter = LinkedHashMap<UUID, MutableList<Candidate>>()
        cart.items.filter { it.type == ProductType.PHYSICAL }.forEach { item ->
            if (item.reservations.isEmpty()) {
                // Untracked physical line: no inventory row to ship from. Don't block the order.
                log.warn("cart {} physical line {} has no inventory reservation; it will not be fulfilled from a center", cart.id, item.id)
                return@forEach
            }
            val product = catalogProductService.get(item.catalogProductId)?.let { productService.get(it.productId) }
            if (product == null) {
                log.warn("cart {} line {} product unresolved; packing with unknown dimensions", cart.id, item.id)
            }
            item.reservations.forEach { reservation ->
                val inventory = inventoryService.get(reservation.inventoryId)
                if (inventory == null) {
                    log.warn("cart {} reservation references missing inventory {}; skipping", cart.id, reservation.inventoryId)
                    return@forEach
                }
                byCenter.getOrPut(inventory.fulfillmentCenterId) { mutableListOf() }.add(
                    Candidate(
                        catalogProductId = item.catalogProductId,
                        inventoryId = inventory.id,
                        sku = inventory.sku,
                        quantity = reservation.quantity,
                        width = product?.width ?: 0.0,
                        height = product?.height ?: 0.0,
                        length = product?.length ?: 0.0,
                        weight = product?.weight ?: 0.0,
                    ),
                )
            }
        }

        byCenter.map { (centerId, candidates) ->
            // Pack this center's items into boxes for density; the boxes ship together as the parcels of
            // ONE shipment per center. Anything that fits no container is held in `unpacked` and the
            // shipment is UNABLE_TO_PACKAGE — never a loose box.
            val lines = candidates.map { ShipmentLine(it.catalogProductId, it.inventoryId, it.sku, it.quantity) }
            val (parcels, unpacked) = packLines(cart.companyId, lines)
            val shipment = shipmentRepository.add(
                Shipment(
                    cartId = cart.id,
                    storeId = cart.storeId,
                    companyId = cart.companyId,
                    fulfillmentCenterId = centerId,
                    status = if (unpacked.isEmpty()) ShipmentStatus.AWAITING else ShipmentStatus.UNABLE_TO_PACKAGE,
                    parcels = parcels,
                    unpacked = unpacked,
                ),
            )
            audit(shipment, "created", principalId)
            ShipmentCreated(
                shipmentId = shipment.id,
                storeId = shipment.storeId,
                cartId = shipment.cartId,
                companyId = shipment.companyId,
                fulfillmentCenterId = shipment.fulfillmentCenterId,
            ).dispatch()
            shipment
        }
    }

    /** A reservation made packable: the inventory row it draws + the product's box footprint. */
    private class Candidate(
        val catalogProductId: UUID,
        val inventoryId: UUID,
        val sku: String,
        val quantity: Int,
        val width: Double,
        val height: Double,
        val length: Double,
        val weight: Double,
    )

    override suspend fun ship(shipmentId: UUID, carrier: String?, tracking: String?, principalId: UUID?): Shipment = transaction {
        val shipment = shipmentRepository.getForUpdate(shipmentId) ?: error("shipment $shipmentId not found")
        check(shipment.status == ShipmentStatus.AWAITING) { "shipment $shipmentId is not awaiting dispatch (${shipment.status})" }
        // Carrier assignment: an operator-supplied carrier/tracking is honored as-is; otherwise the
        // fulfillment center's shipping provider assigns one (purchaseLabel). Either may be absent — an
        // unlabeled shipment is allowed (e.g. no provider configured).
        val label = if (carrier != null || tracking != null) ShipmentLabel(carrier = carrier, tracking = tracking) else resolveLabel(shipment)
        // Draw down on-hand + pending stock for each line via the canonical inventory ship path (audits
        // and emits InventoryShipped per row). The paid order already moved these units into `pending`.
        shipment.parcels.flatMap { it.lines }.forEach { line -> inventoryService.ship(line.inventoryId, line.quantity, principalId) }
        val shipped = shipmentRepository.update(
            shipment.copy(
                status = ShipmentStatus.SHIPPED,
                carrier = label?.carrier,
                tracking = label?.tracking,
                labelUrl = label?.labelUrl,
                shipped = OffsetDateTime.now(),
            ),
        ) ?: error("shipment $shipmentId not found")
        audit(shipped, "shipped", principalId)
        ShipmentShipped(
            shipmentId = shipped.id,
            storeId = shipped.storeId,
            cartId = shipped.cartId,
            companyId = shipped.companyId,
            fulfillmentCenterId = shipped.fulfillmentCenterId,
        ).dispatch()
        shipped
    }

    /**
     * Ask the shipment's fulfillment-center shipping provider to assign a carrier (purchase a label).
     * Best-effort: a missing center/provider, an unregistered provider key, or a provider error yields
     * no label (the shipment ships unlabeled) rather than failing the dispatch.
     */
    private suspend fun resolveLabel(shipment: Shipment): ShipmentLabel? {
        return try {
            val center = fulfillmentService.getCenter(shipment.fulfillmentCenterId) ?: return null
            val config = providerService.getShippingProvider(center.shippingProviderId) ?: return null
            // Resolve the carrier context the provider needs (origin = the center, destination = the
            // cart's shipping address, parcels = the packed boxes' container dimensions + line weights
            // in the company's units) so providers stay thin.
            val origin = Address(center.address1, center.address2, center.city, center.state, center.country, center.zip)
            val shippingAddress = provide<CartService>().getAddresses(shipment.cartId).firstOrNull { it.type == AddressType.SHIPPING }
            val destination = if (shippingAddress != null) {
                Address(shippingAddress.address1, shippingAddress.address2, shippingAddress.city, shippingAddress.state, shippingAddress.country, shippingAddress.zip)
            } else {
                null
            }
            val company = companyService.get(shipment.companyId)
            val lengthUnit = if (company != null) company.lengthUnit else LengthUnit.INCHES
            val weightUnit = if (company != null) company.weightUnit else WeightUnit.POUNDS
            val parcels = shipment.parcels.mapNotNull { parcel ->
                val containerId = parcel.containerId ?: return@mapNotNull null
                val container = provide<ContainerService>().get(containerId) ?: return@mapNotNull null
                val weight = container.weight + parcel.lines.sumOf { lineWeight(it) * it.quantity }
                Parcel(container.length, container.width, container.height, weight, lengthUnit, weightUnit)
            }
            provide<ShippingRateProvider>(name = config.providerKey).purchaseLabel(config, shipment, origin, destination, parcels)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not assign a carrier label for shipment {}: {}", shipment.id, e.message)
            null
        }
    }

    private suspend fun lineWeight(line: ShipmentLine): Double {
        val catalogProduct = catalogProductService.get(line.catalogProductId) ?: return 0.0
        val product = productService.get(catalogProduct.productId) ?: return 0.0
        return product.weight
    }

    override suspend fun repack(shipmentId: UUID, principalId: UUID?): Shipment = transaction {
        val shipment = shipmentRepository.getForUpdate(shipmentId) ?: error("shipment $shipmentId not found")
        check(shipment.status == ShipmentStatus.AWAITING || shipment.status == ShipmentStatus.UNABLE_TO_PACKAGE) {
            "only an awaiting or unable-to-package shipment can be re-packed (${shipment.status})"
        }
        // Reconstruct the full item set (packed + unpacked), collapsing a SKU split across boxes back
        // into one line, then re-pack it with the company's current containers.
        val lines = (shipment.parcels.flatMap { it.lines } + shipment.unpacked)
            .groupBy { it.inventoryId }
            .map { (_, ls) -> ls.first().copy(quantity = ls.sumOf { it.quantity }) }
        if (lines.isEmpty()) return@transaction shipment
        val (parcels, unpacked) = packLines(shipment.companyId, lines)
        val updated = shipmentRepository.update(
            shipment.copy(
                parcels = parcels,
                unpacked = unpacked,
                status = if (unpacked.isEmpty()) ShipmentStatus.AWAITING else ShipmentStatus.UNABLE_TO_PACKAGE,
            ),
        ) ?: error("shipment $shipmentId not found")
        audit(updated, "repacked", principalId)
        updated
    }

    override suspend fun repackUnpackable(companyId: UUID, principalId: UUID?): Int {
        // A newly added/edited container may now fit shipments that were blocked — re-pack those.
        val blocked = shipmentRepository.getUnpackableByCompany(companyId)
        blocked.forEach { repack(it.id, principalId) }
        return blocked.size
    }

    /** Re-runs the packer over [lines], returning the real-container parcels and any unpackable remainder. */
    private suspend fun packLines(companyId: UUID, lines: List<ShipmentLine>): Pair<List<ShipmentParcel>, List<ShipmentLine>> {
        val byRef = lines.associateBy { it.inventoryId }
        val containers = provide<ContainerService>().getByCompany(companyId)
        val packItems = lines.map { line ->
            val product = catalogProductService.get(line.catalogProductId)?.let { productService.get(it.productId) }
            PackItem(
                ref = line.inventoryId,
                length = product?.length ?: 0.0, width = product?.width ?: 0.0,
                height = product?.height ?: 0.0, weight = product?.weight ?: 0.0,
                quantity = line.quantity,
            )
        }
        val result = packer.pack(packItems, containers)
        fun lineFor(packed: PackedItem): ShipmentLine {
            val l = byRef.getValue(packed.ref)
            return ShipmentLine(catalogProductId = l.catalogProductId, inventoryId = l.inventoryId, sku = l.sku, quantity = packed.quantity)
        }
        val parcels = result.boxes.map { box -> ShipmentParcel(containerId = box.containerId, lines = box.items.map(::lineFor)) }
        return parcels to result.unpacked.map(::lineFor)
    }

    override suspend fun getByStore(storeId: UUID, status: ShipmentStatus?, offset: Int, limit: Int): List<Shipment> =
        if (status == null) {
            shipmentRepository.getByStore(storeId, offset, limit)
        } else {
            shipmentRepository.getByStoreAndStatus(storeId, status.name.lowercase(), offset, limit)
        }

    override suspend fun refreshTracking(shipmentId: UUID, principalId: UUID?): Shipment = transaction {
        val shipment = shipmentRepository.getForUpdate(shipmentId) ?: error("shipment $shipmentId not found")
        // Only an in-flight shipment (dispatched, not yet delivered/terminal) can advance.
        if (!shipment.status.isDispatched || shipment.status.isTerminal) return@transaction shipment
        val update = resolveTracking(shipment) ?: return@transaction shipment
        // Monotonic: a tracking reading only ever moves the shipment forward along the lifecycle.
        if (update.status.lifecycleRank <= shipment.status.lifecycleRank) return@transaction shipment
        val updated = shipmentRepository.update(
            shipment.copy(
                status = update.status,
                carrierStatus = update.carrierStatus ?: shipment.carrierStatus,
                delivered = update.delivered ?: shipment.delivered,
            ),
        ) ?: error("shipment $shipmentId not found")
        audit(updated, "tracking_updated", principalId)
        updated
    }

    override suspend fun sweepTracking(): Int {
        // One reading per in-flight shipment per sweep — they stay in-flight until delivered, so this
        // must NOT loop-until-empty; the cron cadence drives the progression, oldest-polled first.
        val batch = shipmentRepository.getInFlight(SWEEP_BATCH_SIZE)
        batch.forEach { refreshTracking(it.id, principalId = null) }
        return batch.size
    }

    /**
     * Ask the shipment's fulfillment-center shipping provider for its latest tracking reading.
     * Best-effort: a missing center/provider, unregistered key, or provider error yields no update
     * rather than failing the refresh/sweep.
     */
    private suspend fun resolveTracking(shipment: Shipment): bosca.ecommerce.model.TrackingUpdate? {
        return try {
            val center = fulfillmentService.getCenter(shipment.fulfillmentCenterId) ?: return null
            val config = providerService.getShippingProvider(center.shippingProviderId) ?: return null
            provide<ShippingRateProvider>(name = config.providerKey).track(config, shipment)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not refresh tracking for shipment {}: {}", shipment.id, e.message)
            null
        }
    }

    private suspend fun audit(shipment: Shipment, action: String, principalId: UUID?) {
        auditService.record(
            entityType = "shipment", entityId = shipment.id, action = action,
            serializer = Shipment.serializer(),
            after = shipment,
            principalId = principalId, storeId = shipment.storeId,
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ShipmentServiceImpl::class.java)
        private const val SWEEP_BATCH_SIZE = 500
    }
}
