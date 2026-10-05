package bosca.communications.mailers.sendgrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies [SendGridMessage.toRequest] conversion logic and the
 * structural correctness of the supporting data classes used to
 * build SendGrid API payloads.
 */
class SendGridMessageTest {

    private val from = SendGridEmail(name = "Sender", email = "sender@example.com")
    private val toList = listOf(
        SendGridEmail(name = "Recipient A", email = "a@example.com"),
        SendGridEmail(name = "Recipient B", email = "b@example.com")
    )
    private val subject = "Test Subject"

    /**
     * The generated request must carry the same sender and subject
     * as the original message without transformation.
     */
    @Test
    fun toRequest_preservesFromAndSubject() {
        val message = SendGridMessage(
            from = from,
            to = toList,
            subject = subject,
            content = listOf(SendGridContent(type = "text/plain", content = "Hello"))
        )
        val request = message.toRequest()
        assertEquals(from, request.from)
        assertEquals(subject, request.subject)
    }

    /**
     * Content entries must be sorted in descending order by type string
     * so that richer formats (e.g. "text/plain") appear before "text/html"
     * alphabetically descending in the SendGrid payload.
     */
    @Test
    fun toRequest_sortsContentDescendingByType() {
        val plain = SendGridContent(type = "text/plain", content = "Hello")
        val html = SendGridContent(type = "text/html", content = "<p>Hello</p>")
        val message = SendGridMessage(
            from = from,
            to = toList,
            subject = subject,
            content = listOf(plain, html)
        )
        val request = message.toRequest()
        assertEquals(2, request.content.size)
        assertEquals("text/plain", request.content[0].type)
        assertEquals("text/html", request.content[1].type)
    }

    /**
     * Each recipient gets a distinct personalization so provider callback
     * arguments can identify the exact profile that received the message.
     */
    @Test
    fun toRequest_createsOnePersonalizationPerRecipient() {
        val message = SendGridMessage(
            from = from,
            to = toList,
            subject = subject,
            content = listOf(SendGridContent(type = "text/plain", content = "Body"))
        )
        val request = message.toRequest()
        assertEquals(2, request.personalization.size)
        val personalization = request.personalization[0]
        assertEquals(subject, personalization.subject)
        assertEquals(listOf(toList[0]), personalization.to)
        assertEquals(listOf(toList[1]), request.personalization[1].to)
    }

    @Test
    fun `toRequest rejects a provider request above the personalization limit`() {
        val message = SendGridMessage(
            from = from,
            to = (1..1001).map { SendGridEmail(name = "Recipient $it", email = "$it@example.com") },
            subject = subject,
            content = listOf(SendGridContent(type = "text/plain", content = "Body")),
        )

        val error = assertFailsWith<IllegalArgumentException> { message.toRequest() }

        assertEquals("SendGrid accepts between 1 and 1000 personalizations per request", error.message)
    }

    @Test
    fun `toRequest rejects a provider request without recipients`() {
        val message = SendGridMessage(
            from = from,
            to = emptyList(),
            subject = subject,
            content = listOf(SendGridContent(type = "text/plain", content = "Body")),
        )

        val error = assertFailsWith<IllegalArgumentException> { message.toRequest() }

        assertEquals("SendGrid accepts between 1 and 1000 personalizations per request", error.message)
    }

    /**
     * [SendGridEmail] structural equality must hold for instances
     * with identical name and email values.
     */
    @Test
    fun sendGridEmail_equalityForIdenticalInstances() {
        val a = SendGridEmail(name = "Test", email = "test@example.com")
        val b = SendGridEmail(name = "Test", email = "test@example.com")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /**
     * [SendGridContent] structural equality must hold for instances
     * with identical type and content values.
     */
    @Test
    fun sendGridContent_equalityForIdenticalInstances() {
        val a = SendGridContent(type = "text/plain", content = "Hello")
        val b = SendGridContent(type = "text/plain", content = "Hello")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /**
     * [Personalization] structural equality must hold for instances
     * with identical subject and recipient list values.
     */
    @Test
    fun personalization_equalityForIdenticalInstances() {
        val a = Personalization(subject = "Sub", to = toList)
        val b = Personalization(subject = "Sub", to = toList)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /**
     * [SendGridRequest] structural equality must hold for instances
     * with identical from, subject, content, and personalization values.
     */
    @Test
    fun sendGridRequest_equalityForIdenticalInstances() {
        val content = listOf(SendGridContent(type = "text/plain", content = "Body"))
        val personalizations = listOf(Personalization(subject = "Sub", to = toList))
        val a = SendGridRequest(from = from, subject = "Sub", content = content, personalization = personalizations)
        val b = SendGridRequest(from = from, subject = "Sub", content = content, personalization = personalizations)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /**
     * When content contains entries with the same type string,
     * the sort should be stable and preserve their original relative order.
     */
    @Test
    fun toRequest_stableSortForIdenticalTypes() {
        val first = SendGridContent(type = "text/plain", content = "First")
        val second = SendGridContent(type = "text/plain", content = "Second")
        val message = SendGridMessage(
            from = from,
            to = toList,
            subject = subject,
            content = listOf(first, second)
        )
        val request = message.toRequest()
        assertEquals("First", request.content[0].content)
        assertEquals("Second", request.content[1].content)
    }
}
