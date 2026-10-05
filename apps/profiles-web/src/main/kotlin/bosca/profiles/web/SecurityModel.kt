package bosca.profiles.web

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.CredentialType
import bosca.profiles.web.graphql.DeleteCredential
import bosca.profiles.web.graphql.DeletePasskey
import bosca.profiles.web.graphql.RevokeLogin
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val securityLog = LoggerFactory.getLogger("bosca.profiles.web.SecurityModel")

@Serializable
class SecurityModel(
    val principalId: String,
    val verified: Boolean,
    val created: String,
    val lastLogin: String,
    var credentials: List<CredentialRow>,
    val thirdPartyProviders: List<ThirdPartyProviderRow>,
    var passkeys: List<PasskeyRow>,
    var logins: List<LoginRow>,
    val tab: String,
    var message: String? = null,
    var failed: Boolean = false,
    var messageId: Long = 0,
    var authenticationInvalidated: Boolean = false,
) {
    val statusClass: String get() = if (failed) "status error" else "status ok"
    val hasPassword: Boolean get() = credentials.any { it.type == "PASSWORD" || it.type == "PASSWORD_SCRYPT" }
    val hasOauthCredentials: Boolean get() = credentials.any { it.oauth }
    val activeLoginCount: Int get() = logins.count { it.active }
    val passwordTab: Boolean get() = tab == "password"
    val connectedTab: Boolean get() = tab == "connected"
    val passkeysTab: Boolean get() = tab == "passkeys"
    val loginsTab: Boolean get() = tab == "logins"

    suspend fun deletePasskey(credentialId: String, ctx: RenderContext) {
        try {
            ctx.gql.execute(DeletePasskey, DeletePasskey.Variables(credentialId))
            passkeys = passkeys.filterNot { it.credentialId == credentialId }
            message = "Passkey removed."
            failed = false
            messageId += 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            securityLog.warn("Passkey deletion failed for principal {}: {}", principalId, e.toString())
            message = "We couldn't remove that passkey. Your account must retain at least one credential."
            failed = true
            messageId += 1
        }
    }

    suspend fun unlinkCredential(identifier: String, ctx: RenderContext) {
        try {
            require(hasPassword) { "Add a password before unlinking a third-party account." }
            ctx.gql.execute(
                DeleteCredential,
                DeleteCredential.Variables(
                    principalId = principalId,
                    type = CredentialType.OAUTH2,
                    identifier = identifier,
                ),
            )
            credentials = credentials.filterNot { it.oauth && it.identifier == identifier }
            message = "Third-party account unlinked. Sign in again to continue."
            failed = false
            messageId += 1
            authenticationInvalidated = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            securityLog.warn("Credential unlink failed for principal {}: {}", principalId, e.toString())
            message = e.message ?: "We couldn't unlink that account."
            failed = true
            messageId += 1
        }
    }

    suspend fun revokeLogin(loginId: Long, ctx: RenderContext) {
        try {
            val login = logins.firstOrNull { it.id == loginId } ?: return
            ctx.gql.execute(RevokeLogin, RevokeLogin.Variables(loginId))
            logins = logins.map { if (it.id == loginId) it.copy(revokedAt = "now") else it }
            message = if (login.current) {
                "This sign-in was revoked. Sign in again to continue."
            } else {
                "Sign-in revoked."
            }
            failed = false
            messageId += 1
            authenticationInvalidated = login.current
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            securityLog.warn("Login revocation failed for principal {}: {}", principalId, e.toString())
            message = e.message ?: "We couldn't revoke that sign-in."
            failed = true
            messageId += 1
        }
    }
}
