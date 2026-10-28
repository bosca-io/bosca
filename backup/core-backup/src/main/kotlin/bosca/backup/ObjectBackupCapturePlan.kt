package bosca.backup

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import java.time.OffsetDateTime

/** Immutable selected capture input and owned output locations, without an execution outcome. */
data class ObjectBackupCapturePlan(
    val key: String,
    @ColumnName("destination_id")
    val destinationId: String,
    @property:DbMapper(JsonbMapper::class)
    val inventory: ObjectBackupCaptureInventory,
    @ColumnName("payload_prefix")
    val payloadPrefix: String,
    val path: String,
    val created: OffsetDateTime = OffsetDateTime.now(),
    /** Saved discovery intent; null for caller-selected inventories. */
    @property:DbMapper(JsonbMapper::class)
    val discovery: ObjectBackupDiscoveryRequest? = null,
)
