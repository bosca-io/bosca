package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.ShippingProvider
import bosca.serialization.UUID

/** Persistence for `ecom.shipping_providers` (unique business `key`). */
@Repository
interface ShippingProviderRepository {

    @Query("select * from ecom.shipping_providers where id = :id and deleted is null")
    suspend fun get(id: UUID): ShippingProvider?

    @Query("select * from ecom.shipping_providers where company_id = :companyId and deleted is null order by created")
    suspend fun getByCompany(companyId: UUID): List<ShippingProvider>

    @Query(
        """
        insert into ecom.shipping_providers (company_id, name, key, provider_key, configuration)
        values (:companyId, :name, :key, :providerKey, :configuration::jsonb)
        returning *
        """,
    )
    suspend fun add(provider: ShippingProvider): ShippingProvider

    @Query(
        """
        update ecom.shipping_providers
           set name = :name, key = :key, provider_key = :providerKey, configuration = :configuration::jsonb, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(provider: ShippingProvider): ShippingProvider?

    @Query("update ecom.shipping_providers set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
