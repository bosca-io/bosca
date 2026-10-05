package bosca.core.models

import kotlin.time.Instant

data class SignupToken(
    val token: String,
    val created: Instant,
    val expires: Instant,
)
