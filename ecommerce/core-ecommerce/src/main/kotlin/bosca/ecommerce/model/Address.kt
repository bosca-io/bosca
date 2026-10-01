package bosca.ecommerce.model

import kotlinx.serialization.Serializable

/**
 * A postal address value type, embedded in account addresses and fulfillment centers, and the
 * base of the contact-bearing [AddressInfo] used on carts.
 */
@Serializable
data class Address(
    val address1: String,
    val address2: String? = null,
    val city: String,
    /** State/province; widened beyond the legacy US-only 2-char code. */
    val state: String,
    /** ISO 3166-1 alpha-2 country code. */
    val country: String,
    val zip: String,
) {
    /** Whether the required postal fields are all present. */
    val valid: Boolean
        get() = address1.isNotBlank() && city.isNotBlank() && state.isNotBlank() &&
            country.isNotBlank() && zip.isNotBlank()
}

/**
 * A postal address plus the contact details a cart needs (name, phone, email, note) and a
 * provider-validation flag. Stored on cart addresses.
 */
@Serializable
data class AddressInfo(
    val firstName: String,
    val lastName: String,
    val address1: String,
    val address2: String? = null,
    val city: String,
    val state: String,
    /** ISO 3166-1 alpha-2 country code. */
    val country: String,
    val zip: String,
    val phone: String,
    val email: String? = null,
    val note: String? = null,
    /** Whether the address passed validation with the shipping provider. */
    val validated: Boolean = false,
) {
    /** The bare postal [Address] without the contact fields. */
    fun toAddress(): Address = Address(address1, address2, city, state, country, zip)
}
