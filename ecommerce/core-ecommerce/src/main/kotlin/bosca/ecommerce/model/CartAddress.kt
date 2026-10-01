package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A billing or shipping address attached to a cart (`ecom.cart_addresses`, unique per cart+type).
 * Carries the contact fields tax/shipping/payment need; [validated] flips once a provider confirms it.
 * One row per [AddressType] per cart.
 */
@BatchKey("id")
@Serializable
data class CartAddress(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("cart_id")
    val cartId: UUID,
    val type: AddressType,
    @ColumnName("first_name")
    val firstName: String,
    @ColumnName("last_name")
    val lastName: String,
    val address1: String,
    val address2: String? = null,
    val city: String,
    val state: String,
    val country: String,
    val zip: String,
    val phone: String,
    val email: String? = null,
    val note: String? = null,
    val validated: Boolean = false,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
)
