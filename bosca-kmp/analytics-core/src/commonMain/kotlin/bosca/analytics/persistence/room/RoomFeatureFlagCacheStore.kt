package bosca.analytics.persistence.room

import bosca.analytics.experimentation.FeatureFlag
import bosca.analytics.experimentation.persistence.FeatureFlagCacheKey
import bosca.analytics.experimentation.persistence.FeatureFlagCacheStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal class RoomFeatureFlagCacheStore(database: AnalyticsDatabase) : FeatureFlagCacheStore {
    private val storage = database.storage()

    override suspend fun load(key: FeatureFlagCacheKey): List<FeatureFlag>? =
        storage.readFeatureFlags(key.installationDatabaseId(), key.identityDatabaseId())?.let { payload ->
            JSON.decodeFromString(ListSerializer(FeatureFlag.serializer()), payload)
        }

    override suspend fun save(key: FeatureFlagCacheKey, flags: List<FeatureFlag>) {
        storage.writeFeatureFlags(
            AnalyticsFeatureFlagCacheEntity(
                installationId = key.installationDatabaseId(),
                identity = key.identityDatabaseId(),
                payload = JSON.encodeToString(ListSerializer(FeatureFlag.serializer()), flags),
            ),
        )
    }

    private fun FeatureFlagCacheKey.installationDatabaseId() = installationId ?: ANONYMOUS_INSTALLATION

    private fun FeatureFlagCacheKey.identityDatabaseId() = identity ?: ANONYMOUS_IDENTITY

    private companion object {
        const val ANONYMOUS_INSTALLATION = "anonymous-installation"
        const val ANONYMOUS_IDENTITY = "anonymous-identity"
        val JSON = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }
    }
}
