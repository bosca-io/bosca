package bosca.security.messages

import bosca.communications.model.Message
import bosca.communications.service.MessageService
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class PasswordResetMessageTest {

    private class FakeMessageService : MessageService {
        var lastMessage: Message? = null

        override suspend fun send(message: Message) {
            lastMessage = message
        }

        override suspend fun sendNow(message: Message) {
            lastMessage = message
        }
    }

    private class FakePasswordResetTemplate : EmailPasswordResetTemplate {
        var initializedProfileId: UUID? = null

        override suspend fun initialize(profileId: UUID) {
            initializedProfileId = profileId
        }

        override suspend fun getSubject(): String = "Password Reset"
        override suspend fun getHtml(): String = "<h1>Your password has been reset</h1>"
        override suspend fun getText(): String = "Your password has been reset"
    }

    @Test
    fun `PasswordResetMessage extends AbstractMessage`() {
        val message = PasswordResetMessage(FakeMessageService(), FakePasswordResetTemplate())
        assertIs<AbstractMessage<EmailPasswordResetTemplate>>(message)
    }

    @Test
    fun `send initializes template and dispatches message`() = runBlocking {
        val messageService = FakeMessageService()
        val template = FakePasswordResetTemplate()
        val resetMessage = PasswordResetMessage(messageService, template)
        val profileId = UUID.random()

        resetMessage.send(profileId)

        kotlin.test.assertEquals(profileId, template.initializedProfileId)
        kotlin.test.assertNotNull(messageService.lastMessage)
        kotlin.test.assertEquals("Password Reset", messageService.lastMessage!!.subject)
        kotlin.test.assertEquals(listOf(profileId), messageService.lastMessage!!.recipients)
        kotlin.test.assertEquals(2, messageService.lastMessage!!.content.size)
    }
}
