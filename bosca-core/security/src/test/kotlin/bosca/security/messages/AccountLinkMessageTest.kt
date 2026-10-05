package bosca.security.messages

import bosca.communications.model.Message
import bosca.communications.model.MessageContentType
import bosca.communications.service.MessageService
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountLinkMessageTest {

    private class FakeMessageService : MessageService {
        var lastMessage: Message? = null
        override suspend fun send(message: Message) { lastMessage = message }
        override suspend fun sendNow(message: Message) { lastMessage = message }
    }

    @Test
    fun `send dispatches an email carrying the confirm link in both HTML and text`() = runBlocking {
        val messageService = FakeMessageService()
        val confirmUrl = "https://app/auth/link/confirm?proof=tok-123"
        val profileId = UUID.random()

        AccountLinkMessage(messageService, DefaultAccountLinkTemplate(confirmUrl)).send(profileId)

        val sent = messageService.lastMessage
        assertNotNull(sent)
        assertEquals("Confirm a new sign-in method for your account", sent!!.subject)
        assertEquals(listOf(profileId), sent.recipients)
        assertEquals(2, sent.content.size)
        val html = sent.content.first { it.type == MessageContentType.HTML }.content
        val text = sent.content.first { it.type == MessageContentType.TEXT }.content
        assertTrue(html.contains("href=\"$confirmUrl\""), "HTML should link to the confirm URL")
        assertTrue(text.contains(confirmUrl), "TEXT should include the confirm URL")
    }
}
