package bosca.communications.model

/** The provider-ready title, body, and options produced by a BML message push-channel render. */
data class RenderedPushTemplate(
    val title: String,
    val body: String,
    val options: PushOptions? = null,
)
