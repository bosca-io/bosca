package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A warehouse that holds inventory and hands off to a shipping provider. [connectorKey] selects the
 * inventory-sync connector (a DI-registered SPI introduced with the scheduled sync job; "manual"
 * means no external sync). [syncIntervalSeconds] is how often that sync runs; [lastSynced] is when the
 * sweep last pulled this center (null = never), so the sweep can honor the per-center interval.
 */
@BatchKey("id")
@Serializable
data class FulfillmentCenter(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    val name: String,
    @ColumnName("connector_key")
    val connectorKey: String,
    @Contextual
    @ColumnName("shipping_provider_id")
    val shippingProviderId: UUID,
    val address1: String,
    val address2: String? = null,
    val city: String,
    val state: String,
    val country: String,
    val zip: String,
    @ColumnName("sync_interval_seconds")
    val syncIntervalSeconds: Int = 60,
    @Contextual
    @ColumnName("last_synced")
    val lastSynced: OffsetDateTime? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
