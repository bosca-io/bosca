package bosca.communications.mailers

import bosca.communications.configuration.Configuration
import bosca.communications.mailers.mailgun.MailgunMailer
import bosca.communications.mailers.sendgrid.SendGridMailer
import bosca.communications.mailers.smtp.SmtpMailer
import bosca.configuration.service.ConfigurationService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class MailerProviderTest {
    private val configurationService = mockk<ConfigurationService>()
    private val id = UUID.random()
    private val provider = Configuration().mailerProvider(
        Json, MailerConfiguration(MailerType.MAILGUN, MailerEmail("Bosca", "mail@example.com")), configurationService,
    )

    @Test
    fun `stored selection changes without rebuilding the provider and null uses deployment choice`() = runBlocking {
        coEvery { configurationService.getByKey("mailer") } returns null
        val deployment = provider.get()
        assertIs<MailgunMailer>(deployment)
        coEvery { configurationService.getByKey("mailer") } returns bosca.configuration.model.Configuration(
            id, "mailer", "Email provider", false,
        )
        select("sendgrid")
        assertIs<SendGridMailer>(provider.get())
        select("smtp")
        assertIs<SmtpMailer>(provider.get())
        select("mailgun")
        assertSame(deployment, provider.get())
        coEvery { configurationService.getValue(id) } returns Json.parseToJsonElement("{\"type\":null}")
        assertSame(deployment, provider.get())
        coEvery { configurationService.getValue(id) } returns null
        assertSame(deployment, provider.get())
    }

    @Test
    fun `invalid selection and configuration failures propagate`() = runBlocking<Unit> {
        coEvery { configurationService.getByKey("mailer") } returns bosca.configuration.model.Configuration(
            id, "mailer", "Email provider", false,
        )
        select("unknown")
        assertFailsWith<SerializationException> { provider.get() }
        coEvery { configurationService.getValue(id) } throws IllegalStateException("unavailable")
        assertFailsWith<IllegalStateException> { provider.get() }
        coEvery { configurationService.getValue(id) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { provider.get() }
    }

    private fun select(type: String) {
        coEvery { configurationService.getValue(id) } returns Json.parseToJsonElement("{\"type\":\"$type\"}")
    }
}
