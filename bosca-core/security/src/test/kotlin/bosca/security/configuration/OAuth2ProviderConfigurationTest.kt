package bosca.security.configuration

import bosca.security.service.OAuth2Provider
import bosca.security.service.WebAuthnConfiguration
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Validates construction, defaults, and equality semantics for the [OAuth2Provider]
 * configuration data class, which holds the OAuth2 integration settings for a
 * specific identity provider (Google, Facebook, Apple, etc.).
 */
class OAuth2ProviderConfigurationTest {

    private fun defaultProvider(
        type: String = "google",
        clientId: String = "client-id-123",
        clientSecret: String = "client-secret-456",
        supportedClientIds: Set<String> = emptySet(),
        enabled: Boolean = true,
        callback: String = "https://app.example.com/callback",
        adminCallback: String = "https://admin.example.com/callback",
        scopes: List<String> = listOf("openid", "email", "profile"),
        userInfoUrl: String = "https://provider.example.com/userinfo",
        authorizeUrl: String = "https://provider.example.com/authorize",
        accessTokenUrl: String = "https://provider.example.com/token",
        pkceEnabled: Boolean = true,
    ) = OAuth2Provider(
        type = type,
        clientId = clientId,
        clientSecret = clientSecret,
        supportedClientIds = supportedClientIds,
        enabled = enabled,
        callback = callback,
        adminCallback = adminCallback,
        scopes = scopes,
        userInfoUrl = userInfoUrl,
        authorizeUrl = authorizeUrl,
        accessTokenUrl = accessTokenUrl,
        pkceEnabled = pkceEnabled,
    )

    @Test
    fun `supports default empty supportedClientIds`() {
        val provider = defaultProvider()
        assertTrue(provider.supportedClientIds.isEmpty())
    }

    @Test
    fun `preserves all configured values`() {
        val provider = defaultProvider(
            type = "facebook",
            clientId = "fb-client",
            clientSecret = "fb-secret",
            supportedClientIds = setOf("ios-client", "android-client"),
            enabled = false,
            callback = "https://app.example.com/fb/callback",
            adminCallback = "https://admin.example.com/fb/callback",
            scopes = listOf("public_profile"),
            userInfoUrl = "https://graph.facebook.com/me",
            authorizeUrl = "https://facebook.com/dialog/oauth",
            accessTokenUrl = "https://graph.facebook.com/oauth/access_token"
        )

        assertEquals("facebook", provider.type)
        assertEquals("fb-client", provider.clientId)
        assertEquals("fb-secret", provider.clientSecret)
        assertEquals(setOf("ios-client", "android-client"), provider.supportedClientIds)
        assertFalse(provider.enabled)
        assertEquals("https://app.example.com/fb/callback", provider.callback)
        assertEquals("https://admin.example.com/fb/callback", provider.adminCallback)
        assertEquals(listOf("public_profile"), provider.scopes)
        assertEquals("https://graph.facebook.com/me", provider.userInfoUrl)
        assertEquals("https://facebook.com/dialog/oauth", provider.authorizeUrl)
        assertEquals("https://graph.facebook.com/oauth/access_token", provider.accessTokenUrl)
    }

    @Test
    fun `data class equality works correctly`() {
        val p1 = defaultProvider()
        val p2 = defaultProvider()
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
    }

    @Test
    fun `different type produces non-equal instances`() {
        val p1 = defaultProvider(type = "google")
        val p2 = defaultProvider(type = "apple")
        assertFalse(p1 == p2)
    }

    @Test
    fun `copy works correctly`() {
        val original = defaultProvider()
        val disabled = original.copy(enabled = false)
        assertFalse(disabled.enabled)
        assertEquals(original.clientId, disabled.clientId)
    }

    @Test
    fun `scopes list is ordered`() {
        val provider = defaultProvider(scopes = listOf("openid", "email", "profile"))
        assertEquals("openid", provider.scopes[0])
        assertEquals("email", provider.scopes[1])
        assertEquals("profile", provider.scopes[2])
    }

    @Test
    fun `supportedClientIds deduplication via set`() {
        val provider = defaultProvider(supportedClientIds = setOf("id1", "id1", "id2"))
        assertEquals(2, provider.supportedClientIds.size)
        assertTrue(provider.supportedClientIds.contains("id1"))
        assertTrue(provider.supportedClientIds.contains("id2"))
    }

    @Test
    fun `equality compares every provider property`() {
        val provider = defaultProvider(supportedClientIds = setOf("mobile"))

        assertEquals(provider, provider)
        assertEquals(provider, provider.copy())
        assertFalse(provider.equals(null))
        assertNotEquals<Any>(provider, "google")
        assertNotEquals(provider, provider.copy(type = "apple"))
        assertNotEquals(provider, provider.copy(clientId = "other"))
        assertNotEquals(provider, provider.copy(clientSecret = "other"))
        assertNotEquals(provider, provider.copy(supportedClientIds = setOf("other")))
        assertNotEquals(provider, provider.copy(enabled = false))
        assertNotEquals(provider, provider.copy(callback = "https://other.example/callback"))
        assertNotEquals(provider, provider.copy(adminCallback = "https://other.example/admin-callback"))
        assertNotEquals(provider, provider.copy(scopes = listOf("openid")))
        assertNotEquals(provider, provider.copy(userInfoUrl = "https://other.example/userinfo"))
        assertNotEquals(provider, provider.copy(authorizeUrl = "https://other.example/authorize"))
        assertNotEquals(provider, provider.copy(accessTokenUrl = "https://other.example/token"))
        assertNotEquals(provider, provider.copy(pkceEnabled = false))
    }

    @Test
    fun `enabled provider requires HTTPS for every remote endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            defaultProvider(accessTokenUrl = "http://provider.example/token")
        }
        assertFailsWith<IllegalArgumentException> {
            defaultProvider(userInfoUrl = "http://provider.example/userinfo")
        }
        assertFailsWith<IllegalArgumentException> {
            defaultProvider(authorizeUrl = "http://provider.example/authorize")
        }
    }

    @Test
    fun `disabled provider permits placeholder endpoints`() {
        val provider = defaultProvider(
            enabled = false,
            accessTokenUrl = "",
            userInfoUrl = "",
            authorizeUrl = "",
        )

        assertEquals("", provider.accessTokenUrl)
    }

    @Test
    fun `provider serialization handles defaults and explicit PKCE setting`() {
        val provider = defaultProvider()
        val decoded = Json.decodeFromString<OAuth2Provider>(Json.encodeToString(provider))
        val encodeDefaults = Json { this.encodeDefaults = true }
        val withoutPkce = Json.decodeFromString<OAuth2Provider>(
            """
            {
              "type":"test",
              "clientId":"id",
              "clientSecret":"secret",
              "enabled":false,
              "callback":"callback",
              "adminCallback":"admin",
              "scopes":[],
              "userInfoUrl":"",
              "authorizeUrl":"",
              "accessTokenUrl":""
            }
            """.trimIndent()
        )

        assertEquals(provider, decoded)
        assertTrue(withoutPkce.pkceEnabled)
        assertEquals(
            withoutPkce,
            encodeDefaults.decodeFromString(encodeDefaults.encodeToString(withoutPkce))
        )
    }

    @Test
    fun `WebAuthn configuration supports defaults serialization and equality`() {
        val defaults = WebAuthnConfiguration()
        val configured = WebAuthnConfiguration(
            rpName = "Example",
            rpId = "example.com",
            origins = listOf("https://example.com"),
        )

        assertEquals("Bosca", defaults.rpName)
        assertEquals(defaults, Json.decodeFromString("""{}"""))
        assertEquals(defaults, Json.decodeFromString(Json.encodeToString(defaults)))
        val encodeDefaults = Json { this.encodeDefaults = true }
        assertEquals(defaults, encodeDefaults.decodeFromString(encodeDefaults.encodeToString(defaults)))
        assertEquals(configured, Json.decodeFromString(Json.encodeToString(configured)))
        assertEquals(configured, configured)
        assertEquals(configured, configured.copy())
        assertFalse(configured.equals(null))
        assertNotEquals<Any>(configured, "Example")
        assertNotEquals(configured, configured.copy(rpName = "Other"))
        assertNotEquals(configured, configured.copy(rpId = "other.example"))
        assertNotEquals(configured, configured.copy(origins = listOf("https://other.example")))
        assertEquals(defaults.hashCode(), defaults.copy().hashCode())
    }
}
