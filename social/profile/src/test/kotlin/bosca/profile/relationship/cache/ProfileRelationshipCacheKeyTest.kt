package bosca.profile.relationship.cache

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProfileRelationshipCacheKeyTest {

    @Test
    fun `serializer round trips arbitrary relationship types`() {
        val key = ProfileRelationshipCacheKeyId(
            profileId1 = UUID.random(),
            profileId2 = UUID.random(),
            type = "friend::close/\uD83D\uDC9B",
        )
        val local = ProfileRelationshipCacheKeySerializer.toLocalKey("relationships", key)

        val restored = ProfileRelationshipCacheKeySerializer.fromRemoteKey(local.toRemoteKey())

        assertEquals(local, restored)
    }

    @Test
    fun `direction and type remain part of cache identity`() {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()
        val friend = ProfileRelationshipCacheKeySerializer.toLocalKey(
            "relationships",
            ProfileRelationshipCacheKeyId(profileId1, profileId2, "friend"),
        )
        val reverse = ProfileRelationshipCacheKeySerializer.toLocalKey(
            "relationships",
            ProfileRelationshipCacheKeyId(profileId2, profileId1, "friend"),
        )
        val follower = ProfileRelationshipCacheKeySerializer.toLocalKey(
            "relationships",
            ProfileRelationshipCacheKeyId(profileId1, profileId2, "follower"),
        )

        assertNotEquals(friend.toRemoteKey(), reverse.toRemoteKey())
        assertNotEquals(friend.toRemoteKey(), follower.toRemoteKey())
    }

    @Test
    fun `prefix includes source profile and relationship type`() {
        val profileId1 = UUID.random()
        val first = ProfileRelationshipCacheKeySerializer.toLocalKey(
            "relationships",
            ProfileRelationshipCacheKeyId(profileId1, UUID.random(), "friend"),
        )
        val second = ProfileRelationshipCacheKeySerializer.toLocalKey(
            "relationships",
            ProfileRelationshipCacheKeyId(profileId1, UUID.random(), "friend"),
        )
        val follower = ProfileRelationshipCacheKeySerializer.toLocalKey(
            "relationships",
            ProfileRelationshipCacheKeyId(profileId1, UUID.random(), "follower"),
        )

        assertTrue(first.toRemoteKey().startsWith(first.toRemoteKeyPrefix()))
        assertTrue(second.toRemoteKey().startsWith(first.toRemoteKeyPrefix()))
        assertFalse(follower.toRemoteKey().startsWith(first.toRemoteKeyPrefix()))
    }
}
