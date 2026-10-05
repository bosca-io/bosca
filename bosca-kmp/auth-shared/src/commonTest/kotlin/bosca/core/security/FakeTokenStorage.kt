package bosca.core.security

import bosca.core.security.model.Identity
import bosca.core.security.model.TokenMetadata

/**
 * In-memory [TokenStorage] for tests. Mirrors the real platform implementations'
 * pattern — a flat key/value store with metadata serialized through
 * [TokenMetadataCodec] — so the contract tests exercise the same codec path the
 * production stores use. Reused by the TokenManager and BoscaAuth test suites.
 */
class FakeTokenStorage : TokenStorage, IdentityStorage {

    /** Backing store, exposed so tests can simulate cross-tab writes / corruption. */
    val store: MutableMap<String, String> = mutableMapOf()

    override suspend fun getToken(): String? = store["token"]

    override suspend fun saveToken(token: String) {
        store["token"] = token
    }

    override suspend fun getRefreshToken(): String? = store["refresh_token"]

    override suspend fun saveRefreshToken(refreshToken: String) {
        store["refresh_token"] = refreshToken
    }

    override suspend fun getTokenMetadata(): TokenMetadata? =
        TokenMetadataCodec.decode(store["token_meta"])

    override suspend fun saveTokenMetadata(metadata: TokenMetadata) {
        store["token_meta"] = TokenMetadataCodec.encode(metadata)
    }

    override suspend fun getIdentity(): Identity? =
        IdentityCodec.decode(store["identity"])

    override suspend fun setIdentity(identity: Identity?) {
        if (identity == null) {
            store.remove("identity")
        } else {
            store["identity"] = IdentityCodec.encode(identity)
        }
    }

    override suspend fun clear() {
        store.remove("token")
        store.remove("refresh_token")
        store.remove("token_meta")
    }
}
