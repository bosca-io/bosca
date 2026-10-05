package bosca.chat.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatObjectType
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatService
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

class ChatQueryControllerTest {

    private val chatService = mockk<ChatService>(relaxed = true)
    private val chatChannelPermissionEvaluator = mockk<ChatChannelPermissionEvaluator>(relaxed = true)
    private val chatObjectPermissionEvaluator = mockk<ChatObjectPermissionEvaluator>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)

    private val controller = ChatQueryController(
        chatService = chatService,
        chatChannelPermissionEvaluator = chatChannelPermissionEvaluator,
        chatObjectPermissionEvaluator = chatObjectPermissionEvaluator,
        groupEvaluator = groupEvaluator,
    )

    @Test
    fun `channel returns null for nonexistent channel`() = runTest {
        val auth = mockk<AuthenticationContext>()
        val id = UUID.random()
        coEvery { chatService.getById(id) } returns null

        val result = controller.channel(auth, id)

        assertEquals(null, result)
    }

    @Test
    fun `channel returns channel when it exists and user has permission`() = runTest {
        val auth = mockk<AuthenticationContext>()
        val channel = ChatChannel(id = UUID.random(), name = "test", type = ChatChannelType.PUBLIC)
        coEvery { chatService.getById(channel.id) } returns channel

        val result = controller.channel(auth, channel.id)

        assertEquals(channel, result)
        coVerify { chatChannelPermissionEvaluator.verifyAllowed(auth, channel, PermissionAction.VIEW) }
    }

    @Test
    fun `objectChannel returns null when no channel for object`() = runTest {
        val auth = mockk<AuthenticationContext>()
        val objectId = UUID.random()
        coEvery { chatService.getByObject(ChatObjectType.METADATA, objectId) } returns null

        val result = controller.objectChannel(auth, ChatObjectType.METADATA, objectId)

        assertEquals(null, result)
    }
}
