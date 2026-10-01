package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.events.InventoryReservationReleased
import bosca.ecommerce.events.InventoryShipped
import bosca.ecommerce.events.dispatch
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.InventoryInput
import bosca.ecommerce.repository.InventoryRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The reservation state machine. Every mutation re-reads the row under `select … for update` inside
 * the transaction, so two concurrent reservations serialize on the lock — the second sees the first's
 * committed counts and can correctly reject an oversell. The DB also enforces
 * `quantity >= 0`, `quantity >= pending`, `pending >= 0`, `in_cart >= 0`; the service guards the
 * cross-column invariant (`available >= requested`) the DB can't express.
 */
@ServiceImplementation
class InventoryServiceImpl(
    private val inventoryRepository: InventoryRepository,
    private val auditService: EcomAuditService,
) : InventoryService {

    override suspend fun get(id: UUID): Inventory? = inventoryRepository.get(id)

    override suspend fun getByProduct(productId: UUID): List<Inventory> = inventoryRepository.getByProduct(productId)

    override suspend fun getByCenter(fulfillmentCenterId: UUID): List<Inventory> =
        inventoryRepository.getByCenter(fulfillmentCenterId)

    override suspend fun addInventory(input: InventoryInput, principalId: UUID?): Inventory = transaction {
        val inventory = inventoryRepository.add(
            Inventory(
                productId = input.productId,
                fulfillmentCenterId = input.fulfillmentCenterId,
                sku = input.sku,
                quantity = input.quantity,
            ),
        )
        auditService.record(
            entityType = "inventory",
            entityId = inventory.id,
            action = "created",
            serializer = Inventory.serializer(),
            after = inventory,
            principalId = principalId,
        )
        inventory
    }

    override suspend fun adjust(id: UUID, quantity: Int, principalId: UUID?): Inventory = transaction {
        require(quantity >= 0) { "quantity cannot be negative" }
        val existing = inventoryRepository.getForUpdate(id) ?: error("inventory $id not found")
        check(quantity >= existing.pending) { "quantity $quantity is below pending ${existing.pending}" }
        val updated = inventoryRepository.update(existing.copy(quantity = quantity, manualQuantity = true))
            ?: error("inventory $id not found")
        auditService.record(
            entityType = "inventory",
            entityId = id,
            action = "quantity_adjusted",
            serializer = Inventory.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        updated
    }

    override suspend fun syncQuantity(id: UUID, quantity: Int, principalId: UUID?): Inventory? = transaction {
        if (quantity < 0) return@transaction null
        val existing = inventoryRepository.getForUpdate(id) ?: return@transaction null
        // Never overwrite a manually-pinned row, never drop below committed/pending stock, and skip a
        // no-op write — so the sweep only records (and counts) real changes.
        if (existing.manualQuantity || quantity < existing.pending || quantity == existing.quantity) {
            return@transaction null
        }
        val updated = inventoryRepository.update(existing.copy(quantity = quantity)) ?: return@transaction null
        auditService.record(
            entityType = "inventory",
            entityId = id,
            action = "quantity_synced",
            serializer = Inventory.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        updated
    }

    override suspend fun reserve(inventoryId: UUID, quantity: Int): Inventory = transaction {
        require(quantity > 0) { "reserve quantity must be positive" }
        val inventory = inventoryRepository.getForUpdate(inventoryId) ?: error("inventory $inventoryId not found")
        check(inventory.available >= quantity) {
            "insufficient inventory: ${inventory.available} available, $quantity requested"
        }
        inventoryRepository.update(inventory.copy(inCart = inventory.inCart + quantity))
            ?: error("inventory $inventoryId not found")
    }

    override suspend fun releaseInCart(inventoryId: UUID, quantity: Int): Inventory = transaction {
        require(quantity > 0) { "release quantity must be positive" }
        val inventory = inventoryRepository.getForUpdate(inventoryId) ?: error("inventory $inventoryId not found")
        val release = minOf(quantity, inventory.inCart)
        val updated = inventoryRepository.update(inventory.copy(inCart = inventory.inCart - release))
            ?: error("inventory $inventoryId not found")
        if (release > 0) {
            InventoryReservationReleased(inventoryId = inventoryId, productId = updated.productId, quantity = release).dispatch()
        }
        updated
    }

    override suspend fun commitToPending(inventoryId: UUID, quantity: Int): Inventory = transaction {
        require(quantity > 0) { "commit quantity must be positive" }
        val inventory = inventoryRepository.getForUpdate(inventoryId) ?: error("inventory $inventoryId not found")
        check(inventory.inCart >= quantity) {
            "cannot commit $quantity: only ${inventory.inCart} held in cart"
        }
        inventoryRepository.update(
            inventory.copy(inCart = inventory.inCart - quantity, pending = inventory.pending + quantity),
        ) ?: error("inventory $inventoryId not found")
    }

    override suspend fun releasePending(inventoryId: UUID, quantity: Int): Inventory = transaction {
        require(quantity > 0) { "release quantity must be positive" }
        val inventory = inventoryRepository.getForUpdate(inventoryId) ?: error("inventory $inventoryId not found")
        val release = minOf(quantity, inventory.pending)
        val updated = inventoryRepository.update(inventory.copy(pending = inventory.pending - release))
            ?: error("inventory $inventoryId not found")
        if (release > 0) {
            InventoryReservationReleased(inventoryId = inventoryId, productId = updated.productId, quantity = release).dispatch()
        }
        updated
    }

    override suspend fun ship(inventoryId: UUID, quantity: Int, principalId: UUID?): Inventory = transaction {
        require(quantity > 0) { "ship quantity must be positive" }
        val inventory = inventoryRepository.getForUpdate(inventoryId) ?: error("inventory $inventoryId not found")
        check(inventory.pending >= quantity) { "cannot ship $quantity: only ${inventory.pending} pending" }
        check(inventory.quantity >= quantity) { "cannot ship $quantity: only ${inventory.quantity} on hand" }
        val updated = inventoryRepository.update(
            inventory.copy(quantity = inventory.quantity - quantity, pending = inventory.pending - quantity),
        ) ?: error("inventory $inventoryId not found")
        auditService.record(
            entityType = "inventory",
            entityId = inventoryId,
            action = "shipped",
            serializer = Inventory.serializer(),
            before = inventory,
            after = updated,
            details = JsonObject(mapOf("quantity" to JsonPrimitive(quantity))),
            principalId = principalId,
        )
        InventoryShipped(inventoryId = inventoryId, productId = updated.productId, quantity = quantity).dispatch()
        updated
    }
}
