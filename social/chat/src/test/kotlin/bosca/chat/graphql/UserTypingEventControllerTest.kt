package bosca.chat.graphql

import bosca.chat.model.UserTypingEvent
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UserTypingEventControllerTest {

    private val profileService = mockk<ProfileService>()
    private val controller = UserTypingEventController(profileService)

    @Test
    fun `resolves channelId`() {
        val channelId = UUID.random()
        val event = UserTypingEvent(channelId, UUID.random(), true)
        assertEquals(channelId, controller.channelId(event))
    }

    @Test
    fun `resolves profileId`() {
        val profileId = UUID.random()
        val event = UserTypingEvent(UUID.random(), profileId, true)
        assertEquals(profileId, controller.profileId(event))
    }

    @Test
    fun `resolves profile through profile service`() = runTest {
        val profileId = UUID.random()
        val event = UserTypingEvent(UUID.random(), profileId, true)
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            name = "Typing User",
            visibility = ProfileVisibility.USER,
        )
        coEvery { profileService.getById(profileId) } returns profile

        assertEquals(profile, controller.profile(event))
    }

    @Test
    fun `resolves isTyping`() {
        val event = UserTypingEvent(UUID.random(), UUID.random(), true)
        assertEquals(true, controller.isTyping(event))
    }
}
