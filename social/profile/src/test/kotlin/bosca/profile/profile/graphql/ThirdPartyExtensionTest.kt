package bosca.profile.profile.graphql

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ThirdPartyExtensionTest {

    @Test
    fun `ThirdPartyExtension stores profileId correctly`() {
        val profileId = UUID.random()
        val extension = ThirdPartyExtension(profileId = profileId)

        assertEquals(profileId, extension.profileId)
    }

    @Test
    fun `ThirdPartyExtension preserves different profile ids`() {
        val id1 = UUID.random()
        val id2 = UUID.random()

        val ext1 = ThirdPartyExtension(profileId = id1)
        val ext2 = ThirdPartyExtension(profileId = id2)

        assertEquals(id1, ext1.profileId)
        assertEquals(id2, ext2.profileId)
    }

    @Test
    fun `ThirdPartyExtension is not null when created`() {
        val extension = ThirdPartyExtension(profileId = UUID.random())

        assertNotNull(extension)
    }

    @Test
    fun `ThirdPartyExtensionMutation can be instantiated`() {
        val mutation = ThirdPartyExtensionMutation()

        assertNotNull(mutation)
    }
}
