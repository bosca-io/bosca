package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A store promotion, keyed `(store, code)`. [rule] is the strongly-typed sealed [Rule]
 * persisted to the `rule` jsonb column via `JsonbMapper`. Active only within [starts]..[ends].
 * Redemption limits live in `ecom.promotion_availability`; redemptions are appended to
 * `ecom.promotion_redemptions`.
 */
@BatchKey("id")
@Serializable
data class Promotion(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("store_id")
    val storeId: UUID,
    val code: String,
    val name: String,
    val type: PromotionType,
    @property:DbMapper(JsonbMapper::class)
    val rule: Rule,
    @Contextual
    val starts: OffsetDateTime,
    @Contextual
    val ends: OffsetDateTime,
    @ColumnName("per_account_limit")
    val perAccountLimit: Long = 0,                       // 0 = unlimited
    @ColumnName("frequency_limit")
    val frequencyLimit: FrequencyLimit = FrequencyLimit.FOREVER,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)

/** Redemption-limit counters for a promotion (atomic guarded update prevents over-redemption). */
@Serializable
data class PromotionAvailability(
    @Contextual
    @ColumnName("promotion_id")
    val promotionId: UUID,
    val quantity: Long,
    val redeemed: Long = 0,
)

/** An append-only record that an account redeemed a promotion (optionally against a cart). */
@BatchKey("id")
@Serializable
data class PromotionRedemption(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("promotion_id")
    val promotionId: UUID,
    @Contextual
    @ColumnName("account_id")
    val accountId: UUID,
    @Contextual
    @ColumnName("cart_id")
    val cartId: UUID? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
)
