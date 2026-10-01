package bosca.profile.guide.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Serializable

/**
 * Aggregate progression counts for a guide across all of its metadata versions.
 *
 * Active progressions are the records still present in the progress table. Historical
 * progressions are records moved to the history table, while completions are the subset
 * of those history records with a completion timestamp.
 */
@Serializable
data class GuideProgressStatistics(
    @ColumnName("active_progressions")
    val activeProgressions: Long,
    @ColumnName("active_profiles")
    val activeProfiles: Long,
    @ColumnName("historical_progressions")
    val historicalProgressions: Long,
    val completions: Long,
    @ColumnName("total_progressions")
    val totalProgressions: Long,
    @ColumnName("unique_profiles")
    val uniqueProfiles: Long,
)
