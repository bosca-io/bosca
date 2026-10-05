package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A saved billing/shipping address on an account ([preferred] prefills carts). Flat columns mirror
 * the `ecom.account_addresses` table; [toAddress] yields the [Address] value type.
 */
@BatchKey("id")
@Serializable
data class AccountAddress(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("account_id")
    val accountId: UUID,
    val type: AddressType,
    val preferred: Boolean = false,
    val address1: String,
    val address2: String? = null,
    val city: String,
    val state: String,
    val country: String,
    val zip: String,
    val phone: String,
    val note: String? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
) {
    fun toAddress(): Address = Address(address1, address2, city, state, country, zip)
}
