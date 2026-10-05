package bosca.security.configuration

import bosca.profile.profile.service.ProfileService
import bosca.security.encryption.PasswordEncoderImpl
import bosca.security.encryption.ScryptPasswordEncoderImpl
import bosca.security.installer.InitialInstaller
import bosca.security.service.SecurityConfigurationImpl
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConfigurationTest {

    private val configuration = Configuration()

    @Test
    fun `provides password encoders and security configuration`() {
        assertIs<PasswordEncoderImpl>(configuration.argon2PasswordEncoder())
        assertIs<ScryptPasswordEncoderImpl>(configuration.scryptPasswordEncoder(application()))
        assertIs<SecurityConfigurationImpl>(configuration.securityConfiguration(application()))
    }

    @Test
    fun `provides the security installer`() {
        assertIs<InitialInstaller>(
            configuration.initialInstaller(
                application(),
                mockk<SecurityService>(),
                mockk<ProfileService>(),
            ),
        )
    }

    @Test
    fun `declares the initial security installation`() {
        val installation = configuration.securityInitialPackage()

        assertEquals("security-initial", installation.key)
        assertEquals("Security Initial Installer", installation.name)
        assertEquals("1.0.0", installation.versions.single().version)
        assertEquals(listOf("security-initial"), installation.versions.single().installerNames)
    }

    private fun application(): BoscaApplication {
        val config = ApplicationConfig.load(
            """
            jwt:
              secret: test-secret
              issuer: issuer
              audience: audience
              domain: localhost
              admin-domain: localhost
              realm: test
              expiration-time: "3600"
            security:
              scrypt:
                base64SaltSeparator: c2FsdA==
                base64SignerKey: c2lnbmVy
            """.trimIndent().byteInputStream(),
        )
        return BoscaApplication(config)
    }
}
