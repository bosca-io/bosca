package bosca.security.model

import kotlinx.serialization.Serializable

/**
 * The challenge returned when a sign-up collides with an existing verified account: a single-use
 * pending-link [token] and the proof [methods] available for that account. The client drives the
 * `security.link.*` mutations with these to complete the link.
 */
@Serializable
data class LinkChallenge(
    val token: String,
    val methods: List<LinkProofMethod>,
)
