package bosca.analytics.persistence.room

import bosca.analytics.experimentation.FeatureFlag
import bosca.analytics.experimentation.persistence.FeatureFlagCacheKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomFeatureFlagCacheStoreTest {
    @Test
    fun `feature flags remain scoped by installation and stable identity`() = runTest {
        val database = openTestDatabase("bosca-flags-room-test")
        val store = RoomFeatureFlagCacheStore(database)
        val userA = FeatureFlagCacheKey("installation", "user-a")
        val userB = FeatureFlagCacheKey("installation", "user-b")
        store.save(userA, listOf(FeatureFlag("offline-mode", JsonPrimitive(true))))

        assertEquals(JsonPrimitive(true), store.load(userA)?.single()?.value)
        assertNull(store.load(userB))

        val anonymous = FeatureFlagCacheKey(null, null)
        store.save(anonymous, emptyList())
        assertEquals(emptyList(), store.load(anonymous))
        database.close()
    }
}
