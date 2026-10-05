package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.PaymentProvider
import bosca.serialization.UUID

/** Persistence for `ecom.payment_providers`. */
@Repository
interface PaymentProviderRepository {

    @Query("select * from ecom.payment_providers where id = :id and deleted is null")
    suspend fun get(id: UUID): PaymentProvider?

    @Query("select * from ecom.payment_providers where company_id = :companyId and deleted is null order by created")
    suspend fun getByCompany(companyId: UUID): List<PaymentProvider>

    @Query(
        """
        insert into ecom.payment_providers (company_id, name, provider_key, configuration)
        values (:companyId, :name, :providerKey, :configuration::jsonb)
        returning *
        """,
    )
    suspend fun add(provider: PaymentProvider): PaymentProvider

    @Query(
        """
        update ecom.payment_providers
           set name = :name, provider_key = :providerKey, configuration = :configuration::jsonb, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(provider: PaymentProvider): PaymentProvider?

    @Query("update ecom.payment_providers set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
