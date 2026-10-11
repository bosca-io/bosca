package bosca.security.service

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import com.auth0.jwt.JWT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecurityConfigurationImplTest {

    @Test
    fun `loads explicit security configuration and builds a working verifier`() {
        val configuration = SecurityConfigurationImpl(
            application(
                """
                jwt:
                  secret: test-secret-with-enough-entropy
                  issuer: https://issuer.example
                  audience: bosca-test
                  domain: api.example
                  admin-domain: admin.example
                  realm: test
                  expiration-time: "7200"
                  cookie-secure: "true"
                  cookie-http-only: "true"
                  cookie-prefixes:
                    - prefix: _bat_preview
                      domains:
                        - PREVIEW.EXAMPLE
                oauth2:
                  redirects:
                    - https://app.example/callback
                  providers:
                    - type: google
                      clientId: client-id
                      clientSecret: client-secret
                      enabled: true
                      callback: https://app.example/oauth/google
                      adminCallback: https://admin.example/oauth/google
                      scopes:
                        - openid
                        - email
                      userInfoUrl: https://provider.example/userinfo
                      authorizeUrl: https://provider.example/authorize
                      accessTokenUrl: https://provider.example/token
                app:
                  url: https://app.example
                  security-alert-url: https://profiles.example/security?tab=logins
                  welcome-url: https://onboarding.example/start
                  allowed-origins:
                    - https://app.example
                    - https://admin.example
                webauthn:
                  rpName: Bosca Test
                  rpId: example
                  origins:
                    - https://app.example
                """.trimIndent(),
            ),
        )

        assertEquals("test-secret-with-enough-entropy", configuration.secret)
        assertEquals("https://issuer.example", configuration.issuer)
        assertEquals("bosca-test", configuration.audience)
        assertEquals("api.example", configuration.domain)
        assertEquals("admin.example", configuration.adminDomain)
        assertEquals("test", configuration.realm)
        assertEquals(7200L, configuration.expirationTimeInSeconds)
        assertEquals(listOf("https://app.example/callback"), configuration.allowedRedirects)
        assertEquals("https://app.example", configuration.appUrl)
        assertEquals("https://profiles.example/security?tab=logins", configuration.securityAlertUrl)
        assertEquals("https://onboarding.example/start", configuration.welcomeUrl)
        assertEquals(
            listOf("https://app.example", "https://admin.example"),
            configuration.allowedAppOrigins,
        )
        assertTrue(configuration.cookieSecure)
        assertTrue(configuration.cookieHttpOnly)
        assertEquals(
            listOf(AuthCookiePrefix("_bat_preview", listOf("preview.example"))),
            configuration.authCookiePrefixes,
        )
        assertEquals("google", configuration.oauth2.single().type)
        assertEquals(
            WebAuthnConfiguration(
                rpName = "Bosca Test",
                rpId = "example",
                origins = listOf("https://app.example"),
            ),
            configuration.webauthn,
        )

        val token = JWT.create()
            .withIssuer(configuration.issuer)
            .withAudience(configuration.audience)
            .sign(configuration.algorithm)
        assertEquals(configuration.issuer, configuration.verifier.verify(token).issuer)
    }

    @Test
    fun `adds extra WebAuthn origins without replacing the defaults`() {
        val configuration = SecurityConfigurationImpl(
            application(
                """
                jwt:
                  secret: test-secret
                  issuer: issuer
                  audience: audience
                  domain: example.org
                  admin-domain: admin.example.org
                  realm: test
                  expiration-time: 60
                webauthn:
                  extraOrigins:
                    - " https://studio.example.org/ "
                    - ""
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("https://studio.example.org"), configuration.webauthn.extraOrigins)
        assertEquals(
            listOf("https://example.org", "https://admin.example.org", "https://studio.example.org"),
            configuration.webauthn.allowedOrigins(configuration.webauthn.rpId ?: configuration.domain, configuration.adminDomain),
        )
    }

    @Test
    fun `an unset extra WebAuthn origin keeps the default origins`() {
        val configuration = SecurityConfigurationImpl(
            application(
                """
                jwt:
                  secret: test-secret
                  issuer: issuer
                  audience: audience
                  domain: example.org
                  admin-domain: admin.example.org
                  realm: test
                  expiration-time: 60
                webauthn:
                  extraOrigins:
                    - ""
                """.trimIndent(),
            ),
        )

        assertEquals(emptyList(), configuration.webauthn.extraOrigins)
        assertEquals(
            listOf("https://example.org", "https://admin.example.org"),
            configuration.webauthn.allowedOrigins(configuration.webauthn.rpId ?: configuration.domain, configuration.adminDomain),
        )
        // Explicit origins still replace the defaults; extras are appended once.
        assertEquals(
            listOf("https://app.example", "https://studio.example.org"),
            WebAuthnConfiguration(origins = listOf("https://app.example"), extraOrigins = listOf("https://studio.example.org", "https://app.example"))
                .allowedOrigins("example.org", "admin.example.org"),
        )
    }

    @Test
    fun `uses safe defaults for optional and malformed configuration`() {
        val configuration = SecurityConfigurationImpl(
            application(
                """
                jwt:
                  secret: test-secret
                  issuer: issuer
                  audience: audience
                  domain: localhost
                  admin-domain: localhost
                  realm: test
                  expiration-time: invalid
                  cookie-secure: invalid
                  cookie-http-only: invalid
                """.trimIndent(),
            ),
        )

        assertEquals(3600L, configuration.expirationTimeInSeconds)
        assertTrue(configuration.allowedRedirects.isEmpty())
        assertTrue(configuration.oauth2.isEmpty())
        assertEquals("http://localhost:3000", configuration.appUrl)
        assertEquals("http://localhost:3000", configuration.securityAlertUrl)
        assertEquals("http://localhost:3000/welcome", configuration.welcomeUrl)
        assertTrue(configuration.allowedAppOrigins.isEmpty())
        assertFalse(configuration.cookieSecure)
        assertFalse(configuration.cookieHttpOnly)
        assertTrue(configuration.authCookiePrefixes.isEmpty())
        assertEquals(WebAuthnConfiguration(rpId = "localhost"), configuration.webauthn)
    }

    @Test
    fun `blank welcome url falls back to the configured application url`() {
        val configuration = SecurityConfigurationImpl(
            application(
                """
                jwt:
                  secret: test-secret
                  issuer: issuer
                  audience: audience
                  domain: localhost
                  admin-domain: localhost
                  realm: test
                  expiration-time: "3600"
                app:
                  url: https://studio.example/
                  welcome-url: ""
                """.trimIndent(),
            ),
        )

        assertEquals("https://studio.example/welcome", configuration.welcomeUrl)
    }

    @Test
    fun `invalid cookie prefix entry fails configuration`() {
        assertFailsWith<IllegalArgumentException> {
            SecurityConfigurationImpl(
                application(
                    """
                    jwt:
                      secret: test-secret
                      issuer: issuer
                      audience: audience
                      domain: localhost
                      admin-domain: localhost
                      realm: test
                      expiration-time: "3600"
                      cookie-prefixes:
                        - prefix: _bat
                          domains:
                            - preview.example
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun `browser reserved cookie prefix fails configuration`() {
        for (prefix in listOf("__Host-preview", "__Secure-preview")) {
            assertFailsWith<IllegalArgumentException> {
                SecurityConfigurationImpl(
                    application(
                        """
                        jwt:
                          secret: test-secret
                          issuer: issuer
                          audience: audience
                          domain: localhost
                          admin-domain: localhost
                          realm: test
                          expiration-time: "3600"
                          cookie-prefixes:
                            - prefix: $prefix
                              domains:
                                - preview.example
                        """.trimIndent(),
                    ),
                )
            }
        }
    }

    @Test
    fun `one invalid cookie prefix entry fails the entire configuration`() {
        assertFailsWith<IllegalArgumentException> {
            SecurityConfigurationImpl(
                application(
                    """
                    jwt:
                      secret: test-secret
                      issuer: issuer
                      audience: audience
                      domain: localhost
                      admin-domain: localhost
                      realm: test
                      expiration-time: "3600"
                      cookie-prefixes:
                        - prefix: _bat_preview
                          domains:
                            - preview.example
                        - prefix: 42
                          domains: malformed.example
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun `a domain mapped to multiple cookie prefixes fails configuration`() {
        assertFailsWith<IllegalArgumentException> {
            SecurityConfigurationImpl(
                application(
                    """
                    jwt:
                      secret: test-secret
                      issuer: issuer
                      audience: audience
                      domain: localhost
                      admin-domain: localhost
                      realm: test
                      expiration-time: "3600"
                      cookie-prefixes:
                        - prefix: _bat_preview
                          domains:
                            - preview.example
                        - prefix: _bat_other
                          domains:
                            - preview.example
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun `duplicate cookie prefixes fail configuration`() {
        assertFailsWith<IllegalArgumentException> {
            SecurityConfigurationImpl(
                application(
                    """
                    jwt:
                      secret: test-secret
                      issuer: issuer
                      audience: audience
                      domain: localhost
                      admin-domain: localhost
                      realm: test
                      expiration-time: "3600"
                      cookie-prefixes:
                        - prefix: _bat_preview
                          domains:
                            - preview.example
                        - prefix: _bat_preview
                          domains:
                            - other.example
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun `blank security urls fall back after trimming`() {
        val configuration = SecurityConfigurationImpl(
            application(
                """
                jwt:
                  secret: test-secret
                  issuer: issuer
                  audience: audience
                  domain: localhost
                  admin-domain: localhost
                  realm: test
                  expiration-time: "3600"
                app:
                  url: https://studio.example/
                  security-alert-url: "   "
                  welcome-url: "   "
                """.trimIndent(),
            ),
        )

        assertEquals("https://studio.example/", configuration.securityAlertUrl)
        assertEquals("https://studio.example/welcome", configuration.welcomeUrl)
    }

    @Test
    fun `cookie prefixes cannot shadow default names or contain invalid domain lists`() {
        for ((prefix, domains) in listOf(
            "_bat" to "[example.com]",
            "custom" to "[]",
            "custom" to "[example.com, example.com]",
            "custom" to "[https://example.com]",
        )) {
            assertFailsWith<IllegalArgumentException> {
                SecurityConfigurationImpl(application("""
                    jwt:
                      secret: test-secret
                      issuer: issuer
                      audience: audience
                      domain: localhost
                      admin-domain: localhost
                      realm: test
                      expiration-time: "3600"
                      cookie-prefixes:
                        - prefix: $prefix
                          domains: $domains
                """.trimIndent()))
            }
        }
    }

    @Test
    fun `an explicitly empty cookie prefix list retains the default cookie policy`() {
        val configuration = SecurityConfigurationImpl(application("""
            jwt:
              secret: test-secret
              issuer: issuer
              audience: audience
              domain: localhost
              admin-domain: localhost
              realm: test
              expiration-time: "3600"
              cookie-prefixes: []
        """.trimIndent()))
        assertEquals(emptyList(), configuration.authCookiePrefixes)
    }

    private fun application(yaml: String): BoscaApplication =
        BoscaApplication(ApplicationConfig.load(yaml.byteInputStream()))
}
