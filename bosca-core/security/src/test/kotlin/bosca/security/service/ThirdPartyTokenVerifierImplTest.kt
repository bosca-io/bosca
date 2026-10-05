package bosca.security.service

import bosca.di.ObjectProvider
import com.auth0.jwk.Jwk
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThirdPartyTokenVerifierImplTest {

    private val configurationProvider = mockk<ObjectProvider<SecurityConfiguration>>()
    private val configuration = mockk<SecurityConfiguration>()
    private val call = mockk<Call>()
    private val request = slot<Request>()
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey

    @BeforeTest
    fun setUp() {
        mockkConstructor(OkHttpClient::class)
        mockkConstructor(UrlJwkProvider::class)
        every {
            anyConstructed<OkHttpClient>().newCall(capture(request))
        } returns call
        every {
            anyConstructed<UrlJwkProvider>().get("test-key")
        } returns mockk<Jwk> {
            every { publicKey } returns this@ThirdPartyTokenVerifierImplTest.publicKey
        }
        coEvery { configurationProvider.get() } returns configuration
    }

    @AfterTest
    fun tearDown() {
        unmockkConstructor(UrlJwkProvider::class)
        unmockkConstructor(OkHttpClient::class)
    }

    @Test
    fun `unsupported provider type is rejected`() = runTest {
        every { configuration.oauth2 } returns emptyList()

        assertFailsWith<IllegalArgumentException> {
            verifier().verify(ThirdPartyType.GOOGLE, "token")
        }
    }

    @Test
    fun `Google token maps identity and string verified claim`() = runTest {
        every { configuration.oauth2 } returns listOf(provider("google"))
        respond(
            200,
            """
            {
              "aud":"client",
              "sub":"subject",
              "email":"person@example.com",
              "name":"Person Example",
              "picture":"https://example.com/picture",
              "given_name":"Person",
              "family_name":"Example",
              "email_verified":"TrUe"
            }
            """.trimIndent(),
        )

        val user = verifier().verify(ThirdPartyType.GOOGLE, "token +/?")

        assertEquals("subject", user.id)
        assertEquals("Person Example", user.name)
        assertEquals("Person", user.givenName)
        assertEquals("Example", user.familyName)
        assertEquals("https://example.com/picture", user.picture)
        assertEquals("person@example.com", user.email)
        assertTrue(user.emailVerified)
        assertTrue(request.captured.url.toString().contains("token+%2B%2F%3F"))
    }

    @Test
    fun `Google accepts supported alternate audience and boolean claim`() = runTest {
        every {
            configuration.oauth2
        } returns listOf(provider("google", supportedClientIds = setOf("alternate")))
        respond(
            200,
            """{"aud":"alternate","sub":"subject","email_verified":true}""",
        )

        val user = verifier().verify(ThirdPartyType.GOOGLE, "token")

        assertTrue(user.emailVerified)
    }

    @Test
    fun `Google safely defaults absent verification claim to false`() = runTest {
        every { configuration.oauth2 } returns listOf(provider("google"))
        respond(200, """{"aud":"client","sub":"subject"}""")

        val user = verifier().verify(ThirdPartyType.GOOGLE, "token")

        assertFalse(user.emailVerified)
    }

    @Test
    fun `Google handles explicit null optional claims and a preceding nonmatching provider`() = runTest {
        every {
            configuration.oauth2
        } returns listOf(provider("facebook"), provider("google"))
        respond(
            200,
            """
            {
              "aud":"client",
              "sub":"subject",
              "email":null,
              "name":null,
              "picture":null,
              "given_name":null,
              "family_name":null,
              "email_verified":"false"
            }
            """.trimIndent(),
        )

        val user = verifier().verify(ThirdPartyType.GOOGLE, "token")

        assertFalse(user.emailVerified)
    }

    @Test
    fun `Google treats an explicit null verification claim as false`() = runTest {
        every { configuration.oauth2 } returns listOf(provider("google"))
        respond(
            200,
            """{"aud":"client","sub":"subject","email_verified":null}""",
        )

        assertFalse(verifier().verify(ThirdPartyType.GOOGLE, "token").emailVerified)
    }

    @Test
    fun `Google rejects HTTP errors missing claims and invalid audiences`() = runTest {
        every { configuration.oauth2 } returns listOf(provider("google"))
        val verifier = verifier()

        respond(401, "{}")
        assertFailsWith<SecurityException> {
            verifier.verify(ThirdPartyType.GOOGLE, "one")
        }

        respond(200, """{"sub":"subject"}""")
        assertFailsWith<SecurityException> {
            verifier.verify(ThirdPartyType.GOOGLE, "two")
        }

        respond(200, """{"aud":"wrong","sub":"subject"}""")
        assertFailsWith<SecurityException> {
            verifier.verify(ThirdPartyType.GOOGLE, "three")
        }

        respond(200, """{"aud":"client"}""")
        assertFailsWith<SecurityException> {
            verifier.verify(ThirdPartyType.GOOGLE, "four")
        }
    }

    @Test
    fun `Facebook token includes encoded token and HMAC proof and maps response`() = runTest {
        every { configuration.oauth2 } returns listOf(provider("facebook"))
        respond(
            200,
            """
            {
              "id":"facebook-id",
              "name":"Person Example",
              "email":"person@example.com",
              "picture":{"data":{"url":"https://example.com/picture"}}
            }
            """.trimIndent(),
        )

        val user = verifier().verify(ThirdPartyType.FACEBOOK, "token +")

        assertEquals("facebook-id", user.id)
        assertEquals("Person", user.givenName)
        assertEquals("Example", user.familyName)
        assertEquals("https://example.com/picture", user.picture)
        assertTrue(user.emailVerified)
        val url = request.captured.url.toString()
        assertTrue(url.contains("access_token=token+%2B"))
        assertTrue(url.contains("appsecret_proof="))
    }

    @Test
    fun `Facebook HTTP failures are rejected`() = runTest {
        every { configuration.oauth2 } returns listOf(provider("facebook"))
        respond(403, "{}")

        assertFailsWith<SecurityException> {
            verifier().verify(ThirdPartyType.FACEBOOK, "token")
        }
    }

    @Test
    fun `Apple verifies boolean email claim and maps identity`() = runTest {
        every { configuration.oauth2 } returns listOf(provider("apple"))
        val token = appleToken(
            subject = "apple-subject",
            email = "person@example.com",
            name = "Person",
            emailVerified = true,
        )

        val user = verifier().verify(ThirdPartyType.APPLE, token)

        assertEquals("apple-subject", user.id)
        assertEquals("person@example.com", user.email)
        assertEquals("Person", user.name)
        assertTrue(user.emailVerified)
    }

    @Test
    fun `Apple accepts string verification and absent optional claims`() = runTest {
        every { configuration.oauth2 } returns listOf(provider("apple"))
        val stringClaim = appleToken(
            subject = "string-subject",
            emailVerifiedString = "TrUe",
        )
        val absentClaim = appleToken(subject = "absent-subject")

        val verified = verifier().verify(ThirdPartyType.APPLE, stringClaim)
        val absent = verifier().verify(ThirdPartyType.APPLE, absentClaim)

        assertTrue(verified.emailVerified)
        assertFalse(absent.emailVerified)
        assertEquals(null, absent.email)
        assertEquals(null, absent.name)
    }

    @Test
    fun `Apple rejects an invalid audience and missing subject`() = runTest {
        every { configuration.oauth2 } returns listOf(provider("apple"))

        assertFailsWith<com.auth0.jwt.exceptions.JWTVerificationException> {
            verifier().verify(
                ThirdPartyType.APPLE,
                appleToken(subject = "subject", audience = "other-client"),
            )
        }
        assertFailsWith<SecurityException> {
            verifier().verify(
                ThirdPartyType.APPLE,
                appleToken(subject = null),
            )
        }
    }

    private fun verifier() = ThirdPartyTokenVerifierImpl(
        configurationProvider,
        Json { ignoreUnknownKeys = true },
    )

    private fun provider(
        type: String,
        supportedClientIds: Set<String> = emptySet(),
    ) = OAuth2Provider(
        type = type,
        clientId = "client",
        clientSecret = "secret",
        supportedClientIds = supportedClientIds,
        enabled = true,
        callback = "https://example.com/callback",
        adminCallback = "https://admin.example.com/callback",
        scopes = listOf("openid", "email"),
        userInfoUrl = "https://example.com/user",
        authorizeUrl = "https://example.com/authorize",
        accessTokenUrl = "https://example.com/token",
    )

    private fun respond(code: Int, body: String) {
        every { call.enqueue(any()) } answers {
            val callback = firstArg<Callback>()
            val response = Response.Builder()
                .request(Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody())
                .build()
            callback.onResponse(this@ThirdPartyTokenVerifierImplTest.call, response)
        }
        every { call.cancel() } returns Unit
    }

    private fun appleToken(
        subject: String?,
        audience: String = "client",
        email: String? = null,
        name: String? = null,
        emailVerified: Boolean? = null,
        emailVerifiedString: String? = null,
    ): String {
        val builder = JWT.create()
            .withKeyId("test-key")
            .withIssuer("https://appleid.apple.com")
            .withAudience(audience)
        subject?.let(builder::withSubject)
        email?.let { builder.withClaim("email", it) }
        name?.let { builder.withClaim("name", it) }
        emailVerified?.let { builder.withClaim("email_verified", it) }
        emailVerifiedString?.let { builder.withClaim("email_verified", it) }
        return builder.sign(Algorithm.RSA256(publicKey, privateKey))
    }
}
