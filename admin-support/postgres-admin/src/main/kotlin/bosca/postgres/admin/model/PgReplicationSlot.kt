package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Replication slot metadata from `pg_replication_slots`, showing the slot's type,
 * activity status, and WAL retention to monitor replication health and prevent
 * unbounded WAL growth from inactive slots.
 */
data class PgReplicationSlot(
    @ColumnName("slot_name") val slotName: String,
    @ColumnName("slot_type") val slotType: String,
    val active: Boolean,
    @ColumnName("database_name") val databaseName: String?,
    @ColumnName("confirmed_flush_lsn") val confirmedFlushLsn: String?,
    @ColumnName("retained_wal_bytes") val retainedWalBytes: Long?,
)
