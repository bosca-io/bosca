package bosca.core.security

import bosca.core.security.model.Identity
import bosca.core.security.model.TokenMetadata
import kotlinx.browser.window

class WebTokenStorage : TokenStorage, IdentityStorage {
    override suspend fun getToken(): String? = window.localStorage.getItem("token")

    override suspend fun saveToken(token: String) {
        window.localStorage.setItem("token", token)
    }

    override suspend fun getRefreshToken(): String? = window.localStorage.getItem("refresh_token")

    override suspend fun saveRefreshToken(refreshToken: String) {
        window.localStorage.setItem("refresh_token", refreshToken)
    }

    override suspend fun getTokenMetadata(): TokenMetadata? =
        TokenMetadataCodec.decode(window.localStorage.getItem("token_meta"))

    override suspend fun saveTokenMetadata(metadata: TokenMetadata) {
        window.localStorage.setItem("token_meta", TokenMetadataCodec.encode(metadata))
    }

    override suspend fun getIdentity(): Identity? =
        IdentityCodec.decode(window.localStorage.getItem("identity"))

    override suspend fun setIdentity(identity: Identity?) {
        if (identity == null) {
            window.localStorage.removeItem("identity")
        } else {
            window.localStorage.setItem("identity", IdentityCodec.encode(identity))
        }
    }

    override suspend fun clear() {
        window.localStorage.removeItem("token")
        window.localStorage.removeItem("refresh_token")
        window.localStorage.removeItem("token_meta")
    }
}
