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
 * The billing entity: carts, payments, subscriptions, and credit hang off an account, not a
 * customer. Several customers may share one account (household, business team). [credit] is the
 * stored balance spendable as ACCOUNT_CREDIT payments; changes run under row locking and are
 * audited.
 */
@BatchKey("id")
@Serializable
data class Account(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    val type: AccountType,
    val credit: Money = Money.ZERO,
    @property:DbMapper(JsonbMapper::class)
    val extras: AccountExtras = EmptyAccountExtras,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
