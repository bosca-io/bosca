package bosca.chat.graphql

import bosca.chat.model.ChatChannelMember
import bosca.chat.service.ChatService
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
import kotlin.test.assertNull

class ChatChannelMemberControllerTest {

    private val profileService = mockk<ProfileService>()
    private val chatService = mockk<ChatService>()
    private val controller = ChatChannelMemberController(profileService, chatService)

    private val channelId = UUID.random()
    private val profileId = UUID.random()

    private val member = ChatChannelMember(
        channelId = channelId,
        profileId = profileId,
        role = "admin",
    )

    @Test
    fun `channelId returns the member channel ID`() {
        assertEquals(channelId, controller.channelId(member))
    }

    @Test
    fun `profileId returns the member profile ID`() {
        assertEquals(profileId, controller.profileId(member))
    }

    @Test
    fun `role returns the member role`() {
        assertEquals("admin", controller.role(member))
    }

    @Test
    fun `lastReadSequence pulls from the read-state KV via ChatService`() = runTest {
        coEvery { chatService.getLastRead(channelId, profileId) } returns 42L
        assertEquals(42L, controller.lastReadSequence(member))
    }

    @Test
    fun `lastReadSequence returns null when the user has never recorded a read`() = runTest {
        coEvery { chatService.getLastRead(channelId, profileId) } returns null
        assertNull(controller.lastReadSequence(member))
    }

    @Test
    fun `attributes returns null when not set`() {
        assertNull(controller.attributes(member))
    }

    @Test
    fun `profile resolves through profile service`() = runTest {
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            name = "Test User",
            visibility = ProfileVisibility.USER,
        )
        coEvery { profileService.getById(profileId) } returns profile
        val result = controller.profile(member)
        assertEquals(profile, result)
    }
}
