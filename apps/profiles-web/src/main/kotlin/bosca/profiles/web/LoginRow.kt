package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class LoginRow(
    val id: Long,
    val method: String,
    val created: String,
    val revokedAt: String,
    val current: Boolean,
) {
    val active: Boolean get() = revokedAt.isBlank()
}
