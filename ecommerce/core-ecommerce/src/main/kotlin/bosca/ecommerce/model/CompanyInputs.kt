package bosca.ecommerce.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Creates a company plus its organization + profile pair in the profiles domain. [attributes]
 * seeds the organization's free-form attribute blob.
 */
@Serializable
data class CompanyInput(
    val name: String,
    val attributes: JsonElement? = null,
)

/**
 * Issues a numbered company credit instrument. The issuing company comes from the enclosing
 * `companies { company(id) }` namespace.
 */
@Serializable
data class CompanyCreditInput(
    @Contextual
    val accountId: UUID? = null,
    /** The redeemable number; generated server-side when null/blank (the legacy behavior). */
    val number: String? = null,
    val description: String? = null,
    val balance: Money,
    @Contextual
    val expires: OffsetDateTime? = null,
)
