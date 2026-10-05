package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A numbered credit instrument (gift/store credit) issued by a company, spendable as a
 * COMPANY_CREDIT payment. [number] is the redeemable natural key (unique). [balance] is the
 * remaining spendable amount; [paid] is the total spent so far. Balance changes run under row
 * locking and write an [bosca.ecommerce.model] audit entry with before/after snapshots.
 */
@BatchKey("id")
@Serializable
data class CompanyCredit(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    @Contextual
    @ColumnName("account_id")
    val accountId: UUID? = null,
    val number: String,
    val description: String? = null,
    val balance: Money = Money.ZERO,
    val paid: Money = Money.ZERO,
    @Contextual
    val expires: OffsetDateTime? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
