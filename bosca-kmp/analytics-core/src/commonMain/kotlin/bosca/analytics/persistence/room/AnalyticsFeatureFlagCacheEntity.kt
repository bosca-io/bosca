package bosca.analytics.persistence.room

import androidx.room3.Entity

@Entity(
    tableName = "analytics_feature_flag_caches",
    primaryKeys = ["installationId", "identity"],
)
internal data class AnalyticsFeatureFlagCacheEntity(
    val installationId: String,
    val identity: String,
    val payload: String,
)
