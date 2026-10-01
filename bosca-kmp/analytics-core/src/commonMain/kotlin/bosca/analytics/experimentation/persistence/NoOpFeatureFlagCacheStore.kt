package bosca.analytics.experimentation.persistence

import bosca.analytics.experimentation.FeatureFlag

object NoOpFeatureFlagCacheStore : FeatureFlagCacheStore {
    override suspend fun load(key: FeatureFlagCacheKey): List<FeatureFlag>? = null

    override suspend fun save(key: FeatureFlagCacheKey, flags: List<FeatureFlag>) = Unit
}
