package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Account
import bosca.serialization.UUID

/**
 * Persistence for `ecom.accounts`. Credit changes go through [getForUpdate] (row lock) then
 * [update], so concurrent add/spend serialize and never overdraw.
 */
@Repository
interface AccountRepository {

    @Query("select * from ecom.accounts where id = :id and deleted is null")
    suspend fun get(id: UUID): Account?

    @Query("select * from ecom.accounts where id = any(:ids) and deleted is null")
    suspend fun getByIds(ids: List<UUID>): List<Account>

    @Query("select * from ecom.accounts where id = :id and deleted is null for update")
    suspend fun getForUpdate(id: UUID): Account?

    @Query(
        """
        insert into ecom.accounts (company_id, type, credit, extras)
        values (:companyId, (:type)::ecom.account_type, :credit, :extras::jsonb)
        returning *
        """,
    )
    suspend fun add(account: Account): Account

    @Query(
        """
        update ecom.accounts
           set type = (:type)::ecom.account_type, credit = :credit, extras = :extras::jsonb, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(account: Account): Account?

    /**
     * Profile-field update for admin `edit` — sets type/extras only, never `credit`. The balance is a
     * contended column owned exclusively by the row-locked credit paths (add/spend); an unlocked edit
     * that wrote `credit` from a stale snapshot would clobber a concurrent change.
     */
    @Query(
        """
        update ecom.accounts
           set type = (:type)::ecom.account_type, extras = :extras::jsonb, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun updateProfile(account: Account): Account?

    @Query("update ecom.accounts set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)

    @Query(
        "select a.* from ecom.accounts a " +
            "join ecom.account_customers ac on ac.account_id = a.id " +
            "where ac.customer_id = :customerId and a.deleted is null",
    )
    suspend fun getByCustomer(customerId: UUID): List<Account>

    @Query("select * from ecom.accounts where company_id = :companyId and deleted is null order by created desc offset :offset limit :limit")
    suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Account>
}
