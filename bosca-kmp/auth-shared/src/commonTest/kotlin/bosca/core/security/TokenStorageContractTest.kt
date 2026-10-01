package bosca.core.security

import bosca.core.security.model.TokenMetadata
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Platform-agnostic contract for [TokenStorage], ported from storage.spec.ts.
 * Exercised against [FakeTokenStorage], which uses the same key names and
 * [TokenMetadataCodec] path as the real platform stores.
 *
 * Real per-platform implementations (EncryptedSharedPreferences, Keychain,
 * localStorage, java.util.prefs) are thin wrappers over their platform APIs;
 * dedicated instrumented tests for those are gated on enabling each target's
 * host/instrumented test infrastructure.
 */
class TokenStorageContractTest {

    @Test
    fun token_roundTrips_andIsAbsentInitially() = runTest {
        val storage = FakeTokenStorage()
        assertNull(storage.getToken())
        storage.saveToken("access-abc")
        assertEquals("access-abc", storage.getToken())
    }

    @Test
    fun refreshToken_roundTrips() = runTest {
        val storage = FakeTokenStorage()
        assertNull(storage.getRefreshToken())
        storage.saveRefreshToken("refresh-xyz")
        assertEquals("refresh-xyz", storage.getRefreshToken())
    }

    @Test
    fun metadata_roundTrips() = runTest {
        val storage = FakeTokenStorage()
        assertNull(storage.getTokenMetadata())
        val metadata = TokenMetadata(expiresAt = 200, issuedAt = 100)
        storage.saveTokenMetadata(metadata)
        assertEquals(metadata, storage.getTokenMetadata())
    }

    @Test
    fun clear_wipesTokensAndMetadata() = runTest {
        val storage = FakeTokenStorage()
        storage.saveToken("t")
        storage.saveRefreshToken("r")
        storage.saveTokenMetadata(TokenMetadata(expiresAt = 2, issuedAt = 1))

        storage.clear()

        assertNull(storage.getToken())
        assertNull(storage.getRefreshToken())
        assertNull(storage.getTokenMetadata())
    }

    @Test
    fun tokenWithoutMetadata_isBackwardCompatible() = runTest {
        // A session persisted before metadata existed: only the access token is
        // present. Reads must succeed; metadata is simply absent (the token
        // manager then falls back to parsing the JWT).
        val storage = FakeTokenStorage()
        storage.saveToken("legacy-access")
        assertEquals("legacy-access", storage.getToken())
        assertNull(storage.getTokenMetadata())
    }
}
