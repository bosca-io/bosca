package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Background writer and checkpoint statistics from `pg_stat_bgwriter`, tracking how
 * frequently checkpoints occur, how many buffers are written during checkpoints versus
 * by the background writer, and how much sync I/O time is consumed. High checkpoint
 * frequency or excessive buffer writes can indicate a need to tune `checkpoint_timeout`,
 * `max_wal_size`, or `bgwriter_lru_maxpages`.
 */
data class PgCheckpointStats(
    @ColumnName("checkpoints_timed_count") val checkpointsTimedCount: Long,
    @ColumnName("checkpoints_requested_count") val checkpointsRequestedCount: Long,
    @ColumnName("buffers_checkpoint") val buffersCheckpoint: Long,
    @ColumnName("buffers_clean") val buffersClean: Long,
    @ColumnName("maxwritten_clean") val maxwrittenClean: Long,
    @ColumnName("buffers_backend") val buffersBackend: Long,
    @ColumnName("buffers_backend_fsync") val buffersBackendFsync: Long,
    @ColumnName("buffers_alloc") val buffersAlloc: Long,
    @ColumnName("stats_reset") val statsReset: String?,
)
