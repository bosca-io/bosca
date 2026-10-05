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
 * A subscription plan within a group (`ecom.subscription_plans`), keyed `(store, key)`. [price] is
 * the recurring charge; [interval]/[intervalUnit] is the billing cadence. [configuration] is the
 * strongly-typed sealed [PlanConfiguration] (jsonb via JsonbMapper).
 */
@BatchKey("id")
@Serializable
data class SubscriptionPlan(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("plan_group_id")
    val planGroupId: UUID,
    @Contextual
    @ColumnName("store_id")
    val storeId: UUID,
    val key: String,
    val name: String,
    val description: String = "",
    val status: PlanStatus = PlanStatus.ACTIVE,
    val price: Money,
    val interval: Int,
    @ColumnName("interval_unit")
    val intervalUnit: IntervalUnit,
    @property:DbMapper(JsonbMapper::class)
    val configuration: PlanConfiguration = StandardPlanConfiguration,
    @Contextual
    val expires: OffsetDateTime? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
