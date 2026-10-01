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
 * A shopper within a company, backed by a profile. Auth resolves principal -> profile -> customer;
 * principal ids are NEVER stored here. A customer can belong to several billing accounts and has a
 * [defaultAccountId] used for new carts/orders. Customers without a principal (imported/guest) are
 * valid — [profileId] is the only identity link.
 */
@BatchKey("id")
@Serializable
data class Customer(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID,
    @Contextual
    @ColumnName("default_account_id")
    val defaultAccountId: UUID? = null,
    @property:DbMapper(JsonbMapper::class)
    val extras: CustomerExtras = EmptyCustomerExtras,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
