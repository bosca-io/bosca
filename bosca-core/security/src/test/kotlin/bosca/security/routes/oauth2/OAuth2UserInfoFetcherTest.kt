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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuth2UserInfoFetcherTest {

    private val httpClient = mockk<OkHttpClient>()
    private val json = Json { ignoreUnknownKeys = true }
    private val fetcher = OAuth2UserInfoFetcher(httpClient, json)

    private fun googleProvider() = OAuth2Provider(
        type = "google",
        clientId = "client-id",
        clientSecret = "client-secret",
        enabled = true,
        callback = "https://example.com/callback",
        adminCallback = "https://admin.example.com/callback",
        scopes = listOf("openid", "email"),
        userInfoUrl = "https://www.googleapis.com/oauth2/v2/userinfo",
        authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
        accessTokenUrl = "https://oauth2.googleapis.com/token"
    )

    private fun facebookProvider() = OAuth2Provider(
        type = "facebook",
        clientId = "fb-client-id",
        clientSecret = "fb-client-secret",
        enabled = true,
        callback = "https://example.com/callback",
        adminCallback = "https://admin.example.com/callback",
        scopes = listOf("email", "public_profile"),
        userInfoUrl = "https://graph.facebook.com/me",
        authorizeUrl = "https://www.facebook.com/v16.0/dialog/oauth",
        accessTokenUrl = "https://graph.facebook.com/v16.0/oauth/access_token"
    )

    private fun mockCallWithResponse(body: String, successful: Boolean = true): okhttp3.Call {
        val response = mockk<Response>()
        every { response.isSuccessful } returns successful
        every { response.body } returns body.toResponseBody()

        val call = mockk<okhttp3.Call>(relaxed = true)
        val callbackSlot = slot<Callback>()
        every { call.enqueue(capture(callbackSlot)) } answers {
            callbackSlot.captured.onResponse(call, response)
        }
        return call
    }

    @Test
    fun `fetchUser for generic provider sends Bearer token and returns user`() = runTest {
        val requestSlot = slot<Request>()
        val call = mockCallWithResponse(
            """{"id": "user-1", "name": "Test User", "email": "test@example.com", "given_name": "Test", "family_name": "User", "picture": null}"""
        )
        every { httpClient.newCall(capture(requestSlot)) } returns call

        val user = fetcher.fetchUser(googleProvider(), "access-token-123")

        assertEquals("user-1", user.id)
        assertEquals("Test User", user.name)
        assertEquals("test@example.com", user.email)
        assertEquals("Bearer access-token-123", requestSlot.captured.header("Authorization"))
    }

    @Test
    fun `fetchUser for facebook provider returns FacebookUser`() = runTest {
        val requestSlot = slot<Request>()
        val call = mockCallWithResponse(
            """{"id": "fb-123", "name": "Jane Doe", "email": "jane@example.com"}"""
        )
        every { httpClient.newCall(capture(requestSlot)) } returns call

        val user = fetcher.fetchUser(facebookProvider(), "fb-token")

        assertEquals("fb-123", user.id)
        assertEquals("Jane Doe", user.name)
        val url = requestSlot.captured.url.toString()
        assertTrue(url.contains("appsecret_proof"), "Facebook request should include appsecret_proof")
    }

    @Test
    fun `fetchUser throws when response is unsuccessful`() = runTest {
        val call = mockCallWithResponse("{}", successful = false)
        every { httpClient.newCall(any()) } returns call

        assertFailsWith<IllegalStateException> {
            fetcher.fetchUser(googleProvider(), "bad-token")
        }
    }

    @Test
    fun `fetchUser reads email_verified boolean true`() = runTest {
        val call = mockCallWithResponse(
            """{"id": "u1", "name": "A", "email": "a@example.com", "email_verified": true}"""
        )
        every { httpClient.newCall(any()) } returns call

        assertTrue(fetcher.fetchUser(googleProvider(), "t").emailVerified)
    }

    @Test
    fun `fetchUser reads email_verified string true`() = runTest {
        val call = mockCallWithResponse(
            """{"id": "u1", "name": "A", "email": "a@example.com", "email_verified": "true"}"""
        )
        every { httpClient.newCall(any()) } returns call

        assertTrue(fetcher.fetchUser(googleProvider(), "t").emailVerified)
    }

    @Test
    fun `fetchUser reads google legacy verified_email flag`() = runTest {
        val call = mockCallWithResponse(
            """{"id": "u1", "name": "A", "email": "a@example.com", "verified_email": true}"""
        )
        every { httpClient.newCall(any()) } returns call

        assertTrue(fetcher.fetchUser(googleProvider(), "t").emailVerified)
    }

    @Test
    fun `fetchUser defaults emailVerified to false when claim absent`() = runTest {
        val call = mockCallWithResponse(
            """{"id": "u1", "name": "A", "email": "a@example.com"}"""
        )
        every { httpClient.newCall(any()) } returns call

        assertFalse(fetcher.fetchUser(googleProvider(), "t").emailVerified)
    }

    @Test
    fun `fetchUser treats email_verified false as unverified`() = runTest {
        val call = mockCallWithResponse(
            """{"id": "u1", "name": "A", "email": "a@example.com", "email_verified": false}"""
        )
        every { httpClient.newCall(any()) } returns call

        assertFalse(fetcher.fetchUser(googleProvider(), "t").emailVerified)
    }

    @Test
    fun `facebook user with email is treated as verified`() = runTest {
        val call = mockCallWithResponse(
            """{"id": "fb-1", "name": "Jane", "email": "jane@example.com"}"""
        )
        every { httpClient.newCall(any()) } returns call

        assertTrue(fetcher.fetchUser(facebookProvider(), "t").emailVerified)
    }

    @Test
    fun `facebook user without email is not verified`() = runTest {
        val call = mockCallWithResponse(
            """{"id": "fb-1", "name": "Jane"}"""
        )
        every { httpClient.newCall(any()) } returns call

        assertFalse(fetcher.fetchUser(facebookProvider(), "t").emailVerified)
    }
}
