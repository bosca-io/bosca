package bosca.chat.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelType
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.graphql.ProfileChat
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileChatControllerTest {

    private val chatService = mockk<ChatService>(relaxed = true)
    private val chatChannelPermissionEvaluator = mockk<ChatChannelPermissionEvaluator>(relaxed = true)
    private val chatObjectPermissionEvaluator = mockk<ChatObjectPermissionEvaluator>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)

    private val controller = ProfileChatController(
        chatService = chatService,
        chatChannelPermissionEvaluator = chatChannelPermissionEvaluator,
        chatObjectPermissionEvaluator = chatObjectPermissionEvaluator,
        groupEvaluator = groupEvaluator,
    )

    private fun profileChat(profileId: UUID = UUID.random()): ProfileChat {
        val profile = Profile(
            id = profileId,
            name = "Alice",
            visibility = ProfileVisibility.SYSTEM,
            type = ProfileType.GENERIC,
        )
        return ProfileChat(profile)
    }

    @Test
    fun `channels returns channels the profile is a member of`() = runTest {
        val profileId = UUID.random()
        val chat = profileChat(profileId)
        val auth = mockk<AuthenticationContext>()
        val channel = ChatChannel(id = UUID.random(), name = "general", type = ChatChannelType.GROUP)
        coEvery { chatService.getChannels(profileId) } returns listOf(channel)
        coEvery {
            chatChannelPermissionEvaluator.filterAllowed(auth, listOf(channel), PermissionAction.VIEW)
        } returns listOf(channel)
        coEvery { chatObjectPermissionEvaluator.isAllowed(auth, channel) } returns true

        val result = controller.channels(auth, chat)

        assertEquals(1, result.size)
        assertEquals("general", result[0].name)
        coVerify { chatService.getChannels(profileId) }
    }

    @Test
    fun `channels returns empty list when profile has no memberships`() = runTest {
        val chat = profileChat()
        val auth = mockk<AuthenticationContext>()
        coEvery { chatService.getChannels(chat.profile.id) } returns emptyList()
        coEvery {
            chatChannelPermissionEvaluator.filterAllowed(auth, emptyList(), PermissionAction.VIEW)
        } returns emptyList()

        val result = controller.channels(auth, chat)

        assertTrue(result.isEmpty())
    }
}
