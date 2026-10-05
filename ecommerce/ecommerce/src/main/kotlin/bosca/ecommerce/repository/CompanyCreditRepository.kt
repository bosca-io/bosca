package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.CompanyCredit
import bosca.serialization.UUID

/**
 * Persistence for `ecom.company_credits`. Balance mutations go through [getForUpdate] (row lock,
 * legacy `getForUpdate` semantics) then [updateBalance], so concurrent spends can't oversubscribe.
 */
@Repository
interface CompanyCreditRepository {

    @Query("select * from ecom.company_credits where id = :id and deleted is null")
    suspend fun get(id: UUID): CompanyCredit?

    @Query("select * from ecom.company_credits where number = :number and deleted is null")
    suspend fun getByNumber(number: String): CompanyCredit?

    @Query(
        "select * from ecom.company_credits where company_id = :companyId and deleted is null " +
            "order by created offset :offset limit :limit",
    )
    suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<CompanyCredit>

    @Query("select * from ecom.company_credits where id = :id and deleted is null for update")
    suspend fun getForUpdate(id: UUID): CompanyCredit?

    @Query(
        """
        insert into ecom.company_credits (company_id, account_id, number, description, balance, paid, expires)
        values (:companyId, :accountId, :number, :description, :balance, :paid, :expires)
        returning *
        """,
    )
    suspend fun add(credit: CompanyCredit): CompanyCredit

    @Query("update ecom.company_credits set balance = :balance, paid = :paid, modified = now() where id = :id returning *")
    suspend fun updateBalance(credit: CompanyCredit): CompanyCredit?

    @Query(
        "update ecom.company_credits set account_id = :accountId, description = :description, " +
            "balance = :balance, expires = :expires, modified = now() where id = :id and deleted is null returning *",
    )
    suspend fun update(credit: CompanyCredit): CompanyCredit?

    @Query("update ecom.company_credits set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
