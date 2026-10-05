package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/** The `ecom.account_customers` many-to-many join (an account has several customers). */
@Repository
interface AccountCustomerRepository {

    @Query(
        "insert into ecom.account_customers (account_id, customer_id) values (:accountId, :customerId) " +
            "on conflict (account_id, customer_id) do nothing",
    )
    suspend fun add(accountId: UUID, customerId: UUID)
}
