package bosca.analytics.experimentation.persistence

/** Stable cache identity without retaining an authentication credential. */
data class FeatureFlagCacheKey(
    val installationId: String?,
    val identity: String?,
)
