package bosca.core.preferences.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import bosca.core.security.IdentityCodec
import bosca.core.security.IdentityStorage
import bosca.core.security.TokenMetadataCodec
import bosca.core.security.TokenStorage
import bosca.core.security.model.Identity
import bosca.core.security.model.TokenMetadata

class AndroidTokenStorage(context: Context) : TokenStorage, IdentityStorage {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override suspend fun getToken(): String? = sharedPreferences.getString("token", null)

    override suspend fun saveToken(token: String) {
        sharedPreferences.edit().putString("token", token).apply()
    }

    override suspend fun getRefreshToken(): String? = sharedPreferences.getString("refresh_token", null)

    override suspend fun saveRefreshToken(refreshToken: String) {
        sharedPreferences.edit().putString("refresh_token", refreshToken).apply()
    }

    override suspend fun getTokenMetadata(): TokenMetadata? =
        TokenMetadataCodec.decode(sharedPreferences.getString("token_meta", null))

    override suspend fun saveTokenMetadata(metadata: TokenMetadata) {
        sharedPreferences.edit().putString("token_meta", TokenMetadataCodec.encode(metadata)).apply()
    }

    override suspend fun getIdentity(): Identity? =
        IdentityCodec.decode(sharedPreferences.getString("identity", null))

    override suspend fun setIdentity(identity: Identity?) {
        val editor = sharedPreferences.edit()
        if (identity == null) {
            editor.remove("identity")
        } else {
            editor.putString("identity", IdentityCodec.encode(identity))
        }
        editor.apply()
    }

    override suspend fun clear() {
        sharedPreferences.edit()
            .remove("token")
            .remove("refresh_token")
            .remove("token_meta")
            .apply()
    }
}
