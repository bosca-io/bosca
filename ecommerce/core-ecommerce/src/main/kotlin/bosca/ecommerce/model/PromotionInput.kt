package bosca.ecommerce.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Creates or edits a promotion. [quantity] null = unlimited redemptions (no availability cap). */
@Serializable
data class PromotionInput(
    @Contextual
    val storeId: UUID,
    val code: String,
    val name: String,
    val type: PromotionType,
    val rule: Rule,
    @Contextual
    val starts: OffsetDateTime,
    @Contextual
    val ends: OffsetDateTime,
    val quantity: Long? = null,
    val perAccountLimit: Long = 0,
    val frequencyLimit: FrequencyLimit = FrequencyLimit.FOREVER,
)
