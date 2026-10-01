package bosca.segmentation.cache

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BannerWeightCacheKeyTest {

    @Test
    fun `toLocalKey creates correct cache key`() {
        val profileId = UUID.random()
        val keyId = BannerWeightKeyId(profileId, "top")
        val cacheKey = BannerWeightCacheKeySerializer.toLocalKey("test-cache", keyId)
        assertEquals("test-cache", cacheKey.cacheName)
        assertEquals(keyId, cacheKey.key)
    }

    @Test
    fun `round-trip with profileId preserves key`() {
        val profileId = UUID.random()
        val keyId = BannerWeightKeyId(profileId, "hero")
        val cacheKey = BannerWeightCacheKeySerializer.toLocalKey("banners", keyId)
        val remoteKey = cacheKey.toRemoteKey()
        val restored = BannerWeightCacheKeySerializer.fromRemoteKey(remoteKey)
        assertEquals(profileId, restored.key.profileId)
        assertEquals("hero", restored.key.placement)
    }

    @Test
    fun `round-trip with null profileId preserves key`() {
        val keyId = BannerWeightKeyId(null, "sidebar")
        val cacheKey = BannerWeightCacheKeySerializer.toLocalKey("banners", keyId)
        val remoteKey = cacheKey.toRemoteKey()
        val restored = BannerWeightCacheKeySerializer.fromRemoteKey(remoteKey)
        assertNull(restored.key.profileId)
        assertEquals("sidebar", restored.key.placement)
    }

    @Test
    fun `different placements produce different remote keys`() {
        val profileId = UUID.random()
        val topKey = BannerWeightCacheKeySerializer.toLocalKey("b", BannerWeightKeyId(profileId, "top"))
        val heroKey = BannerWeightCacheKeySerializer.toLocalKey("b", BannerWeightKeyId(profileId, "hero"))
        val topRemote = topKey.toRemoteKey()
        val heroRemote = heroKey.toRemoteKey()
        assert(topRemote != heroRemote) { "Different placements should produce different remote keys" }
    }

    @Test
    fun `different profileIds produce different remote keys`() {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val key1 = BannerWeightCacheKeySerializer.toLocalKey("b", BannerWeightKeyId(id1, "top"))
        val key2 = BannerWeightCacheKeySerializer.toLocalKey("b", BannerWeightKeyId(id2, "top"))
        val remote1 = key1.toRemoteKey()
        val remote2 = key2.toRemoteKey()
        assert(remote1 != remote2) { "Different profileIds should produce different remote keys" }
    }
}
