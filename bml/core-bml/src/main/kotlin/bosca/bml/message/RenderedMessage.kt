package bosca.bml.message

import bosca.bml.email.RenderedEmail

/**
 * Channel outputs rendered by one BML message unit. A message may provide email, push, or both;
 * each present channel is rendered from the same payload, locale, and published template version.
 */
data class RenderedMessage(
    val email: RenderedEmail? = null,
    val push: RenderedPush? = null,
) {
    init {
        require(email != null || push != null) { "A rendered BML message must contain at least one channel" }
    }
}
