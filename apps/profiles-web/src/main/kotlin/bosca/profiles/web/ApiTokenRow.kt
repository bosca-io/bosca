package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class ApiTokenRow(
    val id: Long,
    val name: String,
    val description: String,
    val prefix: String,
    val scopes: String,
    val expiresAt: String,
    val lastUsedAt: String,
    val lastUsedIp: String,
    val revokedAt: String,
    val active: Boolean,
)
