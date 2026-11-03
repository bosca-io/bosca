package bosca.system.ui

import bosca.system.ui.graphql.ISystemTokenFields
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class SystemTokenView(
    val id: Long, val principalId: String, val name: String, val description: String?, val prefix: String,
    val scopes: List<String>?, val allowedGroups: List<String>?, val expiresAt: String?,
    val lastUsedAt: String?, val lastUsedIp: String?, val revokedAt: String?, val active: Boolean,
) {
    val href: String get() = "/system/security/tokens/$id"
    val status: String get() = when {
        revokedAt != null -> "Revoked"
        expiresAt?.let { OffsetDateTime.parse(it).isBefore(OffsetDateTime.now()) } == true -> "Expired"
        active -> "Active"
        else -> "Inactive"
    }
    val tone: String get() = when (status) {
        "Revoked" -> "error"
        "Expired" -> "warning"
        "Active" -> "success"
        else -> "neutral"
    }
}

internal fun ISystemTokenFields.tokenView() = SystemTokenView(id, principalId, name, description, tokenPrefix, scopes, allowedGroups, expiresAt, lastUsedAt, lastUsedIp, revokedAt, active)
