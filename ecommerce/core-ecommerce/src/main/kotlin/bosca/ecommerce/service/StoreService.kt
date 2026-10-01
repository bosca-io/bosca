package bosca.ecommerce.service

import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreInput
import bosca.serialization.UUID
import bosca.service.Service

/** Stores — the selling context carts/payments/plans/promotions hang off. */
interface StoreService : Service {

    /** A store by id. */
    suspend fun get(id: UUID): Store?

    /** Stores by id (batch — backs the nested-resolver DataLoader path). */
    suspend fun getByIds(ids: List<UUID>): List<Store>

    /** A store by its unique storefront identifier (slug). */
    suspend fun getByIdentifier(identifier: String): Store?

    /** Every store belonging to a company, ordered by name. */
    suspend fun getByCompany(companyId: UUID): List<Store>

    /** Create a store. */
    suspend fun create(input: StoreInput, principalId: UUID?): Store

    /** Edit the store's bindings and cart policy. */
    suspend fun edit(id: UUID, input: StoreInput, principalId: UUID?): Store
}
