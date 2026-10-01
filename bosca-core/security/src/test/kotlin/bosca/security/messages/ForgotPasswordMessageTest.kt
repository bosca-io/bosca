package bosca.security.messages

import bosca.communications.model.Message
import bosca.communications.service.MessageService
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class ForgotPasswordMessageTest {

    private class FakeMessageService : MessageService {
        var lastMessage: Message? = null

        override suspend fun send(message: Message) {
            lastMessage = message
        }

        override suspend fun sendNow(message: Message) {
            lastMessage = message
        }
    }

    private class FakeForgotPasswordTemplate : EmailForgotPasswordTemplate {
        var initializedProfileId: UUID? = null

        override suspend fun initialize(profileId: UUID) {
            initializedProfileId = profileId
        }

        override suspend fun getSubject(): String = "Forgot Password"
        override suspend fun getHtml(): String = "<h1>Reset your password</h1>"
        override suspend fun getText(): String = "Reset your password"
    }

    @Test
    fun `ForgotPasswordMessage extends AbstractMessage`() {
        val message = ForgotPasswordMessage(FakeMessageService(), FakeForgotPasswordTemplate())
        assertIs<AbstractMessage<EmailForgotPasswordTemplate>>(message)
    }

    @Test
    fun `send initializes template and dispatches message`() = runBlocking {
        val messageService = FakeMessageService()
        val template = FakeForgotPasswordTemplate()
        val forgotMessage = ForgotPasswordMessage(messageService, template)
        val profileId = UUID.random()

        forgotMessage.send(profileId)

        kotlin.test.assertEquals(profileId, template.initializedProfileId)
        kotlin.test.assertNotNull(messageService.lastMessage)
        kotlin.test.assertEquals("Forgot Password", messageService.lastMessage!!.subject)
        kotlin.test.assertEquals(listOf(profileId), messageService.lastMessage!!.recipients)
        kotlin.test.assertEquals(2, messageService.lastMessage!!.content.size)
    }
}
