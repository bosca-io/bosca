package bosca.core.security.model

import kotlinx.serialization.Serializable

/** A server-verified principal and the profiles available to it. */
@Serializable
data class Identity(
    val principal: Principal,
    val profiles: List<Profile>,
)
