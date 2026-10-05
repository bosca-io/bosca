package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Customer
import bosca.serialization.UUID

/** Persistence for `ecom.customers`. */
@Repository
interface CustomerRepository {

    @Query("select * from ecom.customers where id = :id and deleted is null")
    suspend fun get(id: UUID): Customer?

    @Query("select * from ecom.customers where id = any(:ids) and deleted is null")
    suspend fun getByIds(ids: List<UUID>): List<Customer>

    @Query("select * from ecom.customers where company_id = :companyId and profile_id = :profileId and deleted is null")
    suspend fun getByProfile(companyId: UUID, profileId: UUID): Customer?

    @Query("select * from ecom.customers where company_id = :companyId and deleted is null order by created desc offset :offset limit :limit")
    suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Customer>

    @Query(
        """
        insert into ecom.customers (company_id, profile_id, default_account_id, extras)
        values (:companyId, :profileId, :defaultAccountId, :extras::jsonb)
        returning *
        """,
    )
    suspend fun add(customer: Customer): Customer

    @Query(
        """
        update ecom.customers
           set extras = :extras::jsonb, default_account_id = :defaultAccountId, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(customer: Customer): Customer?

    @Query("update ecom.customers set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)

    @Query(
        "select c.* from ecom.customers c " +
            "join ecom.account_customers ac on ac.customer_id = c.id " +
            "where ac.account_id = :accountId and c.deleted is null",
    )
    suspend fun getByAccount(accountId: UUID): List<Customer>
}
