package bosca.security.routes.oauth2

import bosca.security.service.OAuth2Provider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OAuth2TokenExchangerTest {

    private val httpClient = mockk<OkHttpClient>()
    private val json = Json { ignoreUnknownKeys = true }
    private val exchanger = OAuth2TokenExchanger(httpClient, json)

    private fun testProvider() = OAuth2Provider(
        type = "google",
        clientId = "test-client-id",
        clientSecret = "test-client-secret",
        enabled = true,
        callback = "https://example.com/oauth2/google/callback",
        adminCallback = "https://admin.example.com/oauth2/google/callback",
        scopes = listOf("openid", "email"),
        userInfoUrl = "https://www.googleapis.com/oauth2/v2/userinfo",
        authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
        accessTokenUrl = "https://oauth2.googleapis.com/token"
    )

    private fun mockCallWithResponse(body: String, successful: Boolean = true): okhttp3.Call {
        val response = mockk<Response>()
        every { response.body } returns body.toResponseBody()
        every { response.isSuccessful } returns successful
        every { response.code } returns if (successful) 200 else 400

        val call = mockk<okhttp3.Call>(relaxed = true)
        val callbackSlot = slot<Callback>()
        every { call.enqueue(capture(callbackSlot)) } answers {
            callbackSlot.captured.onResponse(call, response)
        }
        return call
    }

    @Test
    fun `exchange sends request and returns access token`() = runTest {
        val requestSlot = slot<Request>()
        val call = mockCallWithResponse("""{"access_token": "test-token-123"}""")
        every { httpClient.newCall(capture(requestSlot)) } returns call

        val (token, state) = exchanger.exchange(testProvider(), "auth-code-456", "https://example.com/callback")

        assertEquals("test-token-123", token)
        assertNull(state)
        assertEquals("https://oauth2.googleapis.com/token", requestSlot.captured.url.toString())
        assertEquals("POST", requestSlot.captured.method)
    }

    @Test
    fun `exchange returns state from token response when present`() = runTest {
        val call = mockCallWithResponse("""{"access_token": "tok", "state": "returned-state"}""")
        every { httpClient.newCall(any()) } returns call

        val (token, state) = exchanger.exchange(testProvider(), "code", "https://example.com/callback")

        assertEquals("tok", token)
        assertEquals("returned-state", state)
    }

    @Test
    fun `exchange includes an encoded PKCE verifier`() = runTest {
        val requestSlot = slot<Request>()
        val call = mockCallWithResponse("""{"access_token":"token"}""")
        every { httpClient.newCall(capture(requestSlot)) } returns call

        exchanger.exchange(
            testProvider(),
            "code",
            "https://example.com/callback",
            codeVerifier = "verifier +/?",
        )

        assertEquals(
            true,
            requestSlot.captured.body
                ?.let { body ->
                    okio.Buffer().also(body::writeTo).readUtf8()
                }
                ?.contains("code_verifier=verifier+%2B%2F%3F"),
        )
    }

    @Test
    fun `exchange rejects HTTP failures and nonprimitive token fields`() = runTest {
        every {
            httpClient.newCall(any())
        } returns mockCallWithResponse("""{"error":"invalid_grant"}""", successful = false)
        assertFailsWith<IllegalStateException> {
            exchanger.exchange(testProvider(), "bad-code", "https://example.com/callback")
        }

        every {
            httpClient.newCall(any())
        } returns mockCallWithResponse("""{"access_token":{},"state":{}}""")
        assertFailsWith<IllegalStateException> {
            exchanger.exchange(testProvider(), "bad-token", "https://example.com/callback")
        }

        every {
            httpClient.newCall(any())
        } returns mockCallWithResponse("""{"access_token":"token","state":{}}""")
        assertNull(
            exchanger.exchange(testProvider(), "bad-state", "https://example.com/callback").second,
        )
    }

    @Test
    fun `exchange throws when response has no access_token`() = runTest {
        val call = mockCallWithResponse("""{"error": "invalid_grant"}""")
        every { httpClient.newCall(any()) } returns call

        assertFailsWith<IllegalStateException> {
            exchanger.exchange(testProvider(), "bad-code", "https://example.com/callback")
        }
    }
}
