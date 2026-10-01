package bosca.cli.config

import bosca.cli.api.CliAuth
import bosca.cli.api.NetworkClient
import bosca.core.security.model.TokenMetadata
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the CLI's [ConfigFileTokenStorage] adapter persists tokens to the
 * `~/.config/bosca/config.json` schema [BoscaAuth] expects, keeps profiles
 * isolated, and migrates the legacy single-account schema.
 */
class ConfigFileTokenStorageTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("bosca-cfg-test", "").apply {
            delete()
            mkdirs()
        }
        CliConfigStore.directoryOverride = tempDir
    }

    @AfterTest
    fun tearDown() {
        CliConfigStore.directoryOverride = null
        tempDir.deleteRecursively()
    }

    private val endpoint = "https://api.test/graphql"

    @Test
    fun cliAuthEnablesProactiveRotationForLongLivedSessions() {
        val config = CliAuth.configuration(endpoint)

        assertTrue(config.autoRefresh)
    }

    @Test
    fun roundTripsAccessAndRefreshTokens_andPersistsEndpoint() = runBlocking {
        val storage = ConfigFileTokenStorage(endpoint, "work")
        storage.saveToken("access-1")
        storage.saveRefreshToken("refresh-1")

        assertEquals("access-1", storage.getToken())
        assertEquals("refresh-1", storage.getRefreshToken())
        assertEquals(endpoint, CliConfigStore.load().profiles["work"]?.endpoint)
    }

    @Test
    fun tokenMetadataIsNotPersisted_soTokenManagerFallsBackToJwtParsing() = runBlocking {
        val storage = ConfigFileTokenStorage(endpoint, "work")
        storage.saveToken("access-1")
        storage.saveTokenMetadata(TokenMetadata(expiresAt = 123, issuedAt = 100))

        // The config.json schema has no metadata field; getTokenMetadata is null
        // and the access token is unaffected by the no-op save.
        assertNull(storage.getTokenMetadata())
        assertEquals("access-1", storage.getToken())
    }

    @Test
    fun clearRemovesAuthState() = runBlocking {
        val storage = ConfigFileTokenStorage(endpoint, "work")
        storage.saveToken("access-1")
        storage.saveRefreshToken("refresh-1")

        storage.clear()

        assertNull(storage.getToken())
        assertNull(storage.getRefreshToken())
        assertNull(CliConfigStore.load().profiles["work"]?.auth)
    }

    @Test
    fun readsSessionWrittenByBoscaLogin_andPreservesPrincipalIdOnSave() = runBlocking {
        // Simulate what `bosca login` writes, then read it through the adapter.
        CliConfigStore.save(
            CliConfig(
                activeProfile = "work",
                profiles = mapOf(
                    "work" to ProfileConfig(
                        endpoint = endpoint,
                        auth = AuthConfig(token = "t", refreshToken = "r", principalId = "p"),
                    ),
                ),
            ),
        )

        val storage = ConfigFileTokenStorage(endpoint, "work")
        assertEquals("t", storage.getToken())
        assertEquals("r", storage.getRefreshToken())

        // A rotated access token must not wipe the recorded principal id.
        storage.saveToken("t2")
        val auth = CliConfigStore.load().profiles["work"]?.auth
        assertEquals("t2", auth?.token)
        assertEquals("r", auth?.refreshToken)
        assertEquals("p", auth?.principalId)
    }

    @Test
    fun nonJwtApiTokenIsStoredWithNoRefreshToken() = runBlocking {
        // An API token stored by `bosca login --api-token`: opaque, no refresh.
        CliConfigStore.save(
            CliConfig(
                activeProfile = "automation",
                profiles = mapOf(
                    "automation" to ProfileConfig(
                        endpoint = endpoint,
                        auth = AuthConfig(token = "bsk_opaque"),
                    ),
                ),
            ),
        )
        val storage = ConfigFileTokenStorage(endpoint, "automation")

        assertEquals("bsk_opaque", storage.getToken())
        assertNull(storage.getRefreshToken())
        assertTrue(CliConfigStore.load().profiles["automation"]?.auth?.refreshToken == null)
    }

    @Test
    fun migratesPreExistingCliSession_jwtExpiryParsedFromTokenNoMetadata() = runBlocking {
        // A config.json exactly as the pre-migration CLI wrote it: a JWT access
        // token + refresh token + principalId, and NO expiry-metadata field
        // (the old schema had none). Upgrading must require no migration step.
        val jwt = unexpiredJwt()
        File(CliConfigStore.configPath()).writeText(
            """
            {
              "endpoint": "$endpoint",
              "auth": {
                "token": "$jwt",
                "refreshToken": "r",
                "principalId": "p"
              }
            }
            """.trimIndent(),
        )

        val migrated = CliConfigStore.load()
        assertEquals(DEFAULT_PROFILE_NAME, migrated.activeProfile)
        assertEquals(endpoint, migrated.profiles[DEFAULT_PROFILE_NAME]?.endpoint)
        assertEquals("p", migrated.profiles[DEFAULT_PROFILE_NAME]?.auth?.principalId)
        val stored = Json.parseToJsonElement(File(CliConfigStore.configPath()).readText()).jsonObject
        assertTrue("profiles" in stored)
        assertFalse("endpoint" in stored)
        assertFalse("auth" in stored)

        val auth = CliAuth.create(endpoint, DEFAULT_PROFILE_NAME)
        auth.initialize(fetchProfile = false)

        // The session is restored and the (unexpired) token returned with no
        // network call — its expiry was parsed from the JWT itself, since the
        // legacy config carries no metadata.
        assertEquals(jwt, auth.getToken())
    }

    @Test
    fun profilesOnSameEndpointKeepCredentialsIndependent() = runBlocking {
        val first = ConfigFileTokenStorage(endpoint, "first")
        val second = ConfigFileTokenStorage(endpoint, "second")

        first.saveToken("first-token")
        first.saveRefreshToken("first-refresh")
        second.saveToken("second-token")
        second.saveRefreshToken("second-refresh")

        assertEquals("first-token", first.getToken())
        assertEquals("first-refresh", first.getRefreshToken())
        assertEquals("second-token", second.getToken())
        assertEquals("second-refresh", second.getRefreshToken())

        first.clear()

        assertNull(first.getToken())
        assertEquals("second-token", second.getToken())
        assertEquals("second-refresh", second.getRefreshToken())
    }

    @Test
    fun storedCredentialsAreNotAppliedToADifferentEndpoint() = runBlocking {
        CliConfigStore.save(
            CliConfig(
                activeProfile = "work",
                profiles = mapOf(
                    "work" to ProfileConfig(
                        endpoint = endpoint,
                        auth = AuthConfig(token = "work-token"),
                    ),
                ),
            ),
        )
        val otherEndpoint = "https://other.example/graphql"
        val network = NetworkClient(otherEndpoint)

        val authenticated = CliAuth.authenticate(
            network = network,
            endpoint = otherEndpoint,
            token = null,
            username = null,
            password = null,
            profileName = "work",
        )

        assertFalse(authenticated)
        assertNull(network.tokenProvider)
    }

    @Test
    fun staleStorageCannotReadOverwriteOrClearCredentialsAfterEndpointChanges() = runBlocking {
        val stale = ConfigFileTokenStorage(endpoint, "work")
        stale.saveToken("old-token")
        stale.saveRefreshToken("old-refresh")

        val newEndpoint = "https://other.example/graphql"
        CliConfigStore.update { config ->
            config.withProfile(
                "work",
                ProfileConfig(
                    endpoint = newEndpoint,
                    auth = AuthConfig(token = "new-token", refreshToken = "new-refresh"),
                ),
            )
        }

        assertNull(stale.getToken())
        assertNull(stale.getRefreshToken())
        assertFailsWith<IllegalStateException> {
            stale.saveToken("late-old-token")
        }
        stale.clear()

        val current = CliConfigStore.load().profiles["work"]
        assertEquals(newEndpoint, current?.endpoint)
        assertEquals("new-token", current?.auth?.token)
        assertEquals("new-refresh", current?.auth?.refreshToken)
    }

    @Test
    fun newLoginStorageCanReplaceEndpointWithoutRetainingOldAccountState() = runBlocking {
        CliConfigStore.save(
            CliConfig(
                activeProfile = "work",
                profiles = mapOf(
                    "work" to ProfileConfig(
                        endpoint = endpoint,
                        auth = AuthConfig(
                            token = "old-token",
                            refreshToken = "old-refresh",
                            principalId = "old-principal",
                        ),
                    ),
                ),
            ),
        )

        val newEndpoint = "https://other.example/graphql"
        val replacement = ConfigFileTokenStorage(
            endpoint = newEndpoint,
            profileName = "work",
            allowEndpointChange = true,
        )
        replacement.saveToken("new-token")

        val current = CliConfigStore.load().profiles["work"]
        assertEquals(newEndpoint, current?.endpoint)
        assertEquals("new-token", current?.auth?.token)
        assertNull(current?.auth?.refreshToken)
        assertNull(current?.auth?.principalId)
    }

    /** A minimal unsigned JWT whose payload carries a far-future `exp` (and `iat`). */
    @OptIn(ExperimentalEncodingApi::class)
    private fun unexpiredJwt(): String {
        val nowSec = System.currentTimeMillis() / 1000
        fun seg(json: String) =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())
        return "${seg("""{"alg":"none"}""")}.${seg("""{"exp":${nowSec + 3600},"iat":$nowSec}""")}.sig"
    }
}
