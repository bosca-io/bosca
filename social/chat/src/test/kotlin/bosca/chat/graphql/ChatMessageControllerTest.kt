package bosca.chat.graphql

import bosca.chat.model.ChatMessage
import bosca.chat.model.MessageReaction
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatMessageControllerTest {

    private val profileService = mockk<ProfileService>()
    private val controller = ChatMessageController(profileService)

    private val senderId = UUID.random()
    private val timestamp = OffsetDateTime.now()
    private val message = ChatMessage(
        sequence = 1L,
        timestamp = timestamp,
        senderId = senderId,
        content = listOf(MessageContent(MessageContentType.TEXT, "hi")),
        parentSequence = 5L,
        reactions = listOf(MessageReaction("👍", senderId)),
    )

    @Test
    fun `sequence is exposed`() {
        assertEquals(1L, controller.sequence(message))
    }

    @Test
    fun `parentSequence is exposed`() {
        assertEquals(5L, controller.parentSequence(message))
    }

    @Test
    fun `timestamp is exposed`() {
        assertEquals(timestamp, controller.timestamp(message))
    }

    @Test
    fun `senderId is exposed`() {
        assertEquals(senderId, controller.senderId(message))
    }

    @Test
    fun `content is exposed`() {
        assertEquals(message.content, controller.content(message))
    }

    @Test
    fun `attributes are exposed when null`() {
        assertNull(controller.attributes(message))
    }

    @Test
    fun `reactions are exposed`() {
        assertEquals(message.reactions, controller.reactions(message))
    }

    @Test
    fun `deleted state is exposed`() {
        assertEquals(false, controller.deleted(message))
        assertEquals(true, controller.deleted(message.copy(deleted = true)))
    }

    @Test
    fun `sender resolves through profile service`() = runTest {
        val profile = Profile(
            id = senderId,
            type = ProfileType.GENERIC,
            name = "Sender",
            visibility = ProfileVisibility.USER,
        )
        coEvery { profileService.getById(senderId) } returns profile
        assertEquals(profile, controller.sender(message))
    }
}
