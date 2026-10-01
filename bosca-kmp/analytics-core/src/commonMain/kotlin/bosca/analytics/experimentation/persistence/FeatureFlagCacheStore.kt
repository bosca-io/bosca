package bosca.analytics.experimentation.persistence

import bosca.analytics.experimentation.FeatureFlag

/** Optional persistent cache for feature-flag evaluations. */
interface FeatureFlagCacheStore {
    /** Loads evaluations for an installation and application identity. */
    suspend fun load(key: FeatureFlagCacheKey): List<FeatureFlag>?

    /** Replaces evaluations for an installation and application identity. */
    suspend fun save(key: FeatureFlagCacheKey, flags: List<FeatureFlag>)
}
