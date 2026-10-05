package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.AccountAddress
import bosca.serialization.UUID

/** Persistence for `ecom.account_addresses`. */
@Repository
interface AccountAddressRepository {

    @Query(
        """
        insert into ecom.account_addresses
            (account_id, type, preferred, address1, address2, city, state, country, zip, phone, note)
        values
            (:accountId, (:type)::ecom.address_type, :preferred, :address1, :address2, :city, :state, :country, :zip, :phone, :note)
        returning *
        """,
    )
    suspend fun add(address: AccountAddress): AccountAddress

    @Query("select * from ecom.account_addresses where account_id = :accountId order by created")
    suspend fun getByAccount(accountId: UUID): List<AccountAddress>
}
