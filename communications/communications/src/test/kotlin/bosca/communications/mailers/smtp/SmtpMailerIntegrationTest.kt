package bosca.communications.mailers.smtp

import bosca.communications.configuration.Configuration
import bosca.communications.mailers.ContentType
import bosca.communications.mailers.InlineImage
import bosca.communications.mailers.Mailer
import bosca.communications.mailers.MailerConfiguration
import bosca.communications.mailers.MailerEmail
import bosca.communications.mailers.MailerType
import bosca.configuration.service.ConfigurationService
import bosca.serialization.UUID
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.AfterClass
import org.junit.BeforeClass
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the configured mailer over a real local SMTP socket, including MIME and authentication. */
class SmtpMailerIntegrationTest {
    companion object {
        private lateinit var keyStore: KeyStore
        private lateinit var keyStorePath: Path
        private var previousKeyStore: String? = null

        @BeforeClass
        @JvmStatic
        fun configureTestCertificate() {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=localhost")
            val certificate = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(
                    name, BigInteger.ONE, Date(System.currentTimeMillis() - 60_000),
                    Date(System.currentTimeMillis() + 3_600_000), name, keys.public,
                ).addExtension(
                    Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, "localhost")),
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)),
            )
            keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("smtp-test", keys.private, "changeit".toCharArray(), arrayOf(certificate))
            }
            keyStorePath = Files.createTempFile("bosca-smtp-test-", ".p12")
            Files.newOutputStream(keyStorePath).use { keyStore.store(it, "changeit".toCharArray()) }
            previousKeyStore = System.setProperty("greenmail.tls.keystore.file", keyStorePath.toString())
        }

        @AfterClass
        @JvmStatic
        fun restoreTestCertificate() {
            previousKeyStore?.let { System.setProperty("greenmail.tls.keystore.file", it) }
                ?: System.clearProperty("greenmail.tls.keystore.file")
            Files.deleteIfExists(keyStorePath)
        }
    }

    private val configurationService = mockk<ConfigurationService>()
    private val id = UUID.random()

    init {
        coEvery { configurationService.getByKey("mailer") } returns null
        coEvery { configurationService.getByKey("smtp") } returns bosca.configuration.model.Configuration(
            id, "smtp", "SMTP", false,
        )
    }

    @Test
    fun `configured SMTP sends unicode alternatives and inline images privately without authentication`() = runBlocking {
        withServer { server ->
            server.setUser("ada@example.com", "ada", "secret")
            server.setUser("grace@example.com", "grace", "secret")
            configure(server)
            val mailer = Configuration().mailerProvider(
                Json, MailerConfiguration(MailerType.SMTP, MailerEmail("Bosca", "mail@example.com")), configurationService,
            ).get()
            mailer.send(message(mailer, listOf("ada@example.com")))
            mailer.send(message(mailer, listOf("grace@example.com")))

            assertTrue(server.waitForIncomingEmail(5000, 2))
            val messages = server.receivedMessages
            assertEquals(2, messages.size)
            assertEquals(setOf("ada@example.com", "grace@example.com"), messages.map {
                (it.getRecipients(Message.RecipientType.TO).single() as InternetAddress).address
            }.toSet())
            messages.forEach { received ->
                assertEquals("Héllo 世界", received.subject)
                assertEquals("Bósca", (received.from.single() as InternetAddress).personal)
                assertEquals(1, received.allRecipients.size)
                assertTrue(received.isMimeType("multipart/related"))
                val related = received.content as Multipart
                val alternatives = related.getBodyPart(0).content as Multipart
                assertTrue(alternatives.getBodyPart(0).isMimeType("text/plain"))
                assertEquals("Héllo 世界", alternatives.getBodyPart(0).content)
                assertTrue(alternatives.getBodyPart(1).isMimeType("text/html"))
                assertEquals("<p>Héllo 世界<img src=\"cid:logo\"></p>", alternatives.getBodyPart(1).content)
                val image = related.getBodyPart(1)
                assertEquals("<logo>", image.getHeader("Content-ID").single())
                assertEquals("inline", image.disposition)
                assertEquals("logo.png", image.fileName)
                assertEquals("image bytes", image.inputStream.readBytes().decodeToString())
            }
        }
    }

    @Test
    fun `authentication credentials and server address are read for every send`() = runBlocking {
        withServer { first ->
            withServer { second ->
                first.setUser("ada@example.com", "first", " secret ")
                second.setUser("ada@example.com", "second", "new-secret")
                val mailer = SmtpMailer(Json, configurationService)
                configure(first, "first", " secret ")
                mailer.send(message(mailer))
                configure(second, "second", "new-secret")
                mailer.send(message(mailer))
                assertTrue(first.waitForIncomingEmail(5000, 1))
                assertTrue(second.waitForIncomingEmail(5000, 1))
                configure(second, "second", "wrong")
                assertFailsWith<AuthenticationFailedException> { mailer.send(message(mailer)) }
                assertEquals(1, second.receivedMessages.size)
            }
        }
    }

    @Test
    fun `TLS refuses an untrusted SMTP certificate`() = runBlocking {
        withServer(ServerSetup.PROTOCOL_SMTPS) { server ->
            configure(server, security = SmtpSecurity.TLS)
            val mailer = SmtpMailer(Json, configurationService)
            assertFailsWith<MessagingException> { mailer.send(message(mailer)) }
            assertTrue(server.receivedMessages.isEmpty())
        }
    }

    @Test
    fun `STARTTLS refuses a server without upgrade support`() = runBlocking {
        withServer { server ->
            configure(server, security = SmtpSecurity.STARTTLS)
            val mailer = SmtpMailer(Json, configurationService)
            assertFailsWith<MessagingException> { mailer.send(message(mailer)) }
            assertTrue(server.receivedMessages.isEmpty())
        }
    }

    @Test
    fun `implicit TLS delivers with a trusted certificate and verified hostname`() = runBlocking {
        val previous = SSLContext.getDefault()
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        SSLContext.setDefault(SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) })
        try {
            withServer(ServerSetup.PROTOCOL_SMTPS) { server ->
                server.setUser("ada@example.com", "ada", "secret")
                configure(server, "ada", "secret", SmtpSecurity.TLS)
                val mailer = SmtpMailer(Json, configurationService)
                val message = message(mailer) as SmtpMessage
                mailer.send(message.copy(inlineImages = emptyList(), content = listOf(SmtpContent(ContentType.TEXT, "Hello"))))
                assertTrue(server.waitForIncomingEmail(5000, 1))
                val received = server.receivedMessages.single()
                assertTrue(received.isMimeType("multipart/alternative"))
                assertEquals("Hello", (received.content as Multipart).getBodyPart(0).content)
            }
        } finally {
            SSLContext.setDefault(previous)
        }
    }

    @Test
    fun `invalid configuration and message fail before delivery`() = runBlocking {
        val mailer = SmtpMailer(Json, configurationService)
        coEvery { configurationService.getByKey("smtp") } returns null
        assertFailsWith<IllegalStateException> { mailer.send(message(mailer)) }
        coEvery { configurationService.getByKey("smtp") } returns bosca.configuration.model.Configuration(id, "smtp", "SMTP", false)
        coEvery { configurationService.getValue(id) } returns null
        assertFailsWith<IllegalStateException> { mailer.send(message(mailer)) }
        withServer { server ->
            configure(server)
            val message = message(mailer) as SmtpMessage
            assertFailsWith<IllegalArgumentException> { mailer.send(message.copy(to = emptyList())) }
            assertFailsWith<IllegalArgumentException> { mailer.send(message.copy(to = List(2) { message.to.first() })) }
            assertFailsWith<IllegalArgumentException> { mailer.send(message.copy(subject = "Hello\r\nBcc: other@example.com")) }
            assertFailsWith<IllegalArgumentException> { mailer.send(message.copy(subject = "Hello\nBcc: other@example.com")) }
            for (separator in listOf("\r", "\n")) {
                assertFailsWith<IllegalArgumentException> {
                    mailer.send(message.copy(to = listOf(SmtpEmail("Injected${separator}Name", "ada@example.com"))))
                }
                assertFailsWith<IllegalArgumentException> {
                    mailer.send(message.copy(to = listOf(SmtpEmail("Ada", "ada@example.com${separator}Bcc: other@example.com"))))
                }
            }
            assertFailsWith<IllegalArgumentException> { mailer.send(message.copy(content = emptyList())) }
            assertFailsWith<IllegalArgumentException> {
                mailer.send(message.copy(inlineImages = listOf(message.inlineImages.first().copy(cid = "logo\r\nInjected: value"))))
            }
            assertTrue(server.receivedMessages.isEmpty())
        }
    }

    private suspend fun message(mailer: Mailer, recipients: List<String> = listOf("ada@example.com")) = mailer.newMessage(
        mailer.newEmail("Bósca", "mail@example.com"),
        recipients.map { mailer.newEmail("Recipient", it) },
        "Héllo 世界",
        listOf(
            mailer.newContent(ContentType.HTML, "<p>Héllo 世界<img src=\"cid:logo\"></p>"),
            mailer.newContent(ContentType.TEXT, "Héllo 世界"),
        ),
        listOf(InlineImage("logo", "image/png", "logo.png", "aW1hZ2UgYnl0ZXM=")),
    )

    private fun configure(server: GreenMail, username: String = "", password: String = "", security: SmtpSecurity = SmtpSecurity.NONE) {
        val port = if (security == SmtpSecurity.TLS) server.smtps.port else server.smtp.port
        coEvery { configurationService.getValue(id) } returns Json.encodeToJsonElement(
            SmtpConfiguration.serializer(), SmtpConfiguration("localhost", port, security, username, password),
        )
    }

    private suspend fun withServer(protocol: String = ServerSetup.PROTOCOL_SMTP, block: suspend (GreenMail) -> Unit) {
        val server = GreenMail(ServerSetup(0, "127.0.0.1", protocol))
        server.start()
        try {
            block(server)
        } finally {
            server.stop()
        }
    }
}
