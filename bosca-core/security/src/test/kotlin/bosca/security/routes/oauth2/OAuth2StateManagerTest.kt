package bosca.security.routes.oauth2

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheValue
import bosca.security.model.SignupTokenType
import bosca.server.Parameters
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuth2StateManagerTest {

    private val cache = mockk<Cache<String>>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val allowedRedirects = listOf("https://example.com", "/")

    private fun createManager() = OAuth2StateManager(cache, json, allowedRedirects)

    private fun params(vararg pairs: Pair<String, String>): Parameters {
        return Parameters(pairs.associate { it.first to listOf(it.second) })
    }

    private var capturedValue: String? = null

    private fun setupCachePutCapture() {
        coEvery { cache.put(any(), any()) } coAnswers {
            capturedValue = secondArg()
        }
    }

    @Test
    fun `createState stores state with organization and community tokens`() = runTest {
        setupCachePutCapture()

        val manager = createManager()
        val codeChallenge = manager.createState(params("organization" to "org-123", "community" to "grp-456"), "test-state", "google")

        coVerify { cache.put(any(), any()) }
        assertNotNull(codeChallenge)
        val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
        assertEquals("test-state", stored.state)
        assertEquals("google", stored.provider)
        assertEquals(2, stored.tokens.size)
        assertEquals(SignupTokenType.ORGANIZATION, stored.tokens[0].type)
        assertEquals("org-123", stored.tokens[0].token)
        assertEquals(SignupTokenType.COMMUNITY_GROUP, stored.tokens[1].type)
        assertEquals("grp-456", stored.tokens[1].token)
        assertTrue(stored.codeVerifier.isNotBlank())
    }

    @Test
    fun `createState with no tokens stores empty token list`() = runTest {
        setupCachePutCapture()

        val manager = createManager()
        manager.createState(Parameters.Empty, "state-1", "google")

        val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
        assertTrue(stored.tokens.isEmpty())
        assertEquals("google", stored.provider)
        assertEquals("/", stored.redirect)
    }

    @Test
    fun `createState preserves the principal for an account connection`() = runTest {
        setupCachePutCapture()
        val principalId = bosca.serialization.UUID.random()

        createManager().createState(
            params("redirect" to "https://example.com/security"),
            "connect-state",
            "google",
            connectPrincipalId = principalId,
        )

        val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
        assertEquals(principalId, stored.connectPrincipalId)
        assertEquals("https://example.com/security", stored.redirect)
    }

    @Test
    fun `createState validates redirect against allowed redirects`() = runTest {
        setupCachePutCapture()

        val manager = createManager()
        manager.createState(params("redirect" to "https://example.com/dashboard"), "state-2", "google")

        val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
        assertEquals("https://example.com/dashboard", stored.redirect)
    }

    @Test
    fun `createState rejects invalid redirect and defaults to slash`() = runTest {
        setupCachePutCapture()

        val manager = createManager()
        manager.createState(params("redirect" to "https://evil.com/phish"), "state-3", "google")

        val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
        assertEquals("/", stored.redirect)
    }

    @Test
    fun `createState defaults redirect to slash when blank`() = runTest {
        setupCachePutCapture()

        val manager = createManager()
        manager.createState(params("redirect" to ""), "state-4", "google")

        val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
        assertEquals("/", stored.redirect)
    }

    @Test
    fun `createState defaults redirect to slash when whitespace`() = runTest {
        setupCachePutCapture()

        createManager().createState(params("redirect" to " \t "), "state-whitespace", "google")

        val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
        assertEquals("/", stored.redirect)
    }

    @Test
    fun `createState sets admin flag from query parameter`() = runTest {
        setupCachePutCapture()

        val manager = createManager()
        manager.createState(params("admin" to "true"), "state-5", "google")

        val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
        assertTrue(stored.admin)
    }

    @Test
    fun `createState returns valid code challenge`() = runTest {
        setupCachePutCapture()

        val manager = createManager()
        val codeChallenge = manager.createState(Parameters.Empty, "state-pkce", "google")

        assertTrue(codeChallenge.isNotBlank())
        // Code challenge should be base64url-encoded (no padding, no + or /)
        assertTrue(codeChallenge.none { it == '=' || it == '+' || it == '/' })
        val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
        // Verify the stored code verifier produces the returned code challenge
        assertEquals(codeChallenge, OAuth2StateManager.generateCodeChallenge(stored.codeVerifier))
    }

    @Test
    fun `code verifier is unique per state`() = runTest {
        val verifiers = mutableSetOf<String>()
        val manager = createManager()
        setupCachePutCapture()

        repeat(10) { i ->
            manager.createState(Parameters.Empty, "state-$i", "google")
            val stored = json.decodeFromString<OAuth2State>(capturedValue!!)
            verifiers.add(stored.codeVerifier)
        }

        assertEquals(10, verifiers.size, "Each state should have a unique code verifier")
    }

    @Test
    fun `retrieveAndRemoveState returns deserialized state`() = runTest {
        val state = OAuth2State("state-6", "google", emptyList(), "/home", false, "test-verifier")
        val serialized = json.encodeToString(state)
        val cacheValue = object : CacheValue {
            override val value: String = serialized
            override val exists: Boolean = true
        }
        coEvery { cache.remove(any<CacheKey<String>>()) } returns cacheValue

        val manager = createManager()
        val result = manager.retrieveAndRemoveState("state-6")

        assertNotNull(result)
        assertEquals("state-6", result.state)
        assertEquals("/home", result.redirect)
    }

    @Test
    fun `retrieveAndRemoveState returns null when cache miss`() = runTest {
        coEvery { cache.remove(any<CacheKey<String>>()) } returns null

        val manager = createManager()
        val result = manager.retrieveAndRemoveState("missing")

        assertNull(result)
    }

    @Test
    fun `retrieveAndRemoveState returns null when cache value is null`() = runTest {
        val cacheValue = object : CacheValue {
            override val value: String? = null
            override val exists: Boolean = true
        }
        coEvery { cache.remove(any<CacheKey<String>>()) } returns cacheValue

        val manager = createManager()
        val result = manager.retrieveAndRemoveState("expired")

        assertNull(result)
    }
}
