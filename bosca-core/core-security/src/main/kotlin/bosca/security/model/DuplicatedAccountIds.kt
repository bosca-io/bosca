package bosca.security.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A set of distinct principals that share the same verified email address — i.e. duplicate accounts
 * created before sign-up collision prevention existed. Surfaced read-only to admins so a human can
 * decide which principal survives a merge.
 */
@Serializable
data class DuplicatedAccountIds(
    val email: String,
    val principalIds: List<@Contextual UUID>,
)
