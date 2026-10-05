package bosca.ecommerce.service

import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.FulfillmentCenterInput
import bosca.serialization.UUID
import bosca.service.Service

/** Fulfillment centers (warehouses). */
interface FulfillmentService : Service {

    /** A fulfillment center by id. */
    suspend fun getCenter(id: UUID): FulfillmentCenter?

    /** Every fulfillment center belonging to a company, ordered by name. */
    suspend fun getCentersByCompany(companyId: UUID): List<FulfillmentCenter>

    /** Create a fulfillment center. */
    suspend fun addCenter(input: FulfillmentCenterInput, principalId: UUID?): FulfillmentCenter

    /** Edit a fulfillment center's name, connector, shipping provider, and address. Audited. */
    suspend fun editCenter(id: UUID, input: FulfillmentCenterInput, principalId: UUID?): FulfillmentCenter

    /**
     * The inventory-sync sweep (the scheduled job): for every syncable center due for a refresh
     * (`connectorKey != "manual"` and the per-center [FulfillmentCenter.syncIntervalSeconds] has
     * elapsed since [FulfillmentCenter.lastSynced]), resolve its [InventoryConnector] and pull the
     * on-hand quantity for each of its inventory rows, updating the changed ones. A center pointed at an
     * unregistered connector is skipped (logged), not failed. Returns the number of inventory rows
     * actually updated.
     */
    suspend fun syncInventory(): Int
}
