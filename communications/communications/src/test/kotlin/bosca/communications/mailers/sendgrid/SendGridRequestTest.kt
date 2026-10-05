package bosca.communications.mailers.sendgrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SendGridRequestTest {

    private val from = SendGridEmail("Sender", "sender@test.com")
    private val content = listOf(SendGridContent("text/plain", "Hello"))
    private val personalization = listOf(
        Personalization("Subject", listOf(SendGridEmail("Recipient", "rcpt@test.com")))
    )

    @Test
    fun fieldPreservation() {
        val request = SendGridRequest(
            from = from,
            subject = "Test Subject",
            content = content,
            personalization = personalization
        )
        assertEquals(from, request.from)
        assertEquals("Test Subject", request.subject)
        assertEquals(content, request.content)
        assertEquals(personalization, request.personalization)
    }

    @Test
    fun equality() {
        val a = SendGridRequest(from, "subj", content, personalization)
        val b = SendGridRequest(from, "subj", content, personalization)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequality() {
        val a = SendGridRequest(from, "subj1", content, personalization)
        val b = SendGridRequest(from, "subj2", content, personalization)
        assertNotEquals(a, b)
    }

    @Test
    fun emptyContentList() {
        val request = SendGridRequest(from, "subj", emptyList(), personalization)
        assertTrue(request.content.isEmpty())
    }

    @Test
    fun multiplePersonalizations() {
        val p1 = Personalization("Sub1", listOf(SendGridEmail("A", "a@test.com")))
        val p2 = Personalization("Sub2", listOf(SendGridEmail("B", "b@test.com")))
        val request = SendGridRequest(from, "subj", content, listOf(p1, p2))
        assertEquals(2, request.personalization.size)
    }

    @Test
    fun copy() {
        val original = SendGridRequest(from, "original", content, personalization)
        val copied = original.copy(subject = "copied")
        assertEquals("copied", copied.subject)
        assertEquals(from, copied.from)
    }

    @Test
    fun `inline images map to content_id inline attachments`() {
        val request = SendGridMessage(
            from = from,
            to = listOf(SendGridEmail("Ada", "ada@test.com")),
            subject = "Hi",
            content = content,
            inlineImages = listOf(
                bosca.communications.mailers.InlineImage(
                    cid = "logo-png",
                    mediaType = "image/png",
                    filename = "logo.png",
                    contentBase64 = "aW1n",
                ),
            ),
        ).toRequest()

        val attachment = request.attachments?.single()
        assertEquals("aW1n", attachment?.content)
        assertEquals("image/png", attachment?.type)
        assertEquals("logo.png", attachment?.filename)
        assertEquals("inline", attachment?.disposition)
        assertEquals("logo-png", attachment?.contentId)

        val json = kotlinx.serialization.json.Json.encodeToString(SendGridRequest.serializer(), request)
        assertTrue(""""content_id":"logo-png"""" in json, json)
        assertTrue(""""disposition":"inline"""" in json, json)
    }

    @Test
    fun `provider engagement tracking is disabled on every send — first-party owns clicks and opens`() {
        val request = SendGridMessage(from, listOf(SendGridEmail("A", "a@test.com")), "s", content).toRequest()
        for (json in listOf(kotlinx.serialization.json.Json, kotlinx.serialization.json.Json { encodeDefaults = true })) {
            // A request-level tracking_settings overrides account defaults, so it must ALWAYS
            // be on the wire — otherwise an account with tracking enabled would wrap our
            // first-party /c/<token> links in a sendgrid.net hop and double-count opens.
            val encoded = json.encodeToString(SendGridRequest.serializer(), request)
            assertTrue(""""click_tracking":{"enable":false,"enable_text":false}""" in encoded, encoded)
            assertTrue(""""open_tracking":{"enable":false}""" in encoded, encoded)
            assertTrue(""""subscription_tracking":{"enable":false}""" in encoded, encoded)
        }
    }

    @Test
    fun `no images means no attachments field on the wire — even with encodeDefaults`() {
        val request = SendGridMessage(from, listOf(SendGridEmail("A", "a@test.com")), "s", content).toRequest()
        for (json in listOf(kotlinx.serialization.json.Json, kotlinx.serialization.json.Json { encodeDefaults = true })) {
            // SendGrid rejects a null/empty attachments array — the field must be ABSENT.
            val encoded = json.encodeToString(SendGridRequest.serializer(), request)
            assertTrue("attachments" !in encoded, encoded)
        }
    }

    @Test
    fun `message and recipient correlation ids are emitted as per-personalization custom args`() {
        val request = SendGridMessage(
            from = from,
            to = listOf(
                SendGridEmail("Ada", "ada@test.com", mapOf("bosca_recipient_id" to "recipient-1")),
                SendGridEmail("Grace", "grace@test.com", mapOf("bosca_recipient_id" to "recipient-2")),
            ),
            subject = "Hi",
            content = content,
            customArguments = mapOf("bosca_message_id" to "message-1"),
        ).toRequest()

        assertEquals(2, request.personalization.size)
        assertEquals(
            mapOf("bosca_message_id" to "message-1", "bosca_recipient_id" to "recipient-1"),
            request.personalization[0].customArguments,
        )
        assertEquals(
            mapOf("bosca_message_id" to "message-1", "bosca_recipient_id" to "recipient-2"),
            request.personalization[1].customArguments,
        )

        val encoded = kotlinx.serialization.json.Json.encodeToString(SendGridRequest.serializer(), request)
        assertTrue(""""custom_args":{"bosca_message_id":"message-1","bosca_recipient_id":"recipient-1"}""" in encoded, encoded)
        assertTrue("customArguments" !in encoded, encoded)
    }
}
