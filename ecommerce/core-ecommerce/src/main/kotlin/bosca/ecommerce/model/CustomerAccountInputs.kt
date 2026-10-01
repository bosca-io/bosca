package bosca.ecommerce.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Creates a customer in a company, linked to a profile ([profileId]; defaults to the caller's). */
@Serializable
data class CustomerInput(
    @Contextual
    val companyId: UUID,
    @Contextual
    val profileId: UUID? = null,
    val extras: CustomerExtras? = null,
)

/** Creates or edits a billing account, optionally attaching customers. */
@Serializable
data class AccountInput(
    @Contextual
    val companyId: UUID,
    val type: AccountType,
    val customerIds: List<@Contextual UUID> = emptyList(),
    val extras: AccountExtras? = null,
)

/** A saved address on an account. */
@Serializable
data class AccountAddressInput(
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
)
