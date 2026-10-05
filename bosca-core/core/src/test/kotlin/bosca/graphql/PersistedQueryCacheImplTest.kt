package bosca.graphql

import bosca.graphql.persistedqueries.PersistedQueryCacheImpl
import kotlin.test.Test
import kotlin.test.assertNotNull

class PersistedQueryCacheImplTest {

    @Test
    fun `PersistedQueryCacheImpl can be instantiated`() {
        val cache = PersistedQueryCacheImpl()
        assertNotNull(cache)
    }

    @Test
    fun `clear does not throw on empty cache`() {
        val cache = PersistedQueryCacheImpl()
        cache.clear()
    }

    @Test
    fun `clear can be called multiple times`() {
        val cache = PersistedQueryCacheImpl()
        cache.clear()
        cache.clear()
    }
}
