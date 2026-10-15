package bosca.communications.mailers.smtp

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmtpConfigurationTest {
    @Test
    fun `omitted SMTP options use submission defaults and explicit values round trip`() {
        val defaults = Json.decodeFromString(SmtpConfiguration.serializer(), """{"host":"smtp.example.com"}""")
        assertEquals(SmtpConfiguration("smtp.example.com", 587, SmtpSecurity.STARTTLS, "", ""), defaults)
        val explicit = SmtpConfiguration("relay.example.com", 25, SmtpSecurity.NONE, "user", " secret ")
        assertEquals(
            explicit,
            Json.decodeFromString(SmtpConfiguration.serializer(), Json.encodeToString(SmtpConfiguration.serializer(), explicit)),
        )
    }

    @Test
    fun `rejects incomplete connection settings`() {
        val valid = SmtpConfiguration("smtp.example.com")
        listOf(
            valid.copy(host = " "), valid.copy(host = "smtp example.com"),
            valid.copy(port = 0), valid.copy(port = 65536),
            valid.copy(username = "user"), valid.copy(password = "secret"),
        ).forEach { assertFailsWith<IllegalArgumentException> { it.properties() } }
    }

    @Test
    fun `STARTTLS is required and certificate identity is verified`() {
        val properties = SmtpConfiguration("smtp.example.com").properties()
        assertEquals("true", properties.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertEquals("false", properties.getProperty("mail.smtp.ssl.enable"))
    }
}
