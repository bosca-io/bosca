package bosca.analytics.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.JsonElement

/** Evidence from one uncached warehouse query; [sql] is the statement actually executed. */
data class AnalyticsSqlResult(
    val sql: String,
    val columns: List<AnalyticsQueryColumn>,
    val records: List<JsonElement>,
    val truncated: Boolean,
    val executedAt: OffsetDateTime,
    val durationMs: Long,
)
