package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Tracks the utilization of a database sequence relative to its maximum value, helping
 * detect sequences at risk of exhaustion. Integer (int4) sequences that approach their
 * 2.1 billion maximum are a notorious source of production outages, so proactive
 * monitoring of sequence headroom is critical.
 */
data class PgSequenceUsage(
    @ColumnName("schema_name") val schemaName: String,
    @ColumnName("sequence_name") val sequenceName: String,
    @ColumnName("data_type") val dataType: String,
    @ColumnName("current_value") val currentValue: Long,
    @ColumnName("max_value") val maxValue: Long,
    @ColumnName("percent_used") val percentUsed: Float,
)
