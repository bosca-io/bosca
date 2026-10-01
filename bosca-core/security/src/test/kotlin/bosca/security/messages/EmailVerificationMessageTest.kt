package bosca.security.messages

import bosca.communications.model.Message
import bosca.communications.service.MessageService
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class EmailVerificationMessageTest {

    private class FakeMessageService : MessageService {
        var lastMessage: Message? = null

        override suspend fun send(message: Message) {
            lastMessage = message
        }

        override suspend fun sendNow(message: Message) {
            lastMessage = message
        }
    }

    private class FakeEmailVerificationTemplate : EmailVerificationTemplate {
        var initializedProfileId: UUID? = null

        override suspend fun initialize(profileId: UUID) {
            initializedProfileId = profileId
        }

        override suspend fun getSubject(): String = "Verify your email"
        override suspend fun getHtml(): String = "<h1>Verify</h1>"
        override suspend fun getText(): String = "Verify your email"
    }

    @Test
    fun `EmailVerificationMessage extends AbstractMessage`() {
        val message = EmailVerificationMessage(FakeMessageService(), FakeEmailVerificationTemplate())
        assertIs<AbstractMessage<EmailVerificationTemplate>>(message)
    }

    @Test
    fun `send initializes template and dispatches message`() = runBlocking {
        val messageService = FakeMessageService()
        val template = FakeEmailVerificationTemplate()
        val emailMessage = EmailVerificationMessage(messageService, template)
        val profileId = UUID.random()

        emailMessage.send(profileId)

        kotlin.test.assertEquals(profileId, template.initializedProfileId)
        kotlin.test.assertNotNull(messageService.lastMessage)
        kotlin.test.assertEquals("Verify your email", messageService.lastMessage!!.subject)
        kotlin.test.assertEquals(listOf(profileId), messageService.lastMessage!!.recipients)
        kotlin.test.assertEquals(2, messageService.lastMessage!!.content.size)
    }
}
