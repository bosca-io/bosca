package bosca.profile.guide.graphql

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileGuidesControllerTest {

    private val controller = ProfileGuidesController()

    private fun createProfile(): Profile {
        return Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            name = "Test",
            visibility = ProfileVisibility.PUBLIC
        )
    }

    @Test
    fun `progressions returns ProfileGuideProgressions wrapping the same profile`() {
        val profile = createProfile()
        val guides = ProfileGuides(profile)

        val result = controller.progressions(guides)

        assertEquals(profile, result.profile)
    }

    @Test
    fun `history returns ProfileGuideHistories wrapping the same profile`() {
        val profile = createProfile()
        val guides = ProfileGuides(profile)

        val result = controller.history(guides)

        assertEquals(profile, result.profile)
    }

    @Test
    fun `ProfileGuides preserves profile identity`() {
        val profile = createProfile()
        val guides = ProfileGuides(profile)

        assertEquals(profile.id, guides.profile.id)
        assertEquals(profile.name, guides.profile.name)
    }
}
