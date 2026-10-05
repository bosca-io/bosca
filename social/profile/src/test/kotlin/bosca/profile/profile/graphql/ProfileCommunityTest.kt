package bosca.profile.profile.graphql

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProfileCommunityTest {

    private fun createProfile(
        id: UUID = UUID.random(),
        name: String = "Test User",
        visibility: ProfileVisibility = ProfileVisibility.PUBLIC
    ): Profile {
        return Profile(
            id = id,
            type = ProfileType.GENERIC,
            name = name,
            visibility = visibility
        )
    }

    @Test
    fun `ProfileCommunity wraps profile correctly`() {
        val profile = createProfile()
        val community = ProfileCommunity(profile = profile)
        assertEquals(profile, community.profile)
    }

    @Test
    fun `ProfileCommunity profile fields are accessible`() {
        val id = UUID.random()
        val profile = createProfile(id = id, name = "Community User")
        val community = ProfileCommunity(profile = profile)
        assertEquals(id, community.profile.id)
        assertEquals("Community User", community.profile.name)
    }

    @Test
    fun `ProfileCommunity with different profile visibilities`() {
        ProfileVisibility.entries.forEach { visibility ->
            val profile = createProfile(visibility = visibility)
            val community = ProfileCommunity(profile = profile)
            assertEquals(visibility, community.profile.visibility)
        }
    }

    @Test
    fun `ProfileCommunity with different profile types`() {
        ProfileType.entries.forEach { type ->
            val profile = Profile(
                type = type,
                name = "Test",
                visibility = ProfileVisibility.PUBLIC
            )
            val community = ProfileCommunity(profile = profile)
            assertEquals(type, community.profile.type)
        }
    }

    @Test
    fun `ProfileCommunity profile is not null`() {
        val community = ProfileCommunity(profile = createProfile())
        assertNotNull(community.profile)
    }

    @Test
    fun `ProfileCommunity preserves profile identity`() {
        val profile = createProfile()
        val community = ProfileCommunity(profile = profile)
        assertEquals(profile.id, community.profile.id)
        assertEquals(profile.name, community.profile.name)
        assertEquals(profile.type, community.profile.type)
        assertEquals(profile.visibility, community.profile.visibility)
    }
}
