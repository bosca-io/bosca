package bosca.core.security

import bosca.core.security.model.Identity
import bosca.core.security.model.TokenMetadata
import java.util.prefs.Preferences

class DesktopTokenStorage : TokenStorage, IdentityStorage {
    private val prefs = Preferences.userNodeForPackage(DesktopTokenStorage::class.java)

    override suspend fun getToken(): String? = prefs.get("token", null)

    override suspend fun saveToken(token: String) {
        prefs.put("token", token)
        prefs.flush()
        prefs.sync()
    }

    override suspend fun getRefreshToken(): String? = prefs.get("refresh_token", null)

    override suspend fun saveRefreshToken(refreshToken: String) {
        prefs.put("refresh_token", refreshToken)
        prefs.flush()
        prefs.sync()
    }

    override suspend fun getTokenMetadata(): TokenMetadata? =
        TokenMetadataCodec.decode(prefs.get("token_meta", null))

    override suspend fun saveTokenMetadata(metadata: TokenMetadata) {
        prefs.put("token_meta", TokenMetadataCodec.encode(metadata))
        prefs.flush()
        prefs.sync()
    }

    override suspend fun getIdentity(): Identity? =
        IdentityCodec.decode(prefs.get("identity", null))

    override suspend fun setIdentity(identity: Identity?) {
        if (identity == null) {
            prefs.remove("identity")
        } else {
            prefs.put("identity", IdentityCodec.encode(identity))
        }
        prefs.flush()
        prefs.sync()
    }

    override suspend fun clear() {
        prefs.remove("token")
        prefs.remove("refresh_token")
        prefs.remove("token_meta")
        prefs.flush()
        prefs.sync()
    }
}
